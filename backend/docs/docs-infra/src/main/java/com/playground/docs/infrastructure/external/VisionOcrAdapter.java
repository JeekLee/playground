package com.playground.docs.infrastructure.external;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * Spring AI multimodal adapter — converts a single rendered PDF page (PNG)
 * into Markdown via the spark-inference-gateway's OpenAI-compatible
 * {@code /v1/chat/completions} endpoint per M6 ADR-16.
 *
 * <p>Wired against the same {@code ChatClient.Builder} bean Spring AI's
 * {@code spring-ai-starter-model-openai} registers; the docs-api's
 * {@code application.yml} pins the Vision model name via
 * {@code spring.ai.openai.chat.options.model = ${SPRING_AI_VISION_MODEL}}.
 *
 * <p>Per ADR-16 the adapter MUST NOT fail the whole upload when the Vision
 * gateway is unreachable or the model is still loading — it returns an empty
 * string for the failing page and lets the upstream {@link
 * com.playground.docs.application.port.PdfExtractorPort} concatenate it with
 * the text-layer pages.
 */
@Component
public class VisionOcrAdapter {

    private static final Logger log = LoggerFactory.getLogger(VisionOcrAdapter.class);

    /** ADR-16 verbatim system prompt. */
    private static final String SYSTEM_PROMPT =
            "당신은 PDF 페이지를 정확한 markdown으로 변환하는 전문가입니다. "
                    + "표는 markdown table로, heading은 #/##로, 본문은 그대로 표현합니다. "
                    + "추가 설명이나 코드 블록 wrapper 없이 markdown 본문만 출력하세요.";

    /** ADR-16 verbatim user prompt. */
    private static final String USER_PROMPT = "이 페이지를 markdown으로 변환해주세요.";

    /** Strip a leading/trailing {@code ```markdown ... ```} wrapper if the model emits one. */
    private static final Pattern CODE_FENCE = Pattern.compile(
            "^```(?:markdown)?\\s*\\n?([\\s\\S]*?)\\n?```\\s*$");

    private final ChatClient chatClient;

    public VisionOcrAdapter(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Convert a single rendered PNG page to Markdown. Returns {@code ""} on
     * any failure (transient gateway error, model not loaded, 4xx error
     * response) so the upstream PdfExtractor can finish the rest of the
     * document.
     *
     * @param pngBytes PNG-encoded image bytes of one PDF page
     * @return markdown body (possibly empty)
     */
    public String toMarkdown(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            return "";
        }
        try {
            Media image = Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(new ByteArrayResource(pngBytes))
                    .build();
            UserMessage userMessage = UserMessage.builder()
                    .text(USER_PROMPT)
                    .media(image)
                    .build();
            SystemMessage systemMessage = new SystemMessage(SYSTEM_PROMPT);
            Prompt prompt = new Prompt(systemMessage, userMessage);
            String raw = chatClient.prompt(prompt).call().content();
            return stripCodeFence(raw == null ? "" : raw);
        } catch (RuntimeException e) {
            // ADR-16: never let a Vision failure crash the upload. Log and
            // fall through to an empty page body. Common failures during
            // operator rollout: model not loaded (5xx), gateway unreachable
            // (connect refused), 400 from a stale prompt template.
            log.warn("Vision OCR failed for page, returning empty markdown: {}", e.toString());
            return "";
        }
    }

    /**
     * If the model wraps its output in a ``` markdown fence (despite the
     * system prompt asking it not to), strip the fence. Defense in depth —
     * the system prompt is verbatim from ADR-16 but model adherence varies.
     */
    static String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        Matcher m = CODE_FENCE.matcher(trimmed);
        if (m.matches()) {
            return m.group(1).trim();
        }
        return trimmed;
    }
}
