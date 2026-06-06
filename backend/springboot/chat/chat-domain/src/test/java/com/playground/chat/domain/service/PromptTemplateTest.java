package com.playground.chat.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.chat.domain.enums.Role;
import com.playground.chat.domain.enums.Visibility;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.RetrievedChunk;
import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.id.DocumentId;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PromptTemplateTest {

    private final TokenCounter tokenCounter = new TokenCounter();
    private final CitationExtractor citationExtractor = new CitationExtractor();
    private final PromptTemplate template = new PromptTemplate(tokenCounter, citationExtractor);

    @Test
    void assemble_emptyRetrievalRendersFallback() {
        String out = template.assemble(List.of(), List.of(), "what's up?", 400);
        assertThat(out).contains("[SYSTEM]");
        assertThat(out).contains("[RETRIEVED CONTEXT]\n(no chunks retrieved");
        assertThat(out).contains("[CURRENT TURN]\nuser: what's up?");
        assertThat(out).endsWith("assistant:\n");
    }

    @Test
    void assemble_includesEachChunkWithPositionTitleAndVisibility() {
        List<RetrievedChunk> chunks = List.of(
                new RetrievedChunk(1, DocumentId.of(UUID.randomUUID()), 3, "first text",
                        "Doc A", UserId.of(UUID.randomUUID()), Visibility.PUBLIC),
                new RetrievedChunk(2, DocumentId.of(UUID.randomUUID()), 7, "second text",
                        "Doc B", UserId.of(UUID.randomUUID()), Visibility.PRIVATE));
        String out = template.assemble(chunks, List.of(), "hi", 400);
        assertThat(out).contains("[1] Doc A (visibility=public) — first text");
        assertThat(out).contains("[2] Doc B (visibility=private) — second text");
    }

    @Test
    void assemble_withoutDocuments_rendersNoDocumentIndexSection() {
        // The 4-arg overload (and a null/empty manifest) must keep the M4
        // prompt byte-shape — no [YOUR DOCUMENTS] section at all.
        String out = template.assemble(List.of(), List.of(), "hi", 400);
        assertThat(out).doesNotContain("[YOUR DOCUMENTS]");
        String out2 = template.assemble(List.of(), List.of(), "hi", 400, List.of());
        assertThat(out2).doesNotContain("[YOUR DOCUMENTS]");
        assertThat(out2).isEqualTo(out);
    }

    @Test
    void assemble_withDocuments_rendersOrderedManifestWithIds() {
        UUID firstId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID secondId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<UserDocumentRef> docs = List.of(
                new UserDocumentRef(1, firstId, "사업계획서", "application/pdf", "extracted"),
                new UserDocumentRef(2, secondId, "KFI 설계공모지침서", "application/pdf", "extracted"));
        String out = template.assemble(List.of(), List.of(), "두 번째 문서로 매싱 만들어줘", 400, docs);

        assertThat(out).contains("[YOUR DOCUMENTS]");
        // Ordinal + title + raw UUID must be present so the model can map an
        // ordinal/name reference to the exact briefDocId.
        assertThat(out).contains("1.");
        assertThat(out).contains("사업계획서");
        assertThat(out).contains(firstId.toString());
        assertThat(out).contains("2.");
        assertThat(out).contains("KFI 설계공모지침서");
        assertThat(out).contains(secondId.toString());
        // Section must precede the current turn so it is in-context for the tool call.
        assertThat(out.indexOf("[YOUR DOCUMENTS]")).isLessThan(out.indexOf("[CURRENT TURN]"));
    }

    @Test
    void assemble_stripsMarkersFromPriorAssistantTurns() {
        SessionId sid = SessionId.of(UUID.randomUUID());
        UserId uid = UserId.of(UUID.randomUUID());
        List<Message> hist = List.of(
                new Message(MessageId.of(UUID.randomUUID()), sid, uid, Role.USER, "earlier question",
                        null, null, null, Instant.now()),
                new Message(MessageId.of(UUID.randomUUID()), sid, uid, Role.ASSISTANT,
                        "earlier answer with [1][2] markers", 100, 100, 6, Instant.now()));
        String out = template.assemble(List.of(), hist, "follow-up", 400);
        assertThat(out).contains("user: earlier question");
        assertThat(out).contains("assistant: earlier answer with markers");
        assertThat(out).doesNotContain("[1][2]");
    }
}
