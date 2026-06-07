package com.playground.chat.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Invariant tests for {@link ToolCatalog} per ADR-17 §1 + §8 + PRD Story 10.
 *
 * <p>Post-M8: {@code descriptors()} contains the {@code generate_massing}
 * descriptor (ADR-08 §A08.11 sub-row + ADR-18 §A18.5 §22). Subsequent tool
 * BCs land single-file PRs that append their own constant here.
 */
class ToolCatalogTest {

    @Test
    void registers_massingTool_postM8() {
        assertThat(ToolCatalog.descriptors())
                .extracting(ToolDescriptor::name)
                .contains("generate_massing");
    }

    @Test
    void registers_searchTool_postM9() {
        assertThat(ToolCatalog.descriptors())
                .extracting(ToolDescriptor::name)
                .contains("search_documents");
    }

    @Test
    void list_isImmutable() {
        List<ToolDescriptor> list = ToolCatalog.descriptors();
        ToolDescriptor d = new ToolDescriptor(
                "echo", "Echo", "echo back", null,
                URI.create("http://t/"), Duration.ofSeconds(5), Duration.ofSeconds(30));
        assertThatThrownBy(() -> list.add(d))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sameInstance_returnedOnEveryCall() {
        // Forward-compat: callers MAY cache the list — the static accessor
        // must consistently return the same content (identity not required,
        // structural equality is).
        assertThat(ToolCatalog.descriptors()).isEqualTo(ToolCatalog.descriptors());
    }
}
