package com.playground.chat.infrastructure.persistence;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.id.UserId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Slice test for the docs-api manifest WebClient adapter (SP3a spec D2). Uses
 * WireMock as a lightweight HTTP stub for {@code GET /internal/docs/manifest} —
 * no Docker / Testcontainers, so it runs in the normal
 * {@code :chat:chat-infra:test} pass. Mirrors {@code WebClientToolDispatcherTest}'s
 * harness (dynamicPort, @BeforeEach start, @AfterEach stop).
 */
class WebClientUserDocumentManifestAdapterTest {

    private WireMockServer wm;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        WireMock.configureFor("localhost", wm.port());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private WebClientUserDocumentManifestAdapter adapter() {
        return new WebClientUserDocumentManifestAdapter(
                WebClient.builder(), wm.baseUrl() + "/internal/docs/manifest");
    }

    @Test
    void mapsDocumentsToRefsInOrderWithOrdinals() {
        UUID u = UUID.randomUUID();
        UUID d1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID d2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        wm.stubFor(get(urlPathEqualTo("/internal/docs/manifest"))
                .withQueryParam("userId", equalTo(u.toString()))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"documents\":[{\"id\":\"" + d1 + "\",\"title\":\"A\"},"
                                + "{\"id\":\"" + d2 + "\",\"title\":\"B\"}]}")));

        List<UserDocumentRef> refs = adapter().recentForUser(UserId.of(u), 30);

        assertThat(refs).hasSize(2);
        assertThat(refs.get(0)).isEqualTo(new UserDocumentRef(1, d1, "A"));
        assertThat(refs.get(1)).isEqualTo(new UserDocumentRef(2, d2, "B"));
    }

    @Test
    void non2xxReturnsEmpty() {
        UUID u = UUID.randomUUID();
        wm.stubFor(get(urlPathEqualTo("/internal/docs/manifest"))
                .willReturn(aResponse().withStatus(500)));

        assertThat(adapter().recentForUser(UserId.of(u), 30)).isEmpty();
    }

    @Test
    void emptyDocumentsReturnsEmpty() {
        UUID u = UUID.randomUUID();
        wm.stubFor(get(urlPathEqualTo("/internal/docs/manifest"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"documents\":[]}")));

        assertThat(adapter().recentForUser(UserId.of(u), 30)).isEmpty();
    }

    @Test
    void limitZeroReturnsEmptyWithoutCall() {
        UUID u = UUID.randomUUID();

        assertThat(adapter().recentForUser(UserId.of(u), 0)).isEmpty();

        wm.verify(0, getRequestedFor(urlPathEqualTo("/internal/docs/manifest")));
    }
}
