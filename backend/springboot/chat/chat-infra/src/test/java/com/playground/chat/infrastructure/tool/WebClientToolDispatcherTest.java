package com.playground.chat.infrastructure.tool;

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
import com.playground.chat.application.properties.ChatProperties;
import com.playground.chat.application.tool.ToolArtifact;
import com.playground.chat.application.tool.ToolDispatcherPort;
import com.playground.chat.application.tool.ToolInvocationResult;
import com.playground.chat.application.tool.UserContext;
import com.playground.chat.domain.model.id.UserId;
import com.playground.chat.domain.tool.ToolDescriptor;
import com.playground.chat.domain.tool.ToolErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Unit-level dispatcher tests per ADR-17 §12 + PRD acceptance bullets,
 * updated for the NDJSON streaming contract (tool-streaming spec D2/D4):
 * <ul>
 *   <li>progress lines → {@code ToolProgress} listener callbacks; terminal
 *       {@code result} → Success</li>
 *   <li>terminal {@code error} line → {@code UPSTREAM_4XX} / {@code UPSTREAM_5XX}
 *       with a {@code "<CODE>: <message>"} body</li>
 *   <li>idle gap above the descriptor's idle timeout → {@code TIMEOUT}</li>
 *   <li>stream that ends with no terminal event → {@code INTERNAL}</li>
 *   <li>{@code X-User-Id}/{@code X-User-Sub} forwarded; {@code Authorization}
 *       not forwarded</li>
 *   <li>Over-cap result → truncated Success envelope</li>
 *   <li>Circuit breaker OPEN → {@code CIRCUIT_OPEN}</li>
 * </ul>
 *
 * <p>This is a slice test (not tagged @integration) — uses WireMock as a
 * lightweight HTTP stub but does not need Docker / Testcontainers, so it
 * always runs as part of the normal {@code :chat:chat-infra:test}
 * pass.
 */
class WebClientToolDispatcherTest {

    private WireMockServer wireMock;
    private CircuitBreakerRegistry registry;
    private ObjectMapper objectMapper;
    private ChatProperties properties;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        registry = CircuitBreakerRegistry.ofDefaults();
        objectMapper = new ObjectMapper();
        properties = ChatProperties.defaults();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    /** NDJSON body: one JSON object per line, trailing newline. */
    private static String ndjson(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private WebClientToolDispatcher dispatcher(ToolDescriptor descriptor) {
        ToolBreakerRegistry breakers = new ToolBreakerRegistry(registry);
        WebClient.Builder builder = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(properties.toolMaxResultBytes()));
        return new WebClientToolDispatcher(
                builder, breakers, objectMapper, properties,
                name -> name.equals(descriptor.name()) ? Optional.of(descriptor) : Optional.empty());
    }

    /** Descriptor pointing at the WireMock stub path with the given idle timeout. */
    private ToolDescriptor desc(String name, String path, Duration idle) {
        return new ToolDescriptor(
                name, name + " display", name + " description", null,
                URI.create("http://localhost:" + wireMock.port() + path),
                idle, Duration.ofSeconds(30));
    }

    /**
     * Build a dispatcher whose sole descriptor points at the
     * {@code generate-massing} stub with custom idle + total timeouts, then
     * invoke it. Mirrors the {@link #dispatcher(ToolDescriptor)} injection
     * pattern but lets a test drive the two streaming time bounds directly.
     */
    private ToolInvocationResult invokeWithTimeouts(Duration idle, Duration total) {
        ToolDescriptor d = new ToolDescriptor(
                "generate_massing", "매싱 모델", "gen description", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/generate-massing"),
                idle, total);
        return dispatcher(d).invoke("t1", "generate_massing", args(), userCtx(), p -> { });
    }

    private com.fasterxml.jackson.databind.JsonNode args() {
        return objectMapper.createObjectNode().put("msg", "hi");
    }

    private UserContext userCtx() {
        return new UserContext(UserId.of(UUID.randomUUID()), "google-sub-1234");
    }

    @Test
    void streamsProgressThenResult() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/generate-massing"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson(
                                "{\"event\":\"progress\",\"stage\":\"extract\",\"label\":\"공간 프로그램 추출 중\",\"stageIndex\":3,\"stageCount\":10}",
                                "{\"event\":\"progress\",\"stage\":\"compute\",\"label\":\"매싱 계산\",\"stageIndex\":7,\"stageCount\":10,\"attempt\":2}",
                                "{\"event\":\"result\",\"result\":{\"summary\":\"2실\"},\"artifact\":{\"storageKey\":\"k.3dm\",\"filename\":\"k.3dm\",\"contentType\":\"application/octet-stream\",\"sizeBytes\":5}}"))));

        List<ToolDispatcherPort.ToolProgress> seen = new ArrayList<>();
        ToolInvocationResult r = dispatcher(
                desc("generate_massing", "/internal/tools/generate-massing", Duration.ofSeconds(5)))
                .invoke("t1", "generate_massing", args(), userCtx(), seen::add);

        assertThat(r).isInstanceOf(ToolInvocationResult.Success.class);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).stage()).isEqualTo("extract");
        assertThat(seen.get(0).attempt()).isNull();
        assertThat(seen.get(1).attempt()).isEqualTo(2);
    }

    @Test
    void errorEventMapsToFailureWithCodePrefix() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/generate-massing"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson(
                                "{\"event\":\"error\",\"code\":\"BRIEF_NOT_READY\",\"message\":\"site area missing\",\"status\":422}"))));

        ToolInvocationResult r = dispatcher(
                desc("generate_massing", "/internal/tools/generate-massing", Duration.ofSeconds(5)))
                .invoke("t1", "generate_massing", args(), userCtx(), p -> { });

        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) r;
        assertThat(f.code()).isEqualTo(ToolErrorCode.UPSTREAM_4XX);
        assertThat(f.message()).isEqualTo("BRIEF_NOT_READY: site area missing");
    }

    @Test
    void errorEventStatus5xxMapsToUpstream5xx() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/generate-massing"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson(
                                "{\"event\":\"error\",\"code\":\"SIDECAR_FAILED\",\"message\":\"llm down\",\"status\":502}"))));

        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) dispatcher(
                desc("generate_massing", "/internal/tools/generate-massing", Duration.ofSeconds(5)))
                .invoke("t1", "generate_massing", args(), userCtx(), p -> { });
        assertThat(f.code()).isEqualTo(ToolErrorCode.UPSTREAM_5XX);
    }

    @Test
    void idleTimeoutTripsWhenNoEvents() {
        // idle 짧은 디스크립터로 디스패처 호출 — 바디를 idle보다 길게 지연.
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/generate-massing"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson("{\"event\":\"result\",\"result\":{},\"artifact\":{\"storageKey\":\"k.3dm\",\"filename\":\"k\",\"contentType\":\"x\",\"sizeBytes\":1}}"))
                        .withFixedDelay(800)));   // idle 300ms 초과

        ToolInvocationResult r = invokeWithTimeouts(Duration.ofMillis(300), Duration.ofSeconds(5));
        assertThat(((ToolInvocationResult.Failure) r).code()).isEqualTo(ToolErrorCode.TIMEOUT);
    }

    @Test
    void heartbeatResetsIdleTimer() throws Exception {
        // 핵심: heartbeat가 Reactor Flux.timeout(idle)을 리셋해 idle(800ms)보다
        // 긴 총 스트림(~2.4s)이 TIMEOUT 없이 result에 도달하는지 검증.
        //
        // WireMock(withChunkedDribbleDelay)은 청크를 종료 시점에 합쳐 flush하므로
        // 증분 디코딩이 일어나지 않아(전 줄이 한꺼번에 방출) 이 테스트엔 부적합.
        // 대신 줄마다 즉시 flush하는 최소 chunked HTTP 서버를 띄워 진짜 증분
        // 스트리밍을 만든다. 줄 간 간격 ~800ms < idle 800ms 경계가 빠듯하면
        // 플레이키 — 간격(LINE_GAP)·idle을 비율 유지한 채 키울 것.
        final long lineGapMillis = 600;   // 줄 간 간격
        final Duration idle = Duration.ofMillis(1500); // > lineGap, 헤드룸 포함
        try (StreamingStub stub = StreamingStub.start(lineGapMillis,
                "{\"event\":\"heartbeat\"}",
                "{\"event\":\"heartbeat\"}",
                "{\"event\":\"result\",\"result\":{\"summary\":\"ok\"},\"artifact\":null}")) {

            ToolDescriptor d = new ToolDescriptor(
                    "generate_massing", "매싱 모델", "gen description", null,
                    URI.create("http://localhost:" + stub.port() + "/internal/tools/generate-massing"),
                    idle, Duration.ofSeconds(10));

            ToolInvocationResult r = dispatcher(d)
                    .invoke("t1", "generate_massing", args(), userCtx(), p -> { });
            assertThat(r).isInstanceOf(ToolInvocationResult.Success.class);
        }
    }

    /**
     * Minimal HTTP/1.1 chunked stub that flushes each NDJSON line immediately
     * with a fixed gap — unlike WireMock {@code withChunkedDribbleDelay}, which
     * coalesces and flushes at stream end (defeating incremental decoding).
     * Single request, then closes.
     */
    private static final class StreamingStub implements AutoCloseable {
        private final java.net.ServerSocket server;
        private final Thread thread;

        private StreamingStub(java.net.ServerSocket server, Thread thread) {
            this.server = server;
            this.thread = thread;
        }

        static StreamingStub start(long lineGapMillis, String... lines) throws Exception {
            java.net.ServerSocket server = new java.net.ServerSocket(0);
            Thread t = new Thread(() -> {
                try (java.net.Socket sock = server.accept()) {
                    sock.getInputStream().read(new byte[8192]); // drain request line/headers
                    java.io.OutputStream out = sock.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/x-ndjson\r\n"
                            + "Transfer-Encoding: chunked\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    for (String line : lines) {
                        Thread.sleep(lineGapMillis);
                        byte[] body = (line + "\n").getBytes(StandardCharsets.UTF_8);
                        out.write((Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(body);
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                    // server closed / client disconnected — test owns lifecycle
                }
            }, "streaming-stub");
            t.setDaemon(true);
            t.start();
            return new StreamingStub(server, t);
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.interrupt();
        }
    }

    @Test
    void streamWithoutTerminalEventIsInternal() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/generate-massing"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson(
                                "{\"event\":\"progress\",\"stage\":\"extract\",\"label\":\"l\",\"stageIndex\":3,\"stageCount\":10}",
                                "{\"event\":\"heartbeat\"}"))));

        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) dispatcher(
                desc("generate_massing", "/internal/tools/generate-massing", Duration.ofSeconds(5)))
                .invoke("t1", "generate_massing", args(), userCtx(), p -> { });
        assertThat(f.code()).isEqualTo(ToolErrorCode.INTERNAL);
    }

    @Test
    void happyPath_returnsSuccess_andForwardsUserHeaders() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson("{\"event\":\"result\",\"result\":{\"echoed\":\"hi\"}}"))));

        ToolDescriptor d = desc("echo", "/internal/tools/echo", Duration.ofSeconds(5));
        UserContext ctx = userCtx();

        ToolInvocationResult result = dispatcher(d).invoke(
                "call_1", "echo", objectMapper.createObjectNode().put("msg", "hi"), ctx, p -> { });

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
    void invalidJsonResponseBody_returnsFailure_INTERNAL() {
        // A non-NDJSON / unparseable body produces no JSON nodes, so the stream
        // surfaces a transport/decoding error → INTERNAL (no terminal node).
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody("not even close to JSON {][}\n")));

        ToolInvocationResult result = dispatcher(
                desc("echo", "/internal/tools/echo", Duration.ofSeconds(5)))
                .invoke("call_1", "echo", null, userCtx(), p -> { });

        assertThat(result).isInstanceOf(ToolInvocationResult.Failure.class);
        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) result;
        assertThat(f.code()).isEqualTo(ToolErrorCode.INTERNAL);
    }

    @Test
    void responseOver16KiB_isTruncated_andWrappedAsTruncatedEnvelope() {
        // The LLM-visible `result` node exceeds the dispatcher's byte cap (256)
        // → truncate-and-warn envelope. Run with a small dispatcher byte cap so
        // a modest body trips it deterministically.
        ChatProperties small = new ChatProperties(
                200, 24576, 4000, 5, 256);
        ToolBreakerRegistry breakers = new ToolBreakerRegistry(registry);
        WebClient.Builder builder = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024));

        StringBuilder big = new StringBuilder(512);
        big.append("{\"event\":\"result\",\"result\":{\"data\":\"");
        while (big.length() < 400) {
            big.append("xxxxxxxx");
        }
        big.append("\"},\"artifact\":{\"storageKey\":\"k\",\"filename\":\"k\",\"contentType\":\"x\",\"sizeBytes\":1}}");
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/big"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(big + "\n")));

        ToolDescriptor d = new ToolDescriptor(
                "big", "big display", "big description", null,
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/big"),
                Duration.ofSeconds(5), Duration.ofSeconds(30));

        WebClientToolDispatcher dispatcher = new WebClientToolDispatcher(
                builder, breakers, objectMapper, small,
                name -> name.equals("big") ? Optional.of(d) : Optional.empty());

        ToolInvocationResult result = dispatcher.invoke("call_1", "big", null, userCtx(), p -> { });

        assertThat(result).isInstanceOf(ToolInvocationResult.Success.class);
        ToolInvocationResult.Success s = (ToolInvocationResult.Success) result;
        assertThat(s.body().has("truncated")).isTrue();
        assertThat(s.body().get("truncated").asBoolean()).isTrue();
        assertThat(s.body().has("originalBytes")).isTrue();
        assertThat(s.body().has("excerpt")).isTrue();
    }

    @Test
    void circuitBreaker_opensAfterFailureBurst_thenCIRCUIT_OPEN() {
        // Transport-level failure (HTTP 500) still trips the breaker — non-2xx
        // surfaces as WebClientResponseException through the operator.
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/sick"))
                .willReturn(aResponse().withStatus(500)));

        ToolDescriptor d = desc("sick", "/internal/tools/sick", Duration.ofSeconds(5));
        WebClientToolDispatcher dispatcher = dispatcher(d);

        for (int i = 0; i < 10; i++) {
            ToolInvocationResult r = dispatcher.invoke("call_" + i, "sick", null, userCtx(), p -> { });
            assertThat(r).isInstanceOf(ToolInvocationResult.Failure.class);
            assertThat(((ToolInvocationResult.Failure) r).code())
                    .isIn(ToolErrorCode.UPSTREAM_5XX, ToolErrorCode.CIRCUIT_OPEN);
        }
        int beforeCount = wireMock.findAll(postRequestedFor(urlPathEqualTo("/internal/tools/sick"))).size();
        ToolInvocationResult r = dispatcher.invoke("call_next", "sick", null, userCtx(), p -> { });
        int afterCount = wireMock.findAll(postRequestedFor(urlPathEqualTo("/internal/tools/sick"))).size();

        assertThat(r).isInstanceOf(ToolInvocationResult.Failure.class);
        assertThat(((ToolInvocationResult.Failure) r).code()).isEqualTo(ToolErrorCode.CIRCUIT_OPEN);
        assertThat(afterCount).isEqualTo(beforeCount);
    }

    @Test
    void perToolIsolation_breakerOnAdoesNotAffectB() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/sick"))
                .willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/well"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson("{\"event\":\"result\",\"result\":{\"ok\":true}}"))));

        ToolDescriptor sick = desc("sick", "/internal/tools/sick", Duration.ofSeconds(5));
        ToolDescriptor well = desc("well", "/internal/tools/well", Duration.ofSeconds(5));

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
            d.invoke("c-" + i, "sick", null, userCtx(), p -> { });
        }
        ToolInvocationResult r = d.invoke("call_well", "well", null, userCtx(), p -> { });
        assertThat(r).isInstanceOf(ToolInvocationResult.Success.class);
    }

    @Test
    void noUserSub_doesNotEmitHeader() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson("{\"event\":\"result\",\"result\":{}}"))));

        ToolDescriptor d = desc("echo", "/internal/tools/echo", Duration.ofSeconds(5));
        UserContext ctx = new UserContext(UserId.of(UUID.randomUUID()), null);

        dispatcher(d).invoke("call_1", "echo", null, ctx, p -> { });

        wireMock.verify(postRequestedFor(urlPathEqualTo("/internal/tools/echo"))
                .withHeader("X-User-Id", equalTo(ctx.userId().value().toString()))
                .withHeader("X-User-Sub", absent()));
    }

    @Test
    void artifactEnvelope_splitsResultFromArtifact_resultIsLlmVisibleOnly() {
        // ADR-20 §D3 revised — {result, artifact} terminal: body() carries ONLY
        // result (LLM-visible); artifact() carries metadata (storageKey, sizeBytes).
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/gen"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson("{\"event\":\"result\",\"result\":{\"summary\":\"s\",\"floorCount\":4},"
                                + "\"artifact\":{\"filename\":\"massing-한글-1.3dm\","
                                + "\"contentType\":\"application/octet-stream\","
                                + "\"sizeBytes\":28,"
                                + "\"storageKey\":\"architecture/massing/20260604/abc-uuid/massing-한글-1.3dm\"}}"))));

        ToolDescriptor d = desc("gen", "/internal/tools/gen", Duration.ofSeconds(5));

        ToolInvocationResult result = dispatcher(d).invoke("call_1", "gen", null, userCtx(), p -> { });

        assertThat(result).isInstanceOf(ToolInvocationResult.Success.class);
        ToolInvocationResult.Success s = (ToolInvocationResult.Success) result;
        assertThat(s.body().has("storageKey")).isFalse();
        assertThat(s.body().has("artifact")).isFalse();
        assertThat(s.body().get("floorCount").asInt()).isEqualTo(4);
        ToolArtifact artifact = s.artifact();
        assertThat(artifact).isNotNull();
        assertThat(artifact.filename()).isEqualTo("massing-한글-1.3dm");
        assertThat(artifact.contentTypeOrDefault()).isEqualTo("application/octet-stream");
        assertThat(artifact.sizeBytes()).isEqualTo(28L);
        assertThat(artifact.storageKey()).isEqualTo("architecture/massing/20260604/abc-uuid/massing-한글-1.3dm");
    }

    @Test
    void resultWithoutArtifact_treatedAsResultOnly_noArtifact() {
        // A terminal result event with no `artifact` key → body() = result node,
        // artifact() is null.
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson("{\"event\":\"result\",\"result\":{\"x\":1}}"))));

        ToolInvocationResult.Success s = (ToolInvocationResult.Success) dispatcher(
                desc("echo", "/internal/tools/echo", Duration.ofSeconds(5)))
                .invoke("call_1", "echo", null, userCtx(), p -> { });
        assertThat(s.artifact()).isNull();
        assertThat(s.body().path("x").asInt()).isEqualTo(1);
    }

    @Test
    void unknownTool_returnsFailure_INTERNAL() {
        ToolDescriptor d = desc("echo", "/internal/tools/echo", Duration.ofSeconds(5));
        ToolInvocationResult r = dispatcher(d).invoke(
                "call_1", "no_such_tool", null, userCtx(), p -> { });
        assertThat(r).isInstanceOf(ToolInvocationResult.Failure.class);
        assertThat(((ToolInvocationResult.Failure) r).code()).isEqualTo(ToolErrorCode.INTERNAL);
    }
}
