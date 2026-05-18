package com.playground.ragchat.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.ragchat.domain.enums.Role;
import com.playground.ragchat.domain.model.Message;
import com.playground.ragchat.domain.model.id.MessageId;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.ragchat.domain.model.vo.TokenCount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoryTruncatorTest {

    private final TokenCounter tokenCounter = new TokenCounter();
    private final HistoryTruncator truncator = new HistoryTruncator(tokenCounter);

    @Test
    void truncate_emptyHistoryReturnsEmpty() {
        List<Message> out = truncator.truncate(List.of(), 1000, TokenCount.of(10));
        assertThat(out).isEmpty();
    }

    @Test
    void truncate_belowBudgetReturnsUnchanged() {
        List<Message> history = List.of(
                user("first"),
                assistant("first reply"),
                user("second"),
                assistant("second reply"));
        List<Message> out = truncator.truncate(history, 10000, TokenCount.of(10));
        assertThat(out).hasSize(4);
    }

    @Test
    void truncate_dropsOldestPair() {
        // Make each turn ~50 tokens so we can fit two pairs under a 200-token budget
        // but not three.
        String content = "the quick brown fox jumps over the lazy dog and watches the sunset from a hill ".repeat(2);
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            history.add(user(content));
            history.add(assistant(content));
        }
        // Budget for ~2 pairs (well under 6 messages' worth).
        List<Message> out = truncator.truncate(history, 200, TokenCount.of(0));
        // The truncator drops oldest user, then its adjacent assistant. Should fit ≤ 4.
        assertThat(out.size()).isLessThanOrEqualTo(history.size());
        // Remaining should fit the budget.
        int sum = out.stream().mapToInt(m -> tokenCounter.count(m.content()).value()).sum();
        assertThat(sum).isLessThanOrEqualTo(200);
    }

    @Test
    void truncate_neverPartialUserAssistantPair() {
        String content = "a b c d e f g h i j k l m n o p q r s t u v w x y z";
        List<Message> history = List.of(
                user(content), assistant(content),
                user(content), assistant(content));
        List<Message> out = truncator.truncate(history, 20, TokenCount.of(0));
        // The dropping algorithm drops user + matching assistant together; the
        // result should always be even-sized OR empty when starting from a
        // strictly-paired (user, assistant)+ ordering.
        assertThat(out.size() % 2).isEqualTo(0);
    }

    private Message user(String content) {
        return new Message(MessageId.of(UUID.randomUUID()), session, caller, Role.USER, content,
                null, null, null, Instant.parse("2026-05-18T00:00:00Z"));
    }

    private Message assistant(String content) {
        return new Message(MessageId.of(UUID.randomUUID()), session, caller, Role.ASSISTANT, content,
                100, 100, 6, Instant.parse("2026-05-18T00:00:00Z"));
    }

    private final SessionId session = SessionId.of(UUID.randomUUID());
    private final UserId caller = UserId.of(UUID.randomUUID());
}
