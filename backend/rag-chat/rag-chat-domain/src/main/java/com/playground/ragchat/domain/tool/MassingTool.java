package com.playground.ragchat.domain.tool;

import java.net.URI;
import java.time.Duration;

/**
 * M8 — the first concrete tool descriptor registered in the
 * {@link ToolCatalog}. Per ADR-18 §A18.1 the BC behind this descriptor
 * is implemented in Python/FastAPI (services/massing-gen/); the
 * HTTP contract is language-neutral so rag-chat's
 * {@code ToolDispatcher} reaches it the same way as any future tool.
 *
 * <p>Endpoint default is the compose-internal hostname
 * {@code http://massing-gen-api:18083/internal/tools/generate-massing}
 * (ADR-08 §A08.11 Exception 4 sub-row). Override via
 * {@code PLAYGROUND_MASSING_GEN_TOOL_URL} env var if the BC moves.
 *
 * <p>Timeout is 60 s — brief extraction is LLM-bound (~30 s for a long
 * Korean brief) plus algorithm + .3dm serialization (~1 s), so 60 s
 * gives comfortable headroom under M7's per-tool breaker (which trips
 * on the 60 s timeout, not the response latency itself).
 */
public final class MassingTool {

    private static final String INPUT_SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                    + "\"type\":\"object\","
                    + "\"required\":[\"briefDocId\"],"
                    + "\"additionalProperties\":false,"
                    + "\"properties\":{"
                    + "\"briefDocId\":{\"type\":\"string\",\"format\":\"uuid\","
                    + "\"description\":\"docs.documents.id of the brief PDF to analyze\"},"
                    + "\"siteWidth\":{\"type\":\"number\",\"exclusiveMinimum\":0,"
                    + "\"description\":\"site width in metres (overrides brief-extracted value)\"},"
                    + "\"siteDepth\":{\"type\":\"number\",\"exclusiveMinimum\":0,"
                    + "\"description\":\"site depth in metres (overrides brief-extracted value)\"},"
                    + "\"floorHeight\":{\"type\":\"number\",\"exclusiveMinimum\":0,\"default\":3.5,"
                    + "\"description\":\"per-floor height in metres (defaults to 3.5)\"}"
                    + "}}";

    private static final String DESCRIPTION =
            "Given a brief document ID, extract the room program "
                    + "(rooms with areas in m²) from the brief, compute a stacked "
                    + "rectangular massing model, and return a downloadable Rhino "
                    + ".3dm file URL plus a one-line Korean summary. Use this when "
                    + "the user asks for a massing / 매싱 from a brief PDF.";

    /** Default endpoint hostname — overridden by env var when set. */
    private static final URI DEFAULT_ENDPOINT =
            URI.create("http://massing-gen-api:18083/internal/tools/generate-massing");

    /** Singleton descriptor instance — registered in {@link ToolCatalog#descriptors()}. */
    public static final ToolDescriptor MASSING = new ToolDescriptor(
            "generate_massing",
            DESCRIPTION,
            INPUT_SCHEMA,
            resolveEndpoint(),
            Duration.ofSeconds(60));

    private MassingTool() {
        // constants class — instantiation disallowed
    }

    private static URI resolveEndpoint() {
        String override = System.getenv("PLAYGROUND_MASSING_GEN_TOOL_URL");
        if (override == null || override.isBlank()) {
            return DEFAULT_ENDPOINT;
        }
        return URI.create(override);
    }
}
