package com.playground.ragchat.domain.tool;

import java.util.List;

/**
 * Classpath-loaded constants surface enumerating the tool BCs that
 * {@code rag-chat} can dispatch to per ADR-17 §1 + §8 + PRD Story 1.
 *
 * <p>P0 (M7 ship) returned an empty list. M8 adds the first descriptor
 * ({@link MassingTool#MASSING}) — see ADR-08 §A08.11 sub-row + ADR-18
 * §A18.5 §22. Subsequent tool BCs add constants in a single-file PR
 * per ADR-17 §D.
 *
 * <p>The M4 invariant (PRD Story 10 / M7) is preserved as a property
 * of the surrounding code path — the chat-turn use case enters the
 * tool-aware Spring AI flow only when a turn's resolved tool catalog
 * is non-empty AND the LLM emits {@code tool_call}. Empty catalogs
 * still emit M4-shaped SSE streams; M8 just no longer ships with one.
 *
 * <p>This class is Spring-free per the {@code -domain} invariant
 * (ADR-02 v2). It exposes a static accessor returning an immutable
 * {@link List}; consumers in {@code rag-chat-app} /
 * {@code rag-chat-infra} obtain the list at startup and may cache it
 * for the lifetime of the JVM.
 */
public final class ToolCatalog {

    /**
     * The full set of registered tool descriptors.
     *
     * <p>The list is intentionally immutable so callers cannot
     * accidentally mutate the shared instance. A future dynamic
     * implementation may swap this for a registry-backed list without
     * changing consumer API (ADR-17 §D forward-compat hook).
     */
    private static final List<ToolDescriptor> DESCRIPTORS = List.of(MassingTool.MASSING);

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
