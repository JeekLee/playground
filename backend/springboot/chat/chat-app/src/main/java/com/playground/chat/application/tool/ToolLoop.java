package com.playground.chat.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.chat.application.properties.ChatProperties;
import com.playground.chat.application.service.TurnCitationAccumulator;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.id.AttachmentId;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.tool.ToolDescriptor;
import com.playground.chat.domain.tool.ToolErrorCode;
import com.playground.shared.chat.ChatStreamEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Sinks;

/**
 * Per-turn tool-calling loop extracted from {@code ChatTurnService}. NOT a
 * Spring bean — it is {@code new}-ed once per turn and holds per-turn mutable
 * state (depth, in-flight counter, staged attachments, citation accumulator).
 */
public final class ToolLoop {

    private static final Log log = LogFactory.getLog(ToolLoop.class);

    /** Download URL prefix for message attachments (ADR-20 §D4). FE-facing, gateway-routed. */
    private static final String ATTACHMENT_DOWNLOAD_PREFIX = "/api/chat/attachments/";

    private final ToolDispatcherPort toolDispatcherPort;
    private final ObjectMapper objectMapper;
    private final ChatProperties properties;
    private final Clock clock;

    private final Sinks.Many<ChatStreamEvent> sink;
    private final AtomicInteger depth;
    private final AtomicInteger inFlightTools;
    private final List<Attachment> stagedAttachments;
    private final TurnCitationAccumulator acc;
    private final MessageId assistantMessageId;
    private final UserContext userCtx;
    private final SessionId sessionId;

    public ToolLoop(
            ToolDispatcherPort toolDispatcherPort,
            ObjectMapper objectMapper,
            ChatProperties properties,
            Clock clock,
            Sinks.Many<ChatStreamEvent> sink,
            AtomicInteger depth,
            AtomicInteger inFlightTools,
            List<Attachment> stagedAttachments,
            TurnCitationAccumulator acc,
            MessageId assistantMessageId,
            UserContext userCtx,
            SessionId sessionId) {
        this.toolDispatcherPort = toolDispatcherPort;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.sink = sink;
        this.depth = depth;
        this.inFlightTools = inFlightTools;
        this.stagedAttachments = stagedAttachments;
        this.acc = acc;
        this.assistantMessageId = assistantMessageId;
        this.userCtx = userCtx;
        this.sessionId = sessionId;
    }

    /**
     * Build one {@link ToolBinding} per registered descriptor. The binding's
     * handler is the boundary where (per ADR-17 §3.1) we:
     * <ol>
     *   <li>Check depth cap — if exceeded, emit terminal {@code tool_error}
     *       with {@code MAX_DEPTH} and throw so Spring AI aborts the
     *       round-trip.</li>
     *   <li>Generate a correlation id (server-side ULID-prefixed when Spring
     *       AI does not surface its own — M7 P0 generates per call).</li>
     *   <li>Emit {@code tool_call} to the sink BEFORE calling the
     *       dispatcher.</li>
     *   <li>Invoke {@link ToolDispatcherPort}.</li>
     *   <li>Emit {@code tool_result} (success) or {@code tool_error}
     *       (failure) to the sink, AFTER the dispatcher returns but BEFORE
     *       returning the JSON to Spring AI.</li>
     *   <li>For terminal failure codes ({@code CIRCUIT_OPEN} / {@code MAX_DEPTH})
     *       throw so Spring AI does not continue the round-trip; the
     *       use-case observes the upstream error and the sink already
     *       carries the terminal event.</li>
     * </ol>
     */
    public List<ToolBinding> bindings(List<ToolDescriptor> descriptors) {
        List<ToolBinding> bindings = new ArrayList<>(descriptors.size());
        for (ToolDescriptor d : descriptors) {
            ToolDescriptor desc = d;
            bindings.add(new ToolBinding(desc, args -> handle(desc, args)));
        }
        return bindings;
    }

    private JsonNode handle(ToolDescriptor desc, JsonNode args) {
        String id = "call_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        int newDepth = depth.incrementAndGet();
        int maxDepth = properties.toolMaxDepth();
        if (newDepth > maxDepth) {
            log.warn("tool_max_depth_exceeded tool=" + desc.name() + " depth=" + newDepth + " cap=" + maxDepth);
            sink.tryEmitNext(new ChatStreamEvent.ToolError(
                    id, desc.name(), ToolErrorCode.MAX_DEPTH.name(),
                    "Tool-call depth cap of " + maxDepth + " exceeded"));
            sink.tryEmitComplete();
            throw new MaxDepthExceededException(desc.name(), maxDepth);
        }
        // tool_call BEFORE dispatching (ADR-17 §3.1 step 3a). tool_call /
        // tool_result / tool_error all emit from THIS (dispatch) thread — a
        // single serialization point — so tryEmitNext is safe here.
        sink.tryEmitNext(new ChatStreamEvent.ToolCall(id, desc.name(), desc.displayName(), args));

        // Progress relays from the dispatcher's NDJSON `progress` lines, which
        // the WebClient decodes on a netty event-loop thread — a DIFFERENT
        // thread than the dispatch thread that emits tool_call/result/error
        // into this sink. With multicast().onBackpressureBuffer() the
        // tryEmitNext is non-serialized and a concurrent emission silently
        // drops as FAIL_NON_SERIALIZED, so we use emitNext + busyLooping to
        // retry briefly instead of dropping a progress event (Task 3 review
        // Critical). The spin lands ON the I/O event-loop thread, so keep it
        // short (50ms): contention is near-zero anyway — the dispatch thread
        // is parked in blockLast while progress flows — and progress is
        // droppable + resume-recoverable. emitNext may throw on exhaustion,
        // but the dispatcher's emitProgress isolates RuntimeException from
        // the listener, so the stream stays safe.
        // The in-flight window spans the dispatch AND its result handling: while
        // it is open the outer token-inactivity guard is suspended (the
        // dispatcher's idle 60s / total 600s budget is the liveness authority).
        // The finally decrements on EVERY exit — Success return, CIRCUIT_OPEN /
        // terminal throw, non-terminal synthetic-error return, or any exception
        // from invoke itself — exactly once.
        inFlightTools.incrementAndGet();
        try {
        ToolInvocationResult result = toolDispatcherPort.invoke(
                id, desc.name(), args, userCtx,
                p -> sink.emitNext(
                        new ChatStreamEvent.ToolProgress(p.id(), p.name(), p.stage(), p.label(),
                                p.stageIndex(), p.stageCount(), p.attempt()),
                        Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(50))));
        if (result instanceof ToolInvocationResult.Success s) {
            // agentic-search spec D2 — for search_documents, renumber the
            // per-call [N] positions into the turn-global space and accumulate
            // the chunks. The renumbered body is what BOTH the SSE tool_result
            // and the LLM-bound feedback see, so the [N] markers the model
            // cites line up with the citation cards. Other tools bypass absorb.
            JsonNode llmVisibleBody = s.body();
            if ("search_documents".equals(desc.name()) && s.body() != null) {
                llmVisibleBody = acc.absorb(s.body());
            }
            // ADR-20 §D3 — if the tool emitted an artifact, store it to MinIO
            // and stage an Attachment linked to the (pre-allocated) assistant
            // message. Only the LLM-visible `body` (result) reaches the LLM +
            // the tool_result SSE result field; the bytes never enter context.
            Attachment attachment = null;
            if (s.artifact() != null) {
                String briefTitle = llmVisibleBody != null && llmVisibleBody.has("briefTitle")
                        ? llmVisibleBody.get("briefTitle").asText(null)
                        : null;
                attachment = storeArtifact(
                        s.artifact(), desc.name(), briefTitle);
            }
            // tool_result SSE result carries the LLM result PLUS (per ADR-20 §D4)
            // an `attachment` object + top-level `fileUrl` for the FE. The
            // LLM-bound feedback below is the bare `body` only.
            sink.tryEmitNext(new ChatStreamEvent.ToolResult(
                    s.id(), s.name(), enrichResultForSse(llmVisibleBody, attachment)));
            // Truncate the body to the configured cap if needed for the
            // LLM-bound feedback. The dispatcher already truncates at its
            // own boundary (ADR-17 §4) but we re-cap here defensively
            // before sending back to Spring AI.
            return truncateForLlm(llmVisibleBody);
        }
        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) result;
        sink.tryEmitNext(new ChatStreamEvent.ToolError(f.id(), f.name(), f.code().name(), f.message()));
        if (f.code() == ToolErrorCode.CIRCUIT_OPEN) {
            // Terminal — abort the round-trip so the LLM does not get a chance
            // to retry (ADR-17 §3.1 + Story 6 operator cost-protection).
            sink.tryEmitComplete();
            throw new ToolCallTerminalException(f.code(), f.message());
        }
        // Non-terminal — feed the error back to the LLM as a synthetic tool
        // result so the LLM can either apologize, retry with corrected args,
        // or fall back to a natural-language response (ADR-17 §2 table).
        ObjectNode err = objectMapper.createObjectNode();
        err.put("error", true);
        err.put("code", f.code().name());
        err.put("message", f.message() == null ? "" : f.message());
        return err;
        } finally {
            inFlightTools.decrementAndGet();
        }
    }

    /**
     * Stage an {@link Attachment} from the tool artifact per ADR-20 §D3 revised.
     *
     * <p>agent-tools owns the MinIO write path and already stored the file before
     * returning its response. This method just records the pointer — allocates an
     * {@link AttachmentId}, creates the domain object with the pre-existing
     * {@code storageKey}, and adds it to {@code stagedAttachments} for batch
     * persist in {@code persistAssistantAndDone}.
     */
    private Attachment storeArtifact(
            ToolArtifact artifact,
            String toolName,
            String briefTitle) {
        try {
            AttachmentId attachmentId = AttachmentId.generate();
            Attachment attachment = Attachment.toolArtifact(
                    attachmentId,
                    assistantMessageId,
                    artifact.filename(),
                    artifact.contentTypeOrDefault(),
                    artifact.sizeBytes(),
                    artifact.storageKey(),
                    toolName,
                    briefTitle,
                    clock.instant());
            stagedAttachments.add(attachment);
            log.info("tool_artifact_staged tool=" + toolName
                    + " attachmentId=" + attachmentId
                    + " messageId=" + assistantMessageId
                    + " sizeBytes=" + artifact.sizeBytes()
                    + " storageKey=" + artifact.storageKey());
            return attachment;
        } catch (RuntimeException e) {
            // Unexpected — staging should be infallible (no I/O). Log and degrade
            // gracefully so the turn still completes without the attachment.
            log.warn("tool_artifact_stage_failed tool=" + toolName
                    + " messageId=" + assistantMessageId + " reason=" + e.toString());
            return null;
        }
    }

    /**
     * Build the {@code tool_result} SSE result payload (ADR-20 §D4): the
     * LLM-visible result body, plus — when an artifact was captured — an
     * {@code attachment} object and a top-level {@code fileUrl} the FE's
     * MassingResultCard reads. When there's no artifact, the body is returned
     * unchanged (M7 wire shape, byte-identical for plain tools).
     */
    private Object enrichResultForSse(JsonNode body, Attachment attachment) {
        if (attachment == null) {
            return body;
        }
        ObjectNode enriched;
        if (body != null && body.isObject()) {
            enriched = ((ObjectNode) body).deepCopy();
        } else {
            // Non-object result (rare for a file-producing tool) — nest it under
            // `result` so the attachment fields have somewhere to live.
            enriched = objectMapper.createObjectNode();
            enriched.set("result", body == null ? objectMapper.nullNode() : body.deepCopy());
        }
        String downloadUrl = ATTACHMENT_DOWNLOAD_PREFIX + attachment.id();
        ObjectNode attachmentNode = objectMapper.createObjectNode();
        attachmentNode.put("id", attachment.id().toString());
        attachmentNode.put("filename", attachment.filename());
        attachmentNode.put("contentType", attachment.contentType());
        attachmentNode.put("sizeBytes", attachment.sizeBytes());
        attachmentNode.put("downloadUrl", downloadUrl);
        enriched.set("attachment", attachmentNode);
        enriched.put("fileUrl", downloadUrl);
        if (attachment.briefTitle() != null) {
            enriched.put("briefTitle", attachment.briefTitle());
        }
        return enriched;
    }

    /**
     * Best-effort byte-level truncation of the JSON to feed back to the LLM
     * within the configured cap (ADR-17 §4). The dispatcher already truncates;
     * this is a belt-and-suspenders guard for any future direct-call path.
     */
    private JsonNode truncateForLlm(JsonNode body) {
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(body);
            int cap = properties.toolMaxResultBytes();
            if (serialized.length <= cap) {
                return body;
            }
            int excerptCap = Math.max(0, cap - 64);
            byte[] excerptBytes = new byte[excerptCap];
            System.arraycopy(serialized, 0, excerptBytes, 0, excerptCap);
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("truncated", true);
            envelope.put("originalBytes", serialized.length);
            envelope.put("excerpt", new String(excerptBytes, StandardCharsets.UTF_8));
            return envelope;
        } catch (Exception e) {
            return body;
        }
    }
}
