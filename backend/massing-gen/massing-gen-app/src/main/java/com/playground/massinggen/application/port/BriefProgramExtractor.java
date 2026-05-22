package com.playground.massinggen.application.port;

import com.playground.massinggen.application.dto.ExtractedProgram;

/**
 * Outbound port — the LLM call that turns a brief body (Markdown) into a
 * structured {@link ExtractedProgram} per ADR-18 §4 + §10.
 *
 * <p>The adapter ({@code SpringAiBriefExtractorAdapter} in
 * {@code massing-gen-infra}) calls spark-inference-gateway via Spring AI
 * 1.0.0 GA's {@code ChatClient}, parses the response as JSON, and validates
 * it against the {@code programJson.schema.json} (Draft 2020-12, networknt
 * library). Schema violations or non-JSON output throw
 * {@link com.playground.massinggen.domain.exception.MassingException} with
 * {@link com.playground.massinggen.domain.exception.MassingErrorCode#BRIEF_EXTRACTION_FAILED}.
 *
 * <p>The {@link #extract(String)} call is synchronous — extraction is a
 * pre-algorithm gate and the use case blocks on it; the orchestrator's
 * outbound calls (LLM, sidecar, docs-api) are sequenced per turn.
 */
public interface BriefProgramExtractor {

    /**
     * Extract a structured program from a brief body.
     *
     * @param briefBody markdown body fetched from docs-api
     * @return the validated, parsed program
     */
    ExtractedProgram extract(String briefBody);
}
