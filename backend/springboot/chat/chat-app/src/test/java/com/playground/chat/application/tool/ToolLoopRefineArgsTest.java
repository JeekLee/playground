package com.playground.chat.application.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.chat.application.properties.ChatProperties;
import com.playground.chat.application.repository.AttachmentRepository;
import com.playground.chat.application.service.TurnCitationAccumulator;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.id.AttachmentId;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.shared.chat.ChatStreamEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

/**
 * Unit test for {@code ToolLoop.resolveRefineArgs} — the M9 resolve / validate /
 * transform of the {@code refine_massing} {@code baseAttachmentId}. The driving
 * invariant (multi-turn massing refine spec): the LLM picks an attachment id from
 * the {@code [YOUR MODELS]} manifest; chat resolves it to a storage key BEFORE
 * dispatch (so agent-tools can read the .3dm) while the storage key NEVER reaches
 * the LLM or the SSE stream — and a bad target is rejected with a {@code tool_error}
 * rather than dispatched.
 */
class ToolLoopRefineArgsTest {

    private AttachmentRepository attachmentRepository;
    private ObjectMapper objectMapper;
    private Sinks.Many<ChatStreamEvent> sink;
    private ToolLoop loop;

    private final UserId caller = UserId.of(UUID.randomUUID());
    private final MessageId assistantMessageId = MessageId.generate();
    private final SessionId sessionId = SessionId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        attachmentRepository = mock(AttachmentRepository.class);
        objectMapper = new ObjectMapper();
        sink = Sinks.many().multicast().onBackpressureBuffer();
        Clock clock = Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneOffset.UTC);
        loop = new ToolLoop(
                mock(ToolDispatcherPort.class),
                attachmentRepository,
                objectMapper,
                ChatProperties.defaults(),
                clock,
                sink,
                new AtomicInteger(0),
                new AtomicInteger(0),
                new ArrayList<>(),
                new TurnCitationAccumulator(),
                assistantMessageId,
                new UserContext(caller, "sub-1"),
                sessionId);
    }

    private Attachment modelAttachment(AttachmentId id, String storageKey) {
        return Attachment.toolArtifact(
                id, assistantMessageId, "massing-lib-20260609.3dm",
                "model/vnd.3dm", 1234L, storageKey,
                "generate_massing", "도서관 매싱", Instant.parse("2026-06-09T11:00:00Z"));
    }

    private Attachment nonModelAttachment(AttachmentId id) {
        // A document artifact (.pdf via search/upload) — must NOT be a refine target.
        return new Attachment(
                id, assistantMessageId, "tool-artifact", "brief.pdf",
                "application/pdf", 999L, "chat/x/brief.pdf",
                "search_documents", null, Instant.parse("2026-06-09T11:00:00Z"));
    }

    @Test
    void modelAttachment_resolvesBaseStorageKey_andStripsBaseAttachmentId() {
        UUID attId = UUID.randomUUID();
        AttachmentId id = AttachmentId.of(attId);
        when(attachmentRepository.findOwned(eqId(id), any()))
                .thenReturn(Optional.of(modelAttachment(id, "chat/s/m/massing.3dm")));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("baseAttachmentId", attId.toString());
        args.set("edits", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("op", "SetFloors").put("targetFloorsAbove", 5)));

        JsonNode out = loop.resolveRefineArgs("refine_massing", args, "call_1");

        assertThat(out).isNotNull();
        // baseAttachmentId stripped; baseStorageKey substituted with the resolved key.
        assertThat(out.has("baseAttachmentId")).isFalse();
        assertThat(out.get("baseStorageKey").asText()).isEqualTo("chat/s/m/massing.3dm");
        // The rest of the args (edits) are carried through untouched.
        assertThat(out.get("edits")).isEqualTo(args.get("edits"));
        // No tool_error emitted on the happy path.
        sink.tryEmitComplete();
        List<ChatStreamEvent> events = sink.asFlux().collectList().block();
        assertThat(events).noneMatch(e -> e instanceof ChatStreamEvent.ToolError);
    }

    @Test
    void nonModelAttachment_emitsToolError_andReturnsNull_soDispatchIsSkipped() {
        UUID attId = UUID.randomUUID();
        AttachmentId id = AttachmentId.of(attId);
        when(attachmentRepository.findOwned(eqId(id), any()))
                .thenReturn(Optional.of(nonModelAttachment(id)));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("baseAttachmentId", attId.toString());
        args.set("edits", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("op", "SetFloors").put("targetFloorsAbove", 5)));

        JsonNode out = loop.resolveRefineArgs("refine_massing", args, "call_2");

        // Null → caller skips dispatch entirely (args NOT forwarded).
        assertThat(out).isNull();
        sink.tryEmitComplete();
        List<ChatStreamEvent> events = sink.asFlux().collectList().block();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ChatStreamEvent.ToolError.class);
        ChatStreamEvent.ToolError err = (ChatStreamEvent.ToolError) events.get(0);
        assertThat(err.id()).isEqualTo("call_2");
        assertThat(err.name()).isEqualTo("refine_massing");
        assertThat(err.code()).isEqualTo("UPSTREAM_4XX");
    }

    @Test
    void notFoundId_emitsToolError_andReturnsNull() {
        UUID attId = UUID.randomUUID();
        when(attachmentRepository.findOwned(any(), any())).thenReturn(Optional.empty());

        ObjectNode args = objectMapper.createObjectNode();
        args.put("baseAttachmentId", attId.toString());
        args.set("edits", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("op", "SetFloors").put("targetFloorsAbove", 5)));

        JsonNode out = loop.resolveRefineArgs("refine_massing", args, "call_3");

        assertThat(out).isNull();
        sink.tryEmitComplete();
        List<ChatStreamEvent> events = sink.asFlux().collectList().block();
        assertThat(events).singleElement().isInstanceOf(ChatStreamEvent.ToolError.class);
    }

    @Test
    void nonUuidId_emitsToolError_andReturnsNull_withoutRepoLookup() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("baseAttachmentId", "not-a-uuid");
        args.set("edits", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("op", "SetFloors").put("targetFloorsAbove", 5)));

        JsonNode out = loop.resolveRefineArgs("refine_massing", args, "call_4");

        assertThat(out).isNull();
        sink.tryEmitComplete();
        List<ChatStreamEvent> events = sink.asFlux().collectList().block();
        assertThat(events).singleElement().isInstanceOf(ChatStreamEvent.ToolError.class);
    }

    @Test
    void nonRefineTool_passesArgsThroughUntouched() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "library brief");

        JsonNode out = loop.resolveRefineArgs("search_documents", args, "call_5");

        assertThat(out).isSameAs(args);
        sink.tryEmitComplete();
        List<ChatStreamEvent> events = sink.asFlux().collectList().block();
        assertThat(events).isEmpty();
    }

    /** Mockito matcher for an exact AttachmentId by value (records compare by value). */
    private static AttachmentId eqId(AttachmentId id) {
        return org.mockito.ArgumentMatchers.eq(id);
    }
}
