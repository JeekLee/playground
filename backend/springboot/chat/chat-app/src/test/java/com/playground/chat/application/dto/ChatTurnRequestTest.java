package com.playground.chat.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.shared.error.AbstractException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Message-invariant tests for the self-validating {@link ChatTurnRequest}.
 * Moved out of {@code ChatTurnServiceTest} when the size/blank validation was
 * pushed down into the value object's compact constructor (issue #244 ③):
 * the throw now happens at construction (in the controller's handler), so the
 * reactive advice still maps it to HTTP — but the assertion belongs here, not
 * at the service boundary.
 */
class ChatTurnRequestTest {

    private final SessionId sessionId = SessionId.of(UUID.randomUUID());
    private final UserId caller = UserId.of(UUID.randomUUID());

    @Test
    void oversizeMessage_throwsMessageTooLarge() {
        assertThatThrownBy(() -> new ChatTurnRequest(sessionId, caller, "sub-1", "x".repeat(5000)))
                .isInstanceOf(AbstractException.class)
                .satisfies(t -> assertThat(((AbstractException) t).errorCode().code())
                        .isEqualTo("CHAT-VALIDATION-001"));
    }

    @Test
    void blankMessage_throwsMessageBlank() {
        assertThatThrownBy(() -> new ChatTurnRequest(sessionId, caller, "sub-1", "   "))
                .isInstanceOf(AbstractException.class)
                .satisfies(t -> assertThat(((AbstractException) t).errorCode().code())
                        .isEqualTo("CHAT-VALIDATION-003"));
    }

    @Test
    void emptyMessage_throwsMessageBlank() {
        assertThatThrownBy(() -> new ChatTurnRequest(sessionId, caller, "sub-1", ""))
                .isInstanceOf(AbstractException.class)
                .satisfies(t -> assertThat(((AbstractException) t).errorCode().code())
                        .isEqualTo("CHAT-VALIDATION-003"));
    }

    @Test
    void nullMessage_throwsNpe_notChatError() {
        // Null is a programmer/binding error, not user input — stays an NPE.
        assertThatThrownBy(() -> new ChatTurnRequest(sessionId, caller, "sub-1", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void messageAtByteCap_isAccepted() {
        // Exactly MAX_MESSAGE_BYTES (4096 ASCII bytes) is allowed; one more is not.
        assertThatCode(() -> new ChatTurnRequest(
                sessionId, caller, "sub-1", "x".repeat(ChatTurnRequest.MAX_MESSAGE_BYTES)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ChatTurnRequest(
                sessionId, caller, "sub-1", "x".repeat(ChatTurnRequest.MAX_MESSAGE_BYTES + 1)))
                .isInstanceOf(AbstractException.class)
                .satisfies(t -> assertThat(((AbstractException) t).errorCode().code())
                        .isEqualTo("CHAT-VALIDATION-001"));
    }

    @Test
    void normalMessage_isAccepted() {
        assertThatCode(() -> new ChatTurnRequest(sessionId, caller, "sub-1", "hi"))
                .doesNotThrowAnyException();
    }
}
