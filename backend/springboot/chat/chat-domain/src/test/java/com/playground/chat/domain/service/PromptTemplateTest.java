package com.playground.chat.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.chat.domain.enums.Role;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.UserDocumentRef;
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
    void assemble_baseShape_noRetrievedContextBlock() {
        // agentic-search spec D2: the RETRIEVED CONTEXT block is gone — document
        // content reaches the model only via search_documents tool results.
        String out = template.assemble(List.of(), "what's up?", List.of(), List.of());
        assertThat(out).contains("[SYSTEM]");
        assertThat(out).doesNotContain("[RETRIEVED CONTEXT]");
        assertThat(out).contains("[CURRENT TURN]\nuser: what's up?");
        assertThat(out).endsWith("assistant:\n");
    }

    @Test
    void assemble_withoutDocuments_rendersNoDocumentIndexSection() {
        String out = template.assemble(List.of(), "hi", List.of(), List.of());
        assertThat(out).doesNotContain("[YOUR DOCUMENTS]");
    }

    @Test
    void assemble_withDocuments_rendersOrderedManifestWithIds() {
        UUID firstId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID secondId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<UserDocumentRef> docs = List.of(
                new UserDocumentRef(1, firstId, "사업계획서"),
                new UserDocumentRef(2, secondId, "KFI 설계공모지침서"));
        String out = template.assemble(List.of(), "두 번째 문서로 매싱 만들어줘", docs, List.of());

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
        String out = template.assemble(hist, "follow-up", List.of(), List.of());
        assertThat(out).contains("user: earlier question");
        assertThat(out).contains("assistant: earlier answer with markers");
        // Scope the marker-free assertion to the conversation segment: the
        // SYSTEM_PROMPT itself now carries a literal "[1][2]" cite-individually
        // example, so a whole-prompt doesNotContain would false-positive.
        String conversation = out.substring(out.indexOf("[CONVERSATION SO FAR]"));
        assertThat(conversation).doesNotContain("[1][2]");
    }

    @Test
    void rendersModelsManifestWhenPresent() {
        var models = java.util.List.of(
                new com.playground.chat.domain.model.UserModelRef(
                        1, java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), "도서관"));
        String prompt = template.assemble(
                java.util.List.of(), "3층으로 줄여", java.util.List.of(), models);
        assertThat(prompt).contains("[YOUR MODELS]");
        assertThat(prompt).contains("도서관");
        assertThat(prompt).contains("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void omitsModelsManifestWhenEmpty() {
        String prompt = template.assemble(
                java.util.List.of(), "안녕", java.util.List.of(), java.util.List.of());
        assertThat(prompt).doesNotContain("[YOUR MODELS]");
    }
}
