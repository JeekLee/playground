package com.playground.chat.domain.service;

import com.playground.chat.domain.enums.Role;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.UserDocumentRef;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Per-turn prompt assembler per ADR-14 §1 + spec §6.1 step 10. Pure text
 * concatenation — no Spring AI prompt-template DSL, no Mustache, just
 * deterministic string building so the output is testable against fixtures.
 *
 * <p>Output shape (agentic-search spec D2 — the always-on RETRIEVED CONTEXT
 * block was removed; the LLM searches via the search_documents tool on
 * demand and cites turn-global [N] markers):
 * <pre>
 * [SYSTEM]
 * &lt;system prompt&gt;
 *
 * [YOUR DOCUMENTS]   (only when tools are available)
 * 1. "title" id=...
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
     * agentic-search spec D2 — the grounding paragraph now ties [N] citations
     * to search_documents results retrieved IN THIS turn (the always-on
     * RETRIEVED CONTEXT block is gone). The tool-honesty paragraph (appended
     * 2026-06-05, PR #230, after qwen3-vl PARROTED a previous tool result
     * "재생성했다" without invoking generate_massing) is kept verbatim — it is
     * tool-agnostic so every file-producing tool inherits it.
     */
    public static final String SYSTEM_PROMPT =
            "You are a helpful assistant for the user's playground workspace.\n"
                    + "Claims about the user's documents must cite [N] markers that\n"
                    + "refer ONLY to search_documents results retrieved in THIS turn.\n"
                    + "Never assert document contents you have not retrieved this\n"
                    + "turn — call search_documents first. Casual conversation and\n"
                    + "general knowledge need no search and no citations.\n"
                    + "When tools are available: a request to create, generate, or\n"
                    + "REGENERATE an artifact is only satisfied by invoking the matching\n"
                    + "tool in THIS turn. Never state that a file or model was created,\n"
                    + "regenerated, or delivered unless the tool ran in this turn — an\n"
                    + "earlier tool result in the conversation does not satisfy a new\n"
                    + "request. If you did not invoke the tool, say so instead.";

    private final CitationExtractor citationExtractor;

    public PromptTemplate(TokenCounter tokenCounter, CitationExtractor citationExtractor) {
        // tokenCounter is retained in the constructor signature for wiring
        // compatibility; the per-chunk budget truncation it backed was removed
        // with the RETRIEVED CONTEXT block (agentic-search spec D2).
        this.citationExtractor = citationExtractor;
    }

    /**
     * Build the full prompt body, injecting a {@code [YOUR DOCUMENTS]} manifest
     * (when present) so the model can resolve a natural-language document
     * reference (ordinal like "두 번째 문서", title, or type) to a concrete
     * {@code documentId} for a tool argument. An empty/null {@code documents}
     * list renders no section, keeping the no-tool prompt byte-identical.
     *
     * <p>agentic-search spec D2: there is no longer a RETRIEVED CONTEXT block —
     * document content reaches the model only via search_documents tool
     * results within the turn.
     */
    public String assemble(
            List<Message> truncatedHistory,
            String currentUserMessage,
            List<UserDocumentRef> documents) {

        StringBuilder sb = new StringBuilder(8192);

        sb.append("[SYSTEM]\n").append(SYSTEM_PROMPT).append("\n\n");

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
