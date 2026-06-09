package com.playground.chat.application.tool;

import com.playground.chat.domain.tool.ToolErrorCode;

/** Thrown when a tool error is terminal (CIRCUIT_OPEN / MAX_DEPTH). */
final class ToolCallTerminalException extends RuntimeException {
    final ToolErrorCode code;
    ToolCallTerminalException(ToolErrorCode code, String message) {
        super(message);
        this.code = code;
    }
}
