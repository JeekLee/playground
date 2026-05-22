package com.playground.ragchat.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Invariant tests for {@link ToolCatalog} per ADR-17 §1 + §8 + PRD Story 10.
 *
 * <p>P0 invariant: {@code descriptors()} returns an empty, immutable list
 * — no concrete tools are registered at M7 ship. M8 lands the first
 * descriptor (single-file PR per ADR-17 §D).
 */
class ToolCatalogTest {

    @Test
    void isEmpty_atM7Ship() {
        assertThat(ToolCatalog.descriptors()).isEmpty();
    }

    @Test
    void list_isImmutable() {
        List<ToolDescriptor> list = ToolCatalog.descriptors();
        ToolDescriptor d = new ToolDescriptor(
                "echo", "echo back", null,
                URI.create("http://t/"), Duration.ofSeconds(5));
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
