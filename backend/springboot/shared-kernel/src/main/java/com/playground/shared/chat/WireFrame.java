package com.playground.shared.chat;

import java.util.Map;

/**
 * Framework-neutral SSE wire envelope: the SSE {@code event:} name plus the
 * JSON {@code data} payload as a map. Produced by {@link ChatStreamEvent#toWire()}
 * so each event variant owns its own wire shape, while shared-kernel stays free
 * of any transport type (Spring's {@code ServerSentEvent} lives only in the
 * -api controllers, which wrap this record). Keeps the event model Spring-free
 * per ADR-01 v2 + ADR-03.
 *
 * @param event SSE event name (e.g. {@code "phase"}, {@code "token"}, {@code "done"})
 * @param data  JSON payload map; values may be nested Jackson-serializable
 *              carriers (CitationDto list, JsonNode args/result)
 */
public record WireFrame(String event, Map<String, Object> data) {}
