package com.playground.chat.application.port;

import com.playground.chat.application.tool.UserContext;
import com.playground.chat.domain.tool.ToolDescriptor;
import java.util.List;

/**
 * Source of the tools offered to the LLM for a single chat turn.
 *
 * <p>Lives in {@code chat-app} (not {@code chat-domain}) because it
 * references {@link UserContext}, a {@code chat-app} type.
 */
@FunctionalInterface
public interface ToolRegistry {
    // Source of the tools offered to the LLM this turn. The UserContext is a
    // forward-compat hook for a future dynamic, registry-backed catalog
    // (ADR-17 §D's "registry-backed list" direction) — e.g. per-user/per-plan
    // tool gating; the current static implementation ignores it.
    List<ToolDescriptor> descriptorsFor(UserContext user);
}
