package com.playground.chat.application.tool;

import com.playground.chat.application.port.ToolRegistry;
import com.playground.chat.domain.tool.ToolCatalog;
import com.playground.chat.domain.tool.ToolDescriptor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Production {@link ToolRegistry} — delegates unconditionally to the
 * classpath-loaded {@link ToolCatalog}, ignoring the {@link UserContext}.
 *
 * <p>Preserves the pre-refactor behavior exactly (the catalog was the
 * default {@code ToolCatalog::descriptors} supplier). The per-user hook
 * on {@link ToolRegistry#descriptorsFor(UserContext)} is reserved for a
 * future dynamic catalog (ADR-17 §D).
 */
@Component
public class StaticToolRegistry implements ToolRegistry {
    @Override
    public List<ToolDescriptor> descriptorsFor(UserContext user) {
        return ToolCatalog.descriptors();
    }
}
