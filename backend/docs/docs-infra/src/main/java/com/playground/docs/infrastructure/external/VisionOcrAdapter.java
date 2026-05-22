package com.playground.docs.infrastructure.external;

import java.io.IOException;
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
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Spring AI multimodal adapter — converts a single rendered PDF page (PNG)
 * into Markdown via the spark-inference-gateway's OpenAI-compatible
 * {@code /v1/chat/completions} endpoint per M6 ADR-16 + M6.1 ADR-12 §A12.10.
 *
 * <p>M6.1 retry classification:
 * <ul>
 *   <li>5xx / IOException / connect timeout → throws
 *       {@link RetryableVisionException} so the upstream
 *       {@link PdfExtractorAdapter} retries with backoff (up to 2 retries).</li>
 *   <li>4xx (bad prompt, model not loaded, etc.) → swallowed, returns empty
 *       markdown. The upstream caller treats the page as failed.</li>
 *   <li>Anything else (unexpected runtime exception) → swallowed, returns
 *       empty markdown so the whole extraction never crashes on a single
 *       page anomaly.</li>
 * </ul>
 */
@Component
public class VisionOcrAdapter {

    private static final Logger log = LoggerFactory.getLogger(VisionOcrAdapter.class);

    private static final String SYSTEM_PROMPT =
            "당신은 PDF 페이지를 정확한 markdown으로 변환하는 전문가입니다. "
                    + "표는 markdown table로, heading은 #/##로, 본문은 그대로 표현합니다. "
                    + "추가 설명이나 코드 블록 wrapper 없이 markdown 본문만 출력하세요.";

    private static final String USER_PROMPT = "이 페이지를 markdown으로 변환해주세요.";

    private static final Pattern CODE_FENCE = Pattern.compile(
            "^```(?:markdown)?\\s*\\n?([\\s\\S]*?)\\n?```\\s*$");

    private final ChatClient chatClient;

    public VisionOcrAdapter(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Convert a single rendered PNG page to Markdown.
     *
     * @throws RetryableVisionException on 5xx / IO / connect-timeout — caller
     *         should retry with backoff.
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
        } catch (HttpServerErrorException | ResourceAccessException e) {
            // 5xx / IO / connect: retryable per ADR-12 §A12 retry classification.
            throw new RetryableVisionException(e);
        } catch (RuntimeException e) {
            // 4xx / parsing / programming error — non-retryable. Log and
            // return empty so the upstream caller treats the page as failed
            // and continues with the rest of the document.
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw new RetryableVisionException(e);
            }
            log.warn("Vision OCR non-retryable failure, returning empty: {}", e.toString());
            return "";
        }
    }

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

    /**
     * Marker so the upstream extractor can distinguish retryable
     * (5xx / IO / connect-timeout) failures from terminal ones.
     */
    public static final class RetryableVisionException extends RuntimeException {
        public RetryableVisionException(Throwable cause) {
            super(cause);
        }
    }
}
