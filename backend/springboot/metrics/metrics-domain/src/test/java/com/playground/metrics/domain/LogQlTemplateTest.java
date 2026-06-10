package com.playground.metrics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LogQlTemplateTest {

    @Test
    void buildsLogQlForKnownService() {
        String logql = LogQlTemplate.forService("playground-backend-chat-api", null);
        assertThat(logql).isEqualTo("{container=\"playground-backend-chat-api\"} | json");
    }

    @Test
    void buildsLogQlWithSearchExpression() {
        String logql = LogQlTemplate.forService("playground-backend-chat-api", "ERROR");
        assertThat(logql).isEqualTo("{container=\"playground-backend-chat-api\"} |~ \"ERROR\" | json");
    }

    @Test
    void rejectsUnknownService() {
        assertThatThrownBy(() -> LogQlTemplate.forService("nope", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void escapesQuoteInSearchExpression() {
        String logql = LogQlTemplate.forService("playground-backend-chat-api", "say \"hi\"");
        assertThat(logql).contains("\\\"hi\\\"");
    }

    @Test
    void clampsLimitToMax() {
        assertThat(LogQlTemplate.clampLimit(1000)).isEqualTo(200);
        assertThat(LogQlTemplate.clampLimit(50)).isEqualTo(50);
        assertThat(LogQlTemplate.clampLimit(null)).isEqualTo(200);
        assertThat(LogQlTemplate.clampLimit(0)).isEqualTo(200);
        assertThat(LogQlTemplate.clampLimit(-5)).isEqualTo(200);
    }
}
