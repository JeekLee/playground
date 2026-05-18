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

    // --- M2 S1 / ADR-12 amendment to ADR-09 ---

    @Test
    void get_api_docs_with_uuid_is_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.GET,
                "/api/docs/01234567-89ab-cdef-0123-456789abcdef"))).isTrue();
    }

    @Test
    void get_api_docs_base_path_is_not_public_in_s1() {
        // Spec §6.1 + §6.2: GET /api/docs?scope=mine is the only S1 use of the
        // bare path, and it requires authentication. The community feed (no
        // scope) lands in M2 S2. Either way the gateway treats the bare
        // /api/docs path as authenticated.
        assertThat(matcher.isPublic(exchange(HttpMethod.GET, "/api/docs"))).isFalse();
    }

    @Test
    void get_api_docs_trailing_slash_is_not_public() {
        // Mirrors the bare path — trailing slash variant must not slip into the
        // public allowlist.
        assertThat(matcher.isPublic(exchange(HttpMethod.GET, "/api/docs/"))).isFalse();
    }

    @Test
    void post_api_docs_uuid_publish_is_not_public() {
        // POST /api/docs/{id}/publish — spec §6.1 row, required + owner.
        assertThat(matcher.isPublic(exchange(HttpMethod.POST,
                "/api/docs/01234567-89ab-cdef-0123-456789abcdef/publish"))).isFalse();
    }

    @Test
    void post_api_docs_uuid_unpublish_is_not_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.POST,
                "/api/docs/01234567-89ab-cdef-0123-456789abcdef/unpublish"))).isFalse();
    }

    @Test
    void post_api_docs_is_not_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.POST,
                "/api/docs/01234567-89ab-cdef-0123-456789abcdef"))).isFalse();
    }

    @Test
    void post_api_docs_root_is_not_public() {
        // JSON + multipart create both go through this path; both require auth.
        assertThat(matcher.isPublic(exchange(HttpMethod.POST, "/api/docs"))).isFalse();
    }

    @Test
    void patch_api_docs_with_uuid_is_not_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.PATCH,
                "/api/docs/01234567-89ab-cdef-0123-456789abcdef"))).isFalse();
    }

    @Test
    void delete_api_docs_with_uuid_is_not_public() {
        assertThat(matcher.isPublic(exchange(HttpMethod.DELETE,
                "/api/docs/01234567-89ab-cdef-0123-456789abcdef"))).isFalse();
    }

    @Test
    void get_api_docs_garbage_tail_is_not_public() {
        // A path-segment that is not a UUID does not unlock the public allowance —
        // docs-api would 404 anyway, but the gateway should not waste cycles on it.
        assertThat(matcher.isPublic(exchange(HttpMethod.GET, "/api/docs/something"))).isFalse();
    }

    private ServerWebExchange exchange(HttpMethod method, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build());
    }
}
