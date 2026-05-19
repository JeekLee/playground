package com.playground.metrics.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.playground.metrics.app.dto.LogsResponse;
import com.playground.metrics.app.port.LokiPort;
import com.playground.metrics.domain.LogEntry;
import com.playground.shared.error.AbstractException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class QueryLogsUseCaseTest {

    @Test
    void composesLogsResponseShape() {
        LokiPort loki = Mockito.mock(LokiPort.class);
        Instant now = Instant.parse("2026-05-19T07:41:58.234Z");
        List<LogEntry> entries = List.of(
                new LogEntry(now, "rag-chat-api", "INFO", "{\"level\":\"INFO\",\"msg\":\"hi\"}"),
                new LogEntry(now.minusSeconds(1), "rag-chat-api", "WARN", "{\"level\":\"WARN\"}"));
        when(loki.queryRange(anyString(), any(Duration.class), anyInt()))
                .thenReturn(Mono.just(entries));

        QueryLogsUseCase useCase = new QueryLogsUseCase(loki);
        LogsResponse response = useCase.execute("rag-chat-api", "15m", null, null).block();

        assertThat(response).isNotNull();
        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().get(0).service()).isEqualTo("rag-chat-api");
        assertThat(response.entries().get(0).level()).isEqualTo("INFO");
        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void rejectsUnknownServiceWithBadRequest() {
        LokiPort loki = Mockito.mock(LokiPort.class);
        QueryLogsUseCase useCase = new QueryLogsUseCase(loki);

        assertThatThrownBy(() -> useCase.execute("nope", "15m", null, null))
                .isInstanceOf(AbstractException.class)
                .satisfies(ex -> assertThat(((AbstractException) ex).errorCode().code())
                        .isEqualTo("METRICS-VALIDATION-002"));
    }

    @Test
    void rejectsInvalidSince() {
        LokiPort loki = Mockito.mock(LokiPort.class);
        QueryLogsUseCase useCase = new QueryLogsUseCase(loki);

        assertThatThrownBy(() -> useCase.execute("rag-chat-api", "99x", null, null))
                .isInstanceOf(AbstractException.class);
    }

    @Test
    void parseSinceDefaultsTo15m() {
        assertThat(QueryLogsUseCase.parseSince(null)).isEqualTo(Duration.ofMinutes(15));
        assertThat(QueryLogsUseCase.parseSince("")).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void parseSinceHandlesUnits() {
        assertThat(QueryLogsUseCase.parseSince("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(QueryLogsUseCase.parseSince("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(QueryLogsUseCase.parseSince("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(QueryLogsUseCase.parseSince("1d")).isEqualTo(Duration.ofDays(1));
    }
}
