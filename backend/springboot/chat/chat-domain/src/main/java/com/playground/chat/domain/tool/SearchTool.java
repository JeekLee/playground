package com.playground.chat.domain.tool;

import java.net.URI;
import java.time.Duration;

/**
 * M9 (agentic-search spec D1/D2) — the {@code search_documents} tool
 * descriptor. The BC behind it is the docs-api internal endpoint
 * {@code POST /internal/tools/search-documents} (Task 1): it embeds the
 * query, runs the pgvector search over {@code docs.document_chunks}, and
 * answers an {@code application/x-ndjson} terminal-only stream — a single
 * {@code result} line (search is ~hundreds of ms, no progress/heartbeat) or
 * one {@code error} line. The dispatcher idle bound (30s) is far larger than
 * the search time.
 *
 * <p>This replaces the always-on pipeline retrieval (embedding + pgvector +
 * RETRIEVED CONTEXT prompt block) that chat used to run every turn — the LLM
 * now searches only when the question concerns uploaded-document content.
 * Results carry per-call {@code [N]} positions which chat's
 * {@code TurnCitationAccumulator} renumbers into global {@code [N]} positions,
 * preserving the existing citation machinery.
 *
 * <p>Endpoint default is the compose-internal hostname
 * {@code http://docs-api:18082/internal/tools/search-documents}; override via
 * {@code PLAYGROUND_SEARCH_TOOL_URL} env var (mirrors {@link MassingTool}'s
 * pattern).
 */
public final class SearchTool {

    private static final String INPUT_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema",
             "type":"object","required":["query"],"additionalProperties":false,
             "properties":{
               "query":{"type":"string","minLength":1,
                 "description":"검색할 내용 — 사용자 질문의 핵심을 한국어 그대로"},
               "topK":{"type":"integer","minimum":1,"maximum":20,
                 "description":"결과 수 (기본 6)"},
               "documentId":{"type":"string","format":"uuid",
                 "description":"특정 문서로 한정할 때 [YOUR DOCUMENTS]의 id"}}}
            """;

    private static final String DESCRIPTION = """
            Search the user's uploaded documents by meaning (pgvector). \
            STRICT TRIGGER: call when the user's question concerns the CONTENT \
            of uploaded documents (면적, 요구사항, 일정, "문서에 뭐라고 적혀있어" 류). \
            DO NOT call for casual conversation, general knowledge, or to restate \
            results already retrieved in THIS turn. When the user names a specific \
            document, set documentId from the [YOUR DOCUMENTS] list. \
            Results carry [N] positions — cite facts from them as [N] in your answer. \
            Never assert document contents you have not retrieved this turn.""";

    /** Default endpoint hostname — overridden by env var when set. */
    private static final URI DEFAULT_ENDPOINT =
            URI.create("http://docs-api:18082/internal/tools/search-documents");

    /** Singleton descriptor instance — registered in {@link ToolCatalog#descriptors()}. */
    public static final ToolDescriptor SEARCH = new ToolDescriptor(
            "search_documents",
            "문서 검색",
            DESCRIPTION,
            INPUT_SCHEMA,
            resolveEndpoint(),
            Duration.ofSeconds(30),   // idle — terminal-only stream, ≫ search time
            Duration.ofSeconds(60));  // total cap

    private SearchTool() {
        // constants class — instantiation disallowed
    }

    private static URI resolveEndpoint() {
        String override = System.getenv("PLAYGROUND_SEARCH_TOOL_URL");
        if (override == null || override.isBlank()) {
            return DEFAULT_ENDPOINT;
        }
        return URI.create(override);
    }
}
