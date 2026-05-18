package com.playground.ragchat.domain.service;

import com.playground.ragchat.domain.enums.Role;
import com.playground.ragchat.domain.model.Message;
import com.playground.ragchat.domain.model.RetrievedChunk;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Per-turn prompt assembler per ADR-14 §1 + spec §6.1 step 10. Pure text
 * concatenation — no Spring AI prompt-template DSL, no Mustache, just
 * deterministic string building so the output is testable against fixtures.
 *
 * <p>Output shape:
 * <pre>
 * [SYSTEM]
 * &lt;system prompt&gt;
 *
 * [RETRIEVED CONTEXT]
 * [1] &lt;title&gt; (visibility=&lt;v&gt;) — &lt;chunk text&gt;
 * [2] ...
 *
 * [CONVERSATION SO FAR]
 * user: ...
 * assistant: ...
 *
 * [CURRENT TURN]
 * user: &lt;message&gt;
 * assistant:
 * </pre>
 */
@Service
public class PromptTemplate {

    /** Per spec §6.1 step 10 — the system prompt is exactly this text. */
    public static final String SYSTEM_PROMPT =
            "You are a helpful assistant grounded in the user's playground corpus.\n"
                    + "Cite every factual claim with [N] markers where N is the 1-indexed\n"
                    + "position from the RETRIEVED CONTEXT block. If no chunk supports a\n"
                    + "claim, say so plainly — do not fabricate citations.";

    private final TokenCounter tokenCounter;
    private final CitationExtractor citationExtractor;

    public PromptTemplate(TokenCounter tokenCounter, CitationExtractor citationExtractor) {
        this.tokenCounter = tokenCounter;
        this.citationExtractor = citationExtractor;
    }

    /**
     * Build the full prompt body. {@code perChunkTokenBudget} (typically 400
     * per ADR-14 §8) head-truncates each retrieved chunk's text.
     */
    public String assemble(
            List<RetrievedChunk> retrieved,
            List<Message> truncatedHistory,
            String currentUserMessage,
            int perChunkTokenBudget) {

        StringBuilder sb = new StringBuilder(8192);

        sb.append("[SYSTEM]\n").append(SYSTEM_PROMPT).append("\n\n");

        sb.append("[RETRIEVED CONTEXT]\n");
        if (retrieved == null || retrieved.isEmpty()) {
            sb.append("(no chunks retrieved for this query)\n");
        } else {
            for (RetrievedChunk c : retrieved) {
                String title = (c.title() == null || c.title().isBlank()) ? "(untitled)" : c.title();
                String text = tokenCounter.truncateToTokens(c.text(), perChunkTokenBudget);
                sb.append('[').append(c.position()).append("] ")
                        .append(title)
                        .append(" (visibility=").append(c.visibility().wireValue()).append(") — ")
                        .append(text).append('\n');
            }
        }
        sb.append('\n');

        sb.append("[CONVERSATION SO FAR]\n");
        if (truncatedHistory == null || truncatedHistory.isEmpty()) {
            sb.append("(this is the start of the conversation)\n");
        } else {
            for (Message m : truncatedHistory) {
                String content = m.role() == Role.ASSISTANT
                        ? citationExtractor.stripMarkers(m.content())
                        : m.content();
                sb.append(m.role().wireValue()).append(": ").append(content).append('\n');
            }
        }
        sb.append('\n');

        sb.append("[CURRENT TURN]\n");
        sb.append("user: ").append(currentUserMessage).append('\n');
        sb.append("assistant:\n");

        return sb.toString();
    }
}
