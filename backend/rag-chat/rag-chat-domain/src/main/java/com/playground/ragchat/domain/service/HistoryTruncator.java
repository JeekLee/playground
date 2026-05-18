package com.playground.ragchat.domain.service;

import com.playground.ragchat.domain.enums.Role;
import com.playground.ragchat.domain.model.Message;
import com.playground.ragchat.domain.model.vo.TokenCount;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Conversation truncation per ADR-14 §9 — drop oldest turn pairs (user +
 * assistant) until the history token count fits the remaining budget. Never
 * truncates mid-turn.
 *
 * <p>Algorithm: given chronologically-ordered messages (oldest first), compute
 * total token count of the {@code content} text. While total > budget and at
 * least one pair remains, drop the first message; if the new first is its
 * assistant counterpart (same session, role flip), drop that too. Re-count.
 *
 * <p>Edge case (empty history + current user message already exceeds the
 * budget): the caller is responsible for surfacing 413 PAYLOAD_TOO_LARGE
 * before this method is invoked. The 4-KB user-message cap (spec §5.1)
 * already prevents this in practice.
 */
@Service
public class HistoryTruncator {

    private final TokenCounter tokenCounter;

    public HistoryTruncator(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    /**
     * Truncate {@code messages} so its total token count fits in
     * {@code (maxHistoryTokens - currentUserMessageTokens.value())}.
     *
     * @return a new list (input is not mutated). May be empty if every turn
     *         pair had to be dropped.
     */
    public List<Message> truncate(List<Message> messages, int maxHistoryTokens, TokenCount currentUserMessageTokens) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int budget = maxHistoryTokens - currentUserMessageTokens.value();
        if (budget <= 0) {
            // Whole budget consumed by the current message; no history fits.
            return List.of();
        }

        List<Message> remaining = new ArrayList<>(messages);
        int total = sumTokens(remaining);
        while (total > budget && !remaining.isEmpty()) {
            // Drop the leading user (or assistant — handle either) and its pair.
            Message dropped = remaining.remove(0);
            // If the next remaining is the assistant counterpart for that user
            // turn, drop it together. "Counterpart" = next message right after
            // the dropped one; pair = (user, assistant) by adjacency in the
            // ordered list.
            if (!remaining.isEmpty()
                    && dropped.role() == Role.USER
                    && remaining.get(0).role() == Role.ASSISTANT) {
                remaining.remove(0);
            }
            total = sumTokens(remaining);
        }
        return remaining;
    }

    private int sumTokens(List<Message> messages) {
        int sum = 0;
        for (Message m : messages) {
            sum += tokenCounter.count(m.content()).value();
        }
        return sum;
    }
}
