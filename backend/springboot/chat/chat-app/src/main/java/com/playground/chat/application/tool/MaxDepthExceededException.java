package com.playground.chat.application.tool;

/** Thrown when the per-turn depth cap is exceeded — aborts the round-trip. */
final class MaxDepthExceededException extends RuntimeException {
    MaxDepthExceededException(String tool, int cap) {
        super("Tool-call depth cap " + cap + " exceeded on tool " + tool);
    }
}
