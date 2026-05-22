package com.playground.ragchat.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Wire-contract stability tests for {@link ToolErrorCode} per ADR-17 §2.
 *
 * <p>The enum names are the verbatim {@code tool_error.code} SSE payload
 * values — any rename / removal is a frontend-breaking change. These
 * tests fail fast if the enum drifts from the ADR-pinned 7-value set.
 */
class ToolErrorCodeTest {

    @Test
    void hasExactlySevenValues() {
        assertThat(ToolErrorCode.values()).hasSize(7);
    }

    @Test
    void carriesAllSevenAdrNames() {
        Set<String> names = Arrays.stream(ToolErrorCode.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(
                "TIMEOUT",
                "CIRCUIT_OPEN",
                "MAX_DEPTH",
                "UPSTREAM_4XX",
                "UPSTREAM_5XX",
                "SCHEMA_INVALID",
                "INTERNAL");
    }

    @Test
    void terminalCodes_resolveByName() {
        // CIRCUIT_OPEN and MAX_DEPTH are terminal per ADR-17 §3.1; the test
        // just confirms they are still resolvable as enum values so that
        // future renames trip this assertion.
        assertThat(ToolErrorCode.valueOf("CIRCUIT_OPEN")).isNotNull();
        assertThat(ToolErrorCode.valueOf("MAX_DEPTH")).isNotNull();
    }
}
