package com.playground.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

class PublicRouteMatcherTest {

    private final PublicRouteMatcher matcher = new PublicRouteMatcher();

    @Test
    void public_home_is_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.GET, "/"))).isTrue();
    }

    @Test
    void public_docs_index_is_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.GET, "/docs/public/intro"))).isTrue();
    }

    @Test
    void public_api_metrics_is_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.GET, "/api/metrics/health"))).isTrue();
    }

    @Test
    void private_api_identity_is_not_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.GET, "/api/identity/me"))).isFalse();
    }

    @Test
    void me_route_is_not_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.GET, "/me"))).isFalse();
    }

    @Test
    void post_to_public_chat_is_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.POST, "/api/rag/chat/public"))).isTrue();
    }

    @Test
    void post_to_private_chat_is_not_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.POST, "/api/rag/chat/private"))).isFalse();
    }

    @Test
    void favicon_is_public_for_any_method() {
        assertThat(matcher.isPublic(exchange(HttpMethod.GET, "/favicon.ico"))).isTrue();
    }

    private ServerWebExchange exchange(HttpMethod method, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build());
    }
}
