package com.playground.ragchat.domain.tool;

import java.util.List;

/**
 * Classpath-loaded constants surface enumerating the tool BCs that
 * {@code rag-chat} can dispatch to per ADR-17 §1 + §8 + PRD Story 1.
 *
 * <p>P0 (M7 ship) returns an <b>empty</b> list — no tools are registered.
 * The M4 invariant (PRD Story 10) holds: when {@link #descriptors()} is
 * empty, the chat-turn SSE stream emits exactly what M4 emitted (no
 * {@code tool_call} / {@code tool_result} / {@code tool_error} events).
 *
 * <p>M8 will add the first descriptor ({@code generate_massing} per
 * spec §5 / ADR-08 §A08.8). Subsequent tool BCs add constants here in
 * a single-file PR per ADR-17 §D.
 *
 * <p>This class is Spring-free per the {@code -domain} invariant
 * (ADR-02 v2). It exposes a static accessor returning an immutable
 * {@link List}; consumers in {@code rag-chat-app} /
 * {@code rag-chat-infra} obtain the list at startup and may cache it
 * for the lifetime of the JVM.
 */
public final class ToolCatalog {

    /**
     * The full set of registered tool descriptors. P0 = empty.
     *
     * <p>The list is intentionally immutable so callers cannot
     * accidentally mutate the shared instance. A future dynamic
     * implementation may swap this for a registry-backed list without
     * changing consumer API (ADR-17 §D forward-compat hook).
     */
    private static final List<ToolDescriptor> DESCRIPTORS = List.of();

    private ToolCatalog() {
        // utility class — instantiation disallowed
    }

    /**
     * @return the immutable list of registered tool descriptors. P0 = empty.
     */
    public static List<ToolDescriptor> descriptors() {
        return DESCRIPTORS;
    }
}
