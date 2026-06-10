package com.playground.chat.application.service;

import com.playground.chat.application.dto.ChatTurnRequest;
import com.playground.chat.application.port.UserDocumentManifestPort;
import com.playground.chat.application.properties.ChatProperties;
import com.playground.chat.application.repository.AttachmentRepository;
import com.playground.chat.application.repository.MessageRepository;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.UserModelRef;
import com.playground.chat.domain.model.vo.TokenCount;
import com.playground.chat.domain.service.HistoryTruncator;
import com.playground.chat.domain.service.TokenCounter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;

/**
 * Assembles the per-turn {@link TurnContext} after the gate (rate-limit +
 * session ownership) has run in {@code ChatTurnService}: history load +
 * truncate, persist the user message, determine the first-turn flag, and
 * build the caller's document manifest.
 */
@Component
@RequiredArgsConstructor
public class TurnContextAssembler {

    private static final Log log = LogFactory.getLog(TurnContextAssembler.class);

    /**
     * Cap on the document manifest injected into the prompt's [YOUR DOCUMENTS]
     * section. Bounds prompt tokens; personal-scale corpora sit well under it.
     */
    static final int DOCUMENT_MANIFEST_LIMIT = 30;

    /**
     * Cap on the model manifest injected into the prompt's [YOUR MODELS]
     * section. Bounds prompt tokens; a single session rarely produces this many
     * massing models.
     */
    static final int MODEL_MANIFEST_LIMIT = 20;

    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final TokenCounter tokenCounter;
    private final HistoryTruncator historyTruncator;
    private final UserDocumentManifestPort userDocumentManifestPort;
    private final Clock clock;
    private final ChatProperties properties;

    TurnContext assemble(ChatTurnRequest request, ChatSession session) {
        // 3. History load + truncate.
        List<Message> rawHistory = messageRepository.findBySession(session.id());
        TokenCount currentMessageTokens = tokenCounter.count(request.message());
        List<Message> truncated = historyTruncator.truncate(
                rawHistory, properties.maxHistoryTokens(), currentMessageTokens);

        // 4. Persist the user message before opening the stream so a mid-stream
        // refresh shows the user turn even if the assistant never lands.
        // (agentic-search spec D2: the always-on embed+pgvector retrieval was
        // removed — the LLM searches via the search_documents tool only when
        // the question concerns uploaded-document content.)
        Message userMessage = Message.newUserTurn(
                session.id(), request.caller(), request.message(), clock.instant());
        Message savedUser = messageRepository.save(userMessage);

        // 5. Determine whether this is the first user turn for the auto-title trigger.
        int userMessageCount = messageRepository.countUserMessages(session.id());
        boolean firstTurn = userMessageCount == 1;

        // Caller's document manifest for the [YOUR DOCUMENTS] prompt section, so
        // the model can resolve an ordinal/title reference to a briefDocId.
        List<UserDocumentRef> documents = safeManifestLookup(
                () -> userDocumentManifestPort.recentForUser(request.caller(), DOCUMENT_MANIFEST_LIMIT),
                "document_manifest_lookup_failed userId=" + request.caller());

        // Caller's session model manifest for the [YOUR MODELS] prompt section,
        // so the model can resolve a reference ("두 번째 모델"/"the library
        // massing") to a concrete attachmentId for refine_massing's
        // baseAttachmentId. The query returns newest-first; reverse it so the
        // manifest reads oldest-first with a 1-based creation-order ordinal.
        // Session-scoped + owner-checked.
        List<UserModelRef> models = safeManifestLookup(() -> {
            List<Attachment> rows =
                    attachmentRepository.findModelAttachments(session.id(), request.caller(), MODEL_MANIFEST_LIMIT);
            List<UserModelRef> refs = new ArrayList<>(rows.size());
            int ord = 1;
            for (int i = rows.size() - 1; i >= 0; i--) {
                Attachment a = rows.get(i);
                String label = (a.briefTitle() != null && !a.briefTitle().isBlank())
                        ? a.briefTitle() : a.filename();
                refs.add(new UserModelRef(ord++, a.id().value(), label));
            }
            return refs;
        }, "model_manifest_lookup_failed sessionId=" + session.id());

        log.info("turn_start sessionId=" + session.id()
                + " userId=" + request.caller()
                + " userSub=" + LogMasking.maskSub(request.userSub())
                + " historyTurns=" + truncated.size()
                + " docManifest=" + documents.size()
                + " modelManifest=" + models.size());

        return new TurnContext(session, truncated, savedUser, firstTurn, documents, models);
    }

    /**
     * Run a manifest lookup, degrading gracefully to an empty list on any
     * failure (a missing [YOUR DOCUMENTS]/[YOUR MODELS] section is a soft loss,
     * never a turn-breaker). {@code failTag} is the log prefix; the exception
     * message is appended.
     */
    private <T> List<T> safeManifestLookup(java.util.function.Supplier<List<T>> lookup, String failTag) {
        try {
            return lookup.get();
        } catch (RuntimeException e) {
            log.warn(failTag + " error=" + e.getMessage());
            return List.of();
        }
    }
}
