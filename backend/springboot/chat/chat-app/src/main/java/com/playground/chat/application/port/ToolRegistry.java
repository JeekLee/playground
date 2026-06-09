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
    // Source of the tools offered to the LLM this turn. UserContext is a
    // hook for future per-user/per-plan catalogs (ADR-17 §D); the current
    // implementation ignores it.
    List<ToolDescriptor> descriptorsFor(UserContext user);
}
