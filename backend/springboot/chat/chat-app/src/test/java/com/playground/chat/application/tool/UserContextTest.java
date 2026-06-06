package com.playground.chat.application.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playground.chat.domain.model.id.UserId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserContextTest {

    @Test
    void constructs_withUserIdAndSub() {
        UserId u = UserId.of(UUID.randomUUID());
        UserContext c = new UserContext(u, "sub-1");
        assertThat(c.userId()).isEqualTo(u);
        assertThat(c.userSub()).isEqualTo("sub-1");
    }

    @Test
    void constructs_withNullSub() {
        UserId u = UserId.of(UUID.randomUUID());
        UserContext c = new UserContext(u, null);
        assertThat(c.userSub()).isNull();
    }

    @Test
    void rejectsNullUserId() {
        assertThatThrownBy(() -> new UserContext(null, "sub-1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId");
    }
}
