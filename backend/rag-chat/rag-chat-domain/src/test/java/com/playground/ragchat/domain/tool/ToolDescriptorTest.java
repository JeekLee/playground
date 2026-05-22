package com.playground.ragchat.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Validation unit tests for {@link ToolDescriptor} per ADR-17 §1 + §8.
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
                "Generate a massing model from a brief PDF",
                "{\"type\":\"object\"}",
                URI.create("http://massing-gen-api:18086/internal/tools/generate-massing"),
                Duration.ofSeconds(30));

        assertThat(d.name()).isEqualTo("generate_massing");
        assertThat(d.description()).isEqualTo("Generate a massing model from a brief PDF");
        assertThat(d.parameterSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(d.endpoint()).isEqualTo(URI.create(
                "http://massing-gen-api:18086/internal/tools/generate-massing"));
        assertThat(d.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void nullName_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                null, "desc", null, URI.create("http://t/"), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankName_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "   ", "desc", null, URI.create("http://t/"), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankDescription_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "", null, URI.create("http://t/"), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void nullEndpoint_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "desc", null, null, Duration.ofSeconds(5)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void nullTimeout_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "desc", null, URI.create("http://t/"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void zeroTimeout_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "desc", null, URI.create("http://t/"), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void negativeTimeout_rejected() {
        assertThatThrownBy(() -> new ToolDescriptor(
                "echo", "desc", null, URI.create("http://t/"), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void nullParameterSchema_allowed_forNoArgTools() {
        ToolDescriptor d = new ToolDescriptor(
                "ping",
                "Health check tool — no arguments",
                null,
                URI.create("http://t/"),
                Duration.ofSeconds(5));
        assertThat(d.parameterSchema()).isNull();
    }
}
