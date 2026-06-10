package com.playground.chat.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Byte-level cap for tool-result JSON fed back to the LLM (ADR-17 §4). When the
 * serialized body exceeds {@code capBytes}, it is replaced with a
 * {@code {truncated, originalBytes, excerpt}} envelope (the excerpt is the first
 * {@code capBytes - 64} bytes, leaving headroom for the envelope keys).
 *
 * <p>Single implementation shared by the dispatcher boundary
 * ({@code WebClientToolDispatcher}, first pass) and the orchestrator's
 * defensive re-cap ({@code ToolLoop}). Each caller keeps its own catch + logging
 * policy around {@link #truncate}.
 */
public final class JsonTruncator {

    private JsonTruncator() {
    }

    /** Outcome of a cap attempt: the (possibly enveloped) value + whether it was truncated. */
    public record Result(JsonNode value, boolean truncated, int originalBytes) {
    }

    /**
     * Cap {@code body} to {@code capBytes}. Returns the original node untouched
     * when it fits, else the truncation envelope. Throws on serialization
     * failure so the caller can decide whether to fall back to the raw body.
     */
    public static Result truncate(ObjectMapper mapper, JsonNode body, int capBytes) throws IOException {
        byte[] serialized = mapper.writeValueAsBytes(body);
        if (serialized.length <= capBytes) {
            return new Result(body, false, serialized.length);
        }
        int excerptCap = Math.max(0, capBytes - 64);
        byte[] excerptBytes = new byte[excerptCap];
        System.arraycopy(serialized, 0, excerptBytes, 0, excerptCap);
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("truncated", true);
        envelope.put("originalBytes", serialized.length);
        envelope.put("excerpt", new String(excerptBytes, StandardCharsets.UTF_8));
        return new Result(envelope, true, serialized.length);
    }
}
