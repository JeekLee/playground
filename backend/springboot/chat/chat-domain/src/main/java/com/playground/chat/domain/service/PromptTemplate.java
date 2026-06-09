package com.playground.chat.domain.service;

import com.playground.chat.domain.enums.Role;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.UserModelRef;
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
                    + "When the user asks about the CONTENT, requirements, figures,\n"
                    + "or details of their uploaded documents — including follow-up\n"
                    + "questions — you MUST call search_documents FIRST and answer\n"
                    + "only from this turn's results. Do not answer document questions\n"
                    + "from memory or earlier turns. Claims about document contents\n"
                    + "must cite [N] markers referring ONLY to search_documents results\n"
                    + "retrieved in THIS turn; never fabricate an [N]. Cite each source\n"
                    + "as its own marker like [1][2] — never group them as [1, 2].\n"
                    + "Casual conversation and general knowledge need no search and no\n"
                    + "citations.\n"
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
     * reference (ordinal like "두 번째 문서" or title) to a concrete
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
            List<UserDocumentRef> documents,
            List<UserModelRef> models) {

        StringBuilder sb = new StringBuilder(8192);

        sb.append("[SYSTEM]\n").append(SYSTEM_PROMPT).append("\n\n");

        if (documents != null && !documents.isEmpty()) {
            sb.append("[YOUR DOCUMENTS]\n");
            sb.append("The caller's uploaded documents, in upload order. When you call a tool that\n"
                    + "needs a document id (e.g. briefDocId), pick the exact id from this list that\n"
                    + "matches the document the user refers to — by ordinal (\"두 번째\"/\"second\") or by\n"
                    + "title. Never invent an id; if none matches, ask the user.\n");
            for (UserDocumentRef d : documents) {
                String title = (d.title() == null || d.title().isBlank()) ? "(untitled)" : d.title();
                sb.append(d.ordinal()).append(". \"").append(title).append("\"")
                  .append(" id=").append(d.documentId()).append('\n');
            }
            sb.append('\n');
        }

        if (models != null && !models.isEmpty()) {
            sb.append("[YOUR MODELS]\n");
            sb.append("3D massing models already generated in this session, in creation\n"
                    + "order. To MODIFY one with refine_massing, copy the matching id into\n"
                    + "baseAttachmentId. Never invent an id; if none matches, ask the user.\n");
            for (UserModelRef m : models) {
                String label = (m.label() == null || m.label().isBlank()) ? "(untitled)" : m.label();
                sb.append(m.ordinal()).append(". \"").append(label).append("\"")
                  .append(" id=").append(m.attachmentId()).append('\n');
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
