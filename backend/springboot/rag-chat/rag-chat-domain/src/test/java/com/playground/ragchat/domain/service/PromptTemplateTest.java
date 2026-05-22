package com.playground.ragchat.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.ragchat.domain.enums.Role;
import com.playground.ragchat.domain.enums.Visibility;
import com.playground.ragchat.domain.model.Message;
import com.playground.ragchat.domain.model.RetrievedChunk;
import com.playground.ragchat.domain.model.id.DocumentId;
import com.playground.ragchat.domain.model.id.MessageId;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
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
