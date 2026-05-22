package com.playground.massinggen.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Intermediate projection produced by the {@link
 * com.playground.massinggen.application.port.BriefProgramExtractor} port.
 *
 * <p>{@code rooms} is the extracted list of (name, areaM2) pairs (validated
 * against the JSON Schema). The site/floor-height fields are optional —
 * null means the LLM did not surface a value and the orchestrator should
 * fall back to the request param or the application.yml default per
 * ADR-18 §8.
 *
 * <p>{@code rawProgramJson} carries the original LLM response Jackson tree
 * so it can be persisted to {@code arch.outputs.program_json} and returned
 * to the frontend verbatim (preserving any Korean room name characters
 * UTF-8 round-trip).
 */
public record ExtractedProgram(
        List<ExtractedRoom> rooms,
        Double siteWidthM,
        Double siteDepthM,
        Double floorHeightM,
        JsonNode rawProgramJson) {

    /** Single (name, areaM2) pair per ADR-18 §9 schema. */
    public record ExtractedRoom(String name, double areaM2) {}
}
