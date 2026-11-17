package ru.nexora.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Narrow operator-facing facade for the optional Core AI Assistant.
 * Model/provider integration is deliberately external; every proposed tool
 * call still passes through the permission and approval boundary.
 */
public final class CoreAiAssistant {
    private final AiToolRegistry tools;

    public CoreAiAssistant(AiToolRegistry tools) {
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    public AiToolRegistry.ToolResult invoke(
            AiToolRegistry.Principal operator,
            String toolId,
            Map<String, String> arguments,
            boolean explicitlyApproved
    ) {
        return tools.execute(operator, toolId, arguments, explicitlyApproved);
    }

    public java.util.Set<String> availableTools() {
        return tools.toolIds();
    }
}
