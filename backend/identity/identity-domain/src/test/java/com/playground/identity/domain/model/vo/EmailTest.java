package com.playground.identity.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailTest {

    @Test
    void accepts_valid_address() {
        Email email = Email.of("jeeklee1120@gmail.com");
        assertThat(email.value()).isEqualTo("jeeklee1120@gmail.com");
    }

    @Test
    void rejects_blank() {
        assertThatThrownBy(() -> Email.of(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_missing_at_sign() {
        assertThatThrownBy(() -> Email.of("not-an-email"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> Email.of(null))
                .isInstanceOf(NullPointerException.class);
    }
}
