package com.playground.chat.domain.service;

import com.playground.chat.domain.enums.Role;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.RetrievedChunk;
import com.playground.chat.domain.model.UserDocumentRef;
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

    /**
     * Per spec §6.1 step 10 — the base prompt is exactly the first paragraph.
     * The tool-honesty paragraph was appended 2026-06-05 after qwen3-vl
     * answered a "다시 생성해줘" turn by PARROTING the previous tool result
     * ("재생성했다") without invoking generate_massing — no artifact was
     * produced. The rule is tool-agnostic so future file-producing tools
     * inherit it.
     */
    public static final String SYSTEM_PROMPT =
            "You are a helpful assistant grounded in the user's playground corpus.\n"
                    + "Cite every factual claim with [N] markers where N is the 1-indexed\n"
                    + "position from the RETRIEVED CONTEXT block. If no chunk supports a\n"
                    + "claim, say so plainly — do not fabricate citations.\n"
                    + "When tools are available: a request to create, generate, or\n"
                    + "REGENERATE an artifact is only satisfied by invoking the matching\n"
                    + "tool in THIS turn. Never state that a file or model was created,\n"
                    + "regenerated, or delivered unless the tool ran in this turn — an\n"
                    + "earlier tool result in the conversation does not satisfy a new\n"
                    + "request. If you did not invoke the tool, say so instead.";

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
        return assemble(retrieved, truncatedHistory, currentUserMessage, perChunkTokenBudget, List.of());
    }

    /**
     * Build the full prompt body, additionally injecting a {@code [YOUR
     * DOCUMENTS]} manifest so the model can resolve a natural-language document
     * reference (ordinal like "두 번째 문서", title, or type) to a concrete
     * {@code documentId} when a tool requires one. An empty/null {@code documents}
     * list renders no section, keeping the M4 (no-tool) prompt byte-identical.
     */
    public String assemble(
            List<RetrievedChunk> retrieved,
            List<Message> truncatedHistory,
            String currentUserMessage,
            int perChunkTokenBudget,
            List<UserDocumentRef> documents) {

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

        if (documents != null && !documents.isEmpty()) {
            sb.append("[YOUR DOCUMENTS]\n");
            sb.append("The caller's uploaded documents, in upload order. When you call a tool that\n"
                    + "needs a document id (e.g. briefDocId), pick the exact id from this list that\n"
                    + "matches the document the user refers to — by ordinal (\"두 번째\"/\"second\"), by\n"
                    + "title, or by type. Never invent an id; if none matches, ask the user.\n");
            for (UserDocumentRef d : documents) {
                String title = (d.title() == null || d.title().isBlank()) ? "(untitled)" : d.title();
                sb.append(d.ordinal()).append(". \"").append(title).append("\"");
                String type = d.mimeType();
                String status = d.extractionStatus();
                if ((type != null && !type.isBlank()) || (status != null && !status.isBlank())) {
                    sb.append(" [");
                    if (type != null && !type.isBlank()) {
                        sb.append(type);
                    }
                    if (status != null && !status.isBlank()) {
                        sb.append(type != null && !type.isBlank() ? ", " : "").append(status);
                    }
                    sb.append(']');
                }
                sb.append(" id=").append(d.documentId()).append('\n');
            }
            sb.append('\n');
        }

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
