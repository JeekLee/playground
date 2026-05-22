package com.playground.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class StripUserHeadersFilterTest {

    @Mock GatewayFilterChain chain;

    @Test
    void removes_client_supplied_x_user_headers() {
        StripUserHeadersFilter filter = new StripUserHeadersFilter();

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")
                .header("X-User-Id", "attacker")
                .header("X-User-Email", "evil@example.com")
                .header("X-User-Sub", "9999")
                .build());

        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain, times(1)).filter(captor.capture());

        var headers = captor.getValue().getRequest().getHeaders();
        assertThat(headers.get("X-User-Id")).isNull();
        assertThat(headers.get("X-User-Email")).isNull();
        assertThat(headers.get("X-User-Sub")).isNull();
    }

    @Test
    void runs_at_highest_precedence() {
        assertThat(new StripUserHeadersFilter().getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
