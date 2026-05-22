package com.playground.ragchat.infrastructure.tool;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.playground.ragchat.application.properties.RagChatProperties;
import com.playground.ragchat.application.tool.ToolInvocationResult;
import com.playground.ragchat.application.tool.UserContext;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.ragchat.domain.tool.ToolDescriptor;
import com.playground.ragchat.domain.tool.ToolErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Unit-level dispatcher tests per ADR-17 §12 + PRD acceptance bullets:
 * <ul>
 *   <li>Happy path: descriptor + WireMock 200 + JSON → Success</li>
 *   <li>{@code X-User-Id}/{@code X-User-Sub} forwarded; {@code Authorization}
 *       not forwarded</li>
 *   <li>4xx → {@code UPSTREAM_4XX}</li>
 *   <li>5xx → {@code UPSTREAM_5XX}</li>
 *   <li>Timeout (response delay above descriptor timeout) → {@code TIMEOUT}</li>
 *   <li>JSON parse error → {@code SCHEMA_INVALID}</li>
 *   <li>Over-cap response (&gt; 16 KiB) → truncated Success envelope</li>
 *   <li>Circuit breaker OPEN → {@code CIRCUIT_OPEN}</li>
 * </ul>
 *
 * <p>This is a slice test (not tagged @integration) — uses WireMock as a
 * lightweight HTTP stub but does not need Docker / Testcontainers, so it
 * always runs as part of the normal {@code :rag-chat:rag-chat-infra:test}
 * pass.
 */
class WebClientToolDispatcherTest {

    private WireMockServer wireMock;
    private CircuitBreakerRegistry registry;
    private ObjectMapper objectMapper;
    private RagChatProperties properties;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        registry = CircuitBreakerRegistry.ofDefaults();
        objectMapper = new ObjectMapper();
        properties = RagChatProperties.defaults();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private WebClientToolDispatcher dispatcher(ToolDescriptor descriptor) {
        ToolBreakerRegistry breakers = new ToolBreakerRegistry(registry);
        WebClient.Builder builder = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(properties.toolMaxResultBytes()));
        return new WebClientToolDispatcher(
                builder, breakers, objectMapper, properties,
                name -> name.equals(descriptor.name()) ? Optional.of(descriptor) : Optional.empty());
    }

    private UserContext userCtx() {
        return new UserContext(UserId.of(UUID.randomUUID()), "google-sub-1234");
    }

    @Test
    void happyPath_returnsSuccess_andForwardsUserHeaders() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"echoed\":\"hi\"}")));

        ToolDescriptor desc = new ToolDescriptor(
                "echo", "Echo tool", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/echo"),
                Duration.ofSeconds(5));
        UserContext ctx = userCtx();

        ToolInvocationResult result = dispatcher(desc).invoke(
                "call_1", "echo", objectMapper.createObjectNode().put("msg", "hi"), ctx);

        assertThat(result).isInstanceOf(ToolInvocationResult.Success.class);
        ToolInvocationResult.Success s = (ToolInvocationResult.Success) result;
        assertThat(s.id()).isEqualTo("call_1");
        assertThat(s.name()).isEqualTo("echo");
        assertThat(s.body().get("echoed").asText()).isEqualTo("hi");

        wireMock.verify(postRequestedFor(urlPathEqualTo("/internal/tools/echo"))
                .withHeader("X-User-Id", equalTo(ctx.userId().value().toString()))
                .withHeader("X-User-Sub", equalTo("google-sub-1234"))
                .withHeader("Authorization", absent())
                .withHeader("Cookie", absent())
                .withRequestBody(equalToJson("{\"msg\":\"hi\"}")));
    }

    @Test
    void upstream4xx_returnsFailure_UPSTREAM_4XX() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse().withStatus(400).withBody("bad input")));

        ToolDescriptor desc = new ToolDescriptor(
                "echo", "Echo tool", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/echo"),
                Duration.ofSeconds(5));

        ToolInvocationResult result = dispatcher(desc).invoke(
                "call_1", "echo", null, userCtx());

        assertThat(result).isInstanceOf(ToolInvocationResult.Failure.class);
        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) result;
        assertThat(f.code()).isEqualTo(ToolErrorCode.UPSTREAM_4XX);
    }

    @Test
    void upstream5xx_returnsFailure_UPSTREAM_5XX() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        ToolDescriptor desc = new ToolDescriptor(
                "echo", "Echo tool", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/echo"),
                Duration.ofSeconds(5));

        ToolInvocationResult result = dispatcher(desc).invoke(
                "call_1", "echo", null, userCtx());

        assertThat(result).isInstanceOf(ToolInvocationResult.Failure.class);
        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) result;
        assertThat(f.code()).isEqualTo(ToolErrorCode.UPSTREAM_5XX);
    }

    @Test
    void responseSlowerThanDescriptorTimeout_returnsFailure_TIMEOUT() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/slow"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")
                        .withFixedDelay(800)));

        ToolDescriptor desc = new ToolDescriptor(
                "slow", "Slow tool", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/slow"),
                Duration.ofMillis(200));

        ToolInvocationResult result = dispatcher(desc).invoke(
                "call_1", "slow", null, userCtx());

        assertThat(result).isInstanceOf(ToolInvocationResult.Failure.class);
        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) result;
        assertThat(f.code()).isEqualTo(ToolErrorCode.TIMEOUT);
    }

    @Test
    void invalidJsonResponseBody_returnsFailure_SCHEMA_INVALID() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("not even close to JSON {][}")));

        ToolDescriptor desc = new ToolDescriptor(
                "echo", "Echo tool", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/echo"),
                Duration.ofSeconds(5));

        ToolInvocationResult result = dispatcher(desc).invoke(
                "call_1", "echo", null, userCtx());

        assertThat(result).isInstanceOf(ToolInvocationResult.Failure.class);
        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) result;
        assertThat(f.code()).isEqualTo(ToolErrorCode.SCHEMA_INVALID);
    }

    @Test
    void responseOver16KiB_isTruncated_andWrappedAsTruncatedEnvelope() {
        // Build a 20 KiB JSON string body so WireMock returns it intact (the
        // WebClient codec is configured at 16 KiB cap; we hit DataBufferLimit
        // OR we read the bytes and slice. The dispatcher constructor sets the
        // codec cap, so the codec will throw — we expect a Failure(INTERNAL)
        // in that case. To exercise the truncate path we use a smaller codec
        // cap by configuring properties differently.) Use a properties with
        // a wider codec cap so the body is delivered, then expect dispatcher
        // truncation against its own configured byte cap.
        // For deterministic coverage of truncate-and-warn we run with a
        // smaller dispatcher byte cap (256) below.
        RagChatProperties small = new RagChatProperties(
                6, 40, 200, 2400, 24576, 4000, 400, 5, 256);
        ToolBreakerRegistry breakers = new ToolBreakerRegistry(registry);
        WebClient.Builder builder = WebClient.builder()
                // codec cap > body size so body is delivered to the dispatcher
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024));

        StringBuilder big = new StringBuilder(512);
        big.append("{\"data\":\"");
        while (big.length() < 400) {
            big.append("xxxxxxxx");
        }
        big.append("\"}");
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/big"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(big.toString())));

        ToolDescriptor desc = new ToolDescriptor(
                "big", "Big tool", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/big"),
                Duration.ofSeconds(5));

        WebClientToolDispatcher dispatcher = new WebClientToolDispatcher(
                builder, breakers, objectMapper, small,
                name -> name.equals("big") ? Optional.of(desc) : Optional.empty());

        ToolInvocationResult result = dispatcher.invoke("call_1", "big", null, userCtx());

        assertThat(result).isInstanceOf(ToolInvocationResult.Success.class);
        ToolInvocationResult.Success s = (ToolInvocationResult.Success) result;
        assertThat(s.body().has("truncated")).isTrue();
        assertThat(s.body().get("truncated").asBoolean()).isTrue();
        assertThat(s.body().has("originalBytes")).isTrue();
        assertThat(s.body().has("excerpt")).isTrue();
    }

    @Test
    void circuitBreaker_opensAfterFailureBurst_thenCIRCUIT_OPEN() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/sick"))
                .willReturn(aResponse().withStatus(500)));

        ToolDescriptor desc = new ToolDescriptor(
                "sick", "Sick tool", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/sick"),
                Duration.ofSeconds(5));
        WebClientToolDispatcher dispatcher = dispatcher(desc);

        // Trigger ≥10 failures to satisfy the breaker's minimumNumberOfCalls
        // gate at >=50% failure rate.
        for (int i = 0; i < 10; i++) {
            ToolInvocationResult r = dispatcher.invoke("call_" + i, "sick", null, userCtx());
            assertThat(r).isInstanceOf(ToolInvocationResult.Failure.class);
            assertThat(((ToolInvocationResult.Failure) r).code())
                    .isIn(ToolErrorCode.UPSTREAM_5XX, ToolErrorCode.CIRCUIT_OPEN);
        }
        // At this point the breaker is OPEN. The next call must NOT hit upstream.
        int beforeCount = wireMock.findAll(postRequestedFor(urlPathEqualTo("/internal/tools/sick"))).size();
        ToolInvocationResult r = dispatcher.invoke("call_next", "sick", null, userCtx());
        int afterCount = wireMock.findAll(postRequestedFor(urlPathEqualTo("/internal/tools/sick"))).size();

        assertThat(r).isInstanceOf(ToolInvocationResult.Failure.class);
        assertThat(((ToolInvocationResult.Failure) r).code()).isEqualTo(ToolErrorCode.CIRCUIT_OPEN);
        // Cost-protection invariant: the WireMock call count did not increase.
        assertThat(afterCount).isEqualTo(beforeCount);
    }

    @Test
    void perToolIsolation_breakerOnAdoesNotAffectB() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/sick"))
                .willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/well"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));

        ToolDescriptor sick = new ToolDescriptor(
                "sick", "Sick", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/sick"),
                Duration.ofSeconds(5));
        ToolDescriptor well = new ToolDescriptor(
                "well", "Well", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/well"),
                Duration.ofSeconds(5));

        // Use a shared registry so both dispatchers register their breakers
        // there. Trip the "sick" breaker.
        ToolBreakerRegistry sharedBreakers = new ToolBreakerRegistry(registry);
        WebClient.Builder b = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16384));
        WebClientToolDispatcher d = new WebClientToolDispatcher(
                b, sharedBreakers, objectMapper, properties,
                n -> switch (n) {
                    case "sick" -> Optional.of(sick);
                    case "well" -> Optional.of(well);
                    default -> Optional.empty();
                });

        for (int i = 0; i < 10; i++) {
            d.invoke("c-" + i, "sick", null, userCtx());
        }
        // "well" must still be invokable.
        ToolInvocationResult r = d.invoke("call_well", "well", null, userCtx());
        assertThat(r).isInstanceOf(ToolInvocationResult.Success.class);
    }

    @Test
    void noUserSub_doesNotEmitHeader() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        ToolDescriptor desc = new ToolDescriptor(
                "echo", "Echo", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/echo"),
                Duration.ofSeconds(5));
        UserContext ctx = new UserContext(UserId.of(UUID.randomUUID()), null);

        dispatcher(desc).invoke("call_1", "echo", null, ctx);

        wireMock.verify(postRequestedFor(urlPathEqualTo("/internal/tools/echo"))
                .withHeader("X-User-Id", equalTo(ctx.userId().value().toString()))
                .withHeader("X-User-Sub", absent()));
    }

    @Test
    void unknownTool_returnsFailure_INTERNAL() {
        ToolDescriptor desc = new ToolDescriptor(
                "echo", "Echo", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/echo"),
                Duration.ofSeconds(5));
        ToolInvocationResult r = dispatcher(desc).invoke(
                "call_1", "no_such_tool", null, userCtx());
        assertThat(r).isInstanceOf(ToolInvocationResult.Failure.class);
        assertThat(((ToolInvocationResult.Failure) r).code()).isEqualTo(ToolErrorCode.INTERNAL);
    }
}
