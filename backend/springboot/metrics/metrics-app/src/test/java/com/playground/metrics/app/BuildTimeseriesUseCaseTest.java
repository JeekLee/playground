package com.playground.metrics.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.playground.metrics.app.dto.PrometheusSeries;
import com.playground.metrics.app.dto.TimeseriesResponse;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.domain.Range;
import com.playground.metrics.domain.Step;
import com.playground.metrics.domain.TimeseriesPoint;
import com.playground.shared.error.AbstractException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class BuildTimeseriesUseCaseTest {

    @Test
    void resolvesShapeForKnownMetric() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        PrometheusSeries series = new PrometheusSeries(
                Map.of("service", "rag-chat-api"),
                List.of(new TimeseriesPoint(1715763600, 380.0),
                        new TimeseriesPoint(1715763630, 392.0)));
        when(prometheus.rangeQuery(anyString(), any(Range.class), any(Step.class)))
                .thenReturn(Mono.just(List.of(series)));

        BuildTimeseriesUseCase useCase = new BuildTimeseriesUseCase(prometheus);

        TimeseriesResponse response = useCase.execute(
                "jvm-heap-rag-chat-api", Range.H_1, Step.parse("30s")).block();

        assertThat(response).isNotNull();
        assertThat(response.metric()).isEqualTo("jvm-heap-rag-chat-api");
        assertThat(response.range()).isEqualTo("1h");
        assertThat(response.step()).isEqualTo("30s");
        assertThat(response.unit()).isEqualTo("MB");
        assertThat(response.series()).hasSize(1);
        TimeseriesResponse.Series only = response.series().get(0);
        assertThat(only.label()).isEqualTo("rag-chat-api");
        assertThat(only.points()).hasSize(2);
        assertThat(only.points().get(0).ts()).isEqualTo(1715763600L);
        assertThat(only.points().get(0).value()).isEqualTo(380.0);
    }

    @Test
    void rejectsUnknownMetricWithBadRequest() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        BuildTimeseriesUseCase useCase = new BuildTimeseriesUseCase(prometheus);

        assertThatThrownBy(() -> useCase.execute(
                "foo-bar-not-allowlisted", Range.H_1, Step.parse("30s")))
                .isInstanceOf(AbstractException.class)
                .satisfies(ex -> assertThat(((AbstractException) ex).errorCode().code())
                        .isEqualTo("METRICS-VALIDATION-001"));
    }

    @Test
    void rejectsAllowlistBypassAttempt() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        BuildTimeseriesUseCase useCase = new BuildTimeseriesUseCase(prometheus);

        assertThatThrownBy(() -> useCase.execute(
                "jvm-heap-evil-svc", Range.H_1, Step.parse("30s")))
                .isInstanceOf(AbstractException.class);
    }
}
