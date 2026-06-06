package com.playground.chat.domain.tool;

import java.net.URI;
import java.time.Duration;

/**
 * M8 — the first concrete tool descriptor registered in the
 * {@link ToolCatalog}. Per ADR-18 §A18.1 the BC behind this descriptor
 * is implemented in Python/FastAPI; per ADR-19 §D2 it is the
 * {@code architecture} BC hosted by the {@code agent-tools} service. The
 * HTTP contract is language-neutral so chat's
 * {@code ToolDispatcher} reaches it the same way as any future tool.
 *
 * <p>Endpoint default is the compose-internal hostname
 * {@code http://agent-tools:18083/internal/tools/generate-massing}
 * (ADR-08 §A08.11 Exception 4 sub-row; host renamed per ADR-19 §D2).
 * Override via {@code PLAYGROUND_MASSING_GEN_TOOL_URL} env var if the BC moves.
 *
 * <p>The BC answers as an {@code application/x-ndjson} stream
 * (tool-streaming spec D2/D4): progress + heartbeat lines followed by one
 * terminal {@code result} or {@code error} line. Two bounds govern the
 * call — an IDLE bound and an absolute (total) bound:
 * <ul>
 *   <li><b>idle</b> = 60 s — max gap between NDJSON signals. The BC emits
 *       a heartbeat every ~10 s, so 60 s is 6× that cadence: a quiet
 *       stretch this long means the BC is genuinely wedged, not merely
 *       busy. A heartbeat resets this timer, so a long LLM extraction that
 *       keeps the stream alive never trips it.</li>
 *   <li><b>total</b> = 600 s — absolute cap on the whole pipeline (brief
 *       extraction + algorithm + .3dm serialization + MinIO write) with
 *       worst-case headroom, enforced regardless of heartbeat activity.</li>
 * </ul>
 */
public final class MassingTool {

    private static final String INPUT_SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                    + "\"type\":\"object\","
                    + "\"required\":[\"briefDocId\"],"
                    + "\"additionalProperties\":false,"
                    + "\"properties\":{"
                    + "\"briefDocId\":{\"type\":\"string\",\"format\":\"uuid\","
                    + "\"description\":\"docs.documents.id of the brief PDF to analyze. "
                    + "Copy the exact id from the [YOUR DOCUMENTS] list in context that "
                    + "matches the document the user refers to (by ordinal such as "
                    + "'두 번째'/'second', by title, or by type). Never invent a uuid.\"},"
                    + "\"siteWidth\":{\"type\":\"number\",\"exclusiveMinimum\":0,"
                    + "\"description\":\"site width in metres (overrides brief-extracted value)\"},"
                    + "\"siteDepth\":{\"type\":\"number\",\"exclusiveMinimum\":0,"
                    + "\"description\":\"site depth in metres (overrides brief-extracted value)\"},"
                    + "\"floorHeight\":{\"type\":\"number\",\"exclusiveMinimum\":0,\"default\":3.5,"
                    + "\"description\":\"per-floor height in metres (defaults to 3.5)\"}"
                    + "}}";

    private static final String DESCRIPTION =
            "Build a stacked rectangular massing model (.3dm Rhino file) from "
                    + "a competition brief's room program.\n\n"
                    + "STRICT TRIGGER CRITERIA — invoke this tool ONLY when the "
                    + "user's most recent message explicitly requests a massing "
                    + "/ 매싱 / 매스 / mass / 매스모델 / .3dm be CREATED, "
                    + "GENERATED, or BUILT.\n\n"
                    + "REGENERATION COUNTS: a request to generate AGAIN (\"다시 "
                    + "생성해줘\", \"재생성\", \"다시 만들어줘\", \"regenerate\", "
                    + "\"one more time\") MUST invoke this tool again in the "
                    + "current turn. A massing result already present in the "
                    + "conversation history NEVER satisfies a new generation "
                    + "request — do not answer from history, and do not claim "
                    + "the model was (re)generated unless this tool ran in "
                    + "this turn.\n\n"
                    + "DO NOT invoke this tool for any of the following, even if "
                    + "the conversation mentions a brief or document:\n"
                    + "- Questions about the brief content (\"what does it say\", "
                    + "  \"실별 크기 적혀있나\", \"요구사항이 뭐야\")\n"
                    + "- Summarization or extraction requests that do NOT mention "
                    + "  massing (\"실 프로그램 추출해줘\", \"요약해줘\")\n"
                    + "- Casual conversation, meta-questions, follow-ups, "
                    + "  acknowledgements (\"야\", \"응\", \"맞아\")\n"
                    + "- General questions about Korean architecture briefs that "
                    + "  don't request a massing artifact\n\n"
                    + "When the user does explicitly request a massing, the tool "
                    + "extracts the room program (rooms with areas in m²) from the "
                    + "brief and computes a stacked rectangular massing. "
                    + "The tool result contains a one-line Korean summary "
                    + "(e.g., \"12실 · 3층 · 총 480 m²\"). "
                    + "The .3dm file is delivered via a download button in the UI automatically — "
                    + "DO NOT write any URL, file link, or download path in your text response. "
                    + "Just relay the summary from the tool result and confirm completion.";

    /** Default endpoint hostname — overridden by env var when set. */
    private static final URI DEFAULT_ENDPOINT =
            URI.create("http://agent-tools:18083/internal/tools/generate-massing");

    /** Singleton descriptor instance — registered in {@link ToolCatalog#descriptors()}. */
    public static final ToolDescriptor MASSING = new ToolDescriptor(
            "generate_massing",
            "매싱 모델",
            DESCRIPTION,
            INPUT_SCHEMA,
            resolveEndpoint(),
            Duration.ofSeconds(60),    // idle — heartbeat(10s)의 6배 여유
            Duration.ofSeconds(600));  // total cap — 파이프라인 최악치 여유

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
