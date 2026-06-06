package com.playground.ragchat.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Validation unit tests for {@link ToolDescriptor} per ADR-17 §1 + §8,
 * updated for the NDJSON streaming contract (tool-streaming spec D2/D4):
 * the descriptor now carries a {@code displayName} plus separate IDLE
 * ({@code timeout}) and absolute ({@code totalTimeout}) bounds.
 *
 * <p>The descriptor is the {@code rag-chat-domain} surface that
 * {@code ToolCatalog.descriptors()} returns. It is consumed by
 * Spring AI's {@code FunctionToolCallback.builder()} in
 * {@code rag-chat-infra}, so the validation here is the only
 * fail-fast layer that catches bad descriptor authors before runtime.
 */
class ToolDescriptorTest {

    @Test
    void constructs_withValidFields() {
        ToolDescriptor d = new ToolDescriptor(
                "generate_massing",
                "매싱 모델",
                "Generate a massing model from a brief PDF",
                "{\"type\":\"object\"}",
                URI.create("http://agent-tools:18086/internal/tools/generate-massing"),
                Duration.ofSeconds(30),
                Duration.ofSeconds(300));

        assertThat(d.name()).isEqualTo("generate_massing");
        assertThat(d.displayName()).isEqualTo("매싱 모델");
        assertThat(d.description()).isEqualTo("Generate a massing model from a brief PDF");
        assertThat(d.parameterSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(d.endpoint()).isEqualTo(URI.create(
                "http://agent-tools:18086/internal/tools/generate-massing"));
        assertThat(d.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(d.totalTimeout()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void nullName_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                null, "display", "desc", null, URI.create("http://t/"),
                Duration.ofSeconds(5), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankName_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "   ", "display", "desc", null, URI.create("http://t/"),
                Duration.ofSeconds(5), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankDisplayName_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "  ", "desc", null, URI.create("http://t/"),
                Duration.ofSeconds(5), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName");
    }

    @Test
    void blankDescription_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "display", "", null, URI.create("http://t/"),
                Duration.ofSeconds(5), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void nullEndpoint_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "display", "desc", null, null,
                Duration.ofSeconds(5), Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void nullTimeout_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "display", "desc", null, URI.create("http://t/"),
                null, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void zeroTimeout_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "display", "desc", null, URI.create("http://t/"),
                Duration.ZERO, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void negativeTimeout_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "display", "desc", null, URI.create("http://t/"),
                Duration.ofSeconds(-1), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void nullTotalTimeout_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "display", "desc", null, URI.create("http://t/"),
                Duration.ofSeconds(5), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("totalTimeout");
    }

    @Test
    void zeroTotalTimeout_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "display", "desc", null, URI.create("http://t/"),
                Duration.ofSeconds(5), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalTimeout");
    }

    @Test
    void nullParameterSchema_allowed_forNoArgTools() {
        ToolDescriptor d = new ToolDescriptor(
                "ping",
                "Ping",
                "Health check tool — no arguments",
                null,
                URI.create("http://t/"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30));
        assertThat(d.parameterSchema()).isNull();
    }
}
