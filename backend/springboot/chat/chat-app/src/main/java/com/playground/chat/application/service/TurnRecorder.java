package com.playground.chat.application.service;

import com.playground.chat.application.dto.ChatTurnRequest;
import com.playground.chat.application.dto.CitationDto;
import com.playground.chat.application.repository.AttachmentRepository;
import com.playground.chat.application.repository.MessageRepository;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.MessageCitation;
import com.playground.chat.domain.model.RetrievedChunk;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.service.CitationRenumberer;
import com.playground.chat.domain.service.TokenCounter;
import com.playground.shared.chat.ChatStreamEvent;
import com.playground.shared.chat.SourceRef;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Persists the assistant message + cited subset + staged attachments at turn
 * end and emits the terminal {@code done} SSE event. Extracted from
 * {@code ChatTurnService} — runs on {@code boundedElastic} so the blocking
 * JDBC writes never tie up the reactive event-loop threads.
 */
@Component
public class TurnRecorder {

    private static final Log log = LogFactory.getLog(TurnRecorder.class);

    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final TokenCounter tokenCounter;
    private final Clock clock;

    public TurnRecorder(
            MessageRepository messageRepository,
            AttachmentRepository attachmentRepository,
            TokenCounter tokenCounter,
            Clock clock) {
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.tokenCounter = tokenCounter;
        this.clock = clock;
    }

    Mono<ChatStreamEvent> record(
            TurnContext ctx, List<RetrievedChunk> retrieved, String accumulatedText,
            ChatTurnRequest request,
            MessageId assistantMessageId, List<Attachment> stagedAttachments) {
        return Mono.fromCallable(() -> {
            if (accumulatedText == null || accumulatedText.isEmpty()) {
                // The LLM produced no tokens — emit done with empty content so
                // the client can close the stream cleanly. We still persist an
                // empty assistant message so the message count remains paired.
            }

            // Renumber the [N] markers so the cited subset is rendered as
            // a dense [1][2]… sequence regardless of which positions in
            // the original retrieval window the LLM actually used. The
            // assistant text shipped to the client (and persisted) gets
            // its markers rewritten to the new sequence; the per-row
            // citation cards line up 1:1.
            CitationRenumberer.CitationRenumber renumber =
                    CitationRenumberer.renumberCitations(accumulatedText, retrieved);

            // Approximate token counts — we don't get them from the streaming
            // ChatResponse uniformly; the assistant counter is the byte/token
            // count of the accumulated text via JTokkit.
            int tokensIn = tokenCounter.count(request.message()).value();
            int tokensOut = tokenCounter.count(renumber.text()).value();
            int retrievalK = retrieved.size();

            // Reuse the UP-FRONT-allocated assistant messageId (ADR-20 §D3) so
            // any attachments staged mid-stream already point at this row.
            Message assistant = Message.newAssistantTurn(
                    assistantMessageId, ctx.session().id(), request.caller(), renumber.text(),
                    tokensIn, tokensOut, retrievalK, clock.instant());
            Message persisted = messageRepository.save(assistant);

            List<MessageCitation> toPersist = new java.util.ArrayList<>();
            List<CitationDto> wireCitations = new java.util.ArrayList<>();
            for (CitationRenumberer.CitedChunk c : renumber.cited()) {
                // Snapshot persistence (SP3b spec D4): freeze the corpus-agnostic
                // SourceRef (sourceType/title/content/uri) on the citation row so
                // history reload reads it back without joining the docs schema.
                // The live Done-event DTO and the persisted snapshot carry
                // IDENTICAL values (same RetrievedChunk source, no re-truncation).
                SourceRef s = c.chunk().source();
                toPersist.add(new MessageCitation(persisted.id(), c.newN(), s));
                wireCitations.add(new CitationDto(
                        c.newN(), s.sourceType(), s.title(), s.content(), s.uri()));
            }
            if (!toPersist.isEmpty()) {
                messageRepository.saveCitations(toPersist);
            }

            // ADR-20 §D3 — persist the staged attachments (already in MinIO,
            // already linked to assistantMessageId). The snapshot copy avoids a
            // concurrent-modification read while the (now-terminated) tool
            // callbacks could in theory still be appending.
            List<Attachment> attachments;
            synchronized (stagedAttachments) {
                attachments = new ArrayList<>(stagedAttachments);
            }
            if (!attachments.isEmpty()) {
                attachmentRepository.saveAll(attachments);
            }

            log.info("stream_end sessionId=" + ctx.session().id()
                    + " userId=" + request.caller()
                    + " userSub=" + maskSub(request.userSub())
                    + " messageId=" + persisted.id()
                    + " tokensIn=" + tokensIn
                    + " tokensOut=" + tokensOut
                    + " cited=" + toPersist.size()
                    + " attachments=" + attachments.size());

            // The tool_result SSE event already carries fileUrl for the streaming
            // card; history loads via loadMessages carry the attachment DTO.
            // Never put attachment data inside the citations field — the frontend
            // calls citations.map() and crashes when it receives an object.
            ChatStreamEvent.Done done = new ChatStreamEvent.Done(
                    persisted.id().value().toString(), tokensIn, tokensOut, wireCitations);
            return (ChatStreamEvent) done;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static String maskSub(String sub) {
        if (sub == null || sub.length() <= 4) {
            return "***";
        }
        return sub.substring(0, 4) + "***";
    }
}
