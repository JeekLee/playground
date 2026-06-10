package com.playground.chat.application.service;

import com.playground.chat.application.port.ChatGenerationPort;
import com.playground.chat.application.repository.SessionRepository;
import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.id.SessionId;
import lombok.RequiredArgsConstructor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Fire-and-forget auto-title per ADR-14 §6 — invoked after the first user
 * message persists on a fresh session. Runs on
 * {@link Schedulers#boundedElastic()} so it does not block the request thread.
 * Failures (4xx/5xx/breaker open) WARN-log and leave the title as
 * {@link ChatSession#DEFAULT_TITLE}.
 */
@Service
@RequiredArgsConstructor
public class AutoTitleService {

    private static final Log log = LogFactory.getLog(AutoTitleService.class);

    /** Pinned system prompt per ADR-14 §6 (verbatim). */
    public static final String SYSTEM_PROMPT =
            "You generate a concise 2 to 6 word title for a chat conversation.\n"
                    + "The user just sent their first message in a new chat session.\n"
                    + "Read the message and produce a title that captures the topic.\n"
                    + "\n"
                    + "Rules:\n"
                    + "- Output ONLY the title. No quotes, no punctuation at the end, no explanation.\n"
                    + "- 2 to 6 words. Use Title Case for English, plain spacing for Korean.\n"
                    + "- Match the language of the user message (English in -> English out; Korean in -> Korean out).\n"
                    + "- If the message is too short or vague to title (one-word, \"hi\", \"test\"), output: New chat\n"
                    + "- Do not invent specifics not present in the message.";

    /** ADR-14 §6 — 1 KB user-message cap before truncation. */
    private static final int USER_MESSAGE_CAP_BYTES = 1024;

    /** ADR-14 §6 — auto-title generation options. */
    private static final double TEMPERATURE = 0.1;

    private static final int MAX_TOKENS = 24;

    /** Defensive output cap per ADR-14 §6 — drop trailing punctuation + cap to 60 chars. */
    private static final int OUTPUT_CHAR_CAP = 60;

    private final ChatGenerationPort chatGenerationPort;
    private final SessionRepository sessionRepository;

    /**
     * Fire-and-forget. Returns a {@link Mono} the caller subscribes via
     * {@code .subscribeOn(Schedulers.boundedElastic()).subscribe()} so the
     * request thread is freed immediately.
     */
    public Mono<Void> generate(SessionId sessionId, String firstUserMessage) {
        String trimmed = truncateTo1KB(firstUserMessage);
        if (trimmed == null || trimmed.isBlank()) {
            return Mono.empty();
        }
        String prompt = SYSTEM_PROMPT + "\n\nUser message:\n" + trimmed + "\n\nTitle:";
        return chatGenerationPort.generateOnce(prompt, TEMPERATURE, MAX_TOKENS)
                .map(AutoTitleService::sanitize)
                .filter(s -> s != null && !s.isBlank() && !ChatSession.DEFAULT_TITLE.equalsIgnoreCase(s))
                .doOnNext(title -> {
                    boolean updated = sessionRepository.renameIfDefault(sessionId, title);
                    if (updated) {
                        log.info("auto-title sessionId=" + sessionId + " title=\"" + title + "\"");
                    } else {
                        log.debug("auto-title skipped sessionId=" + sessionId + " — title already changed");
                    }
                })
                .doOnError(err -> log.warn(
                        "auto-title failed sessionId=" + sessionId + " reason=" + err.toString()))
                .onErrorResume(err -> Mono.empty())
                .then();
    }

    static String truncateTo1KB(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        byte[] bytes = trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= USER_MESSAGE_CAP_BYTES) {
            return trimmed;
        }
        // Cut on a UTF-8 char boundary at or below the cap.
        return new String(bytes, 0, USER_MESSAGE_CAP_BYTES, java.nio.charset.StandardCharsets.UTF_8)
                // Java's UTF-8 decoder substitutes the replacement char for any
                // mid-codepoint truncation; chop trailing replacement chars.
                .replaceAll("\\uFFFD+$", "");
    }

    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        // Drop trailing punctuation per ADR-14 §6.
        while (!s.isEmpty()) {
            char c = s.charAt(s.length() - 1);
            if (c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' || c == '"' || c == '\'') {
                s = s.substring(0, s.length() - 1).trim();
            } else {
                break;
            }
        }
        if (s.length() > OUTPUT_CHAR_CAP) {
            s = s.substring(0, OUTPUT_CHAR_CAP);
        }
        return s;
    }
}
