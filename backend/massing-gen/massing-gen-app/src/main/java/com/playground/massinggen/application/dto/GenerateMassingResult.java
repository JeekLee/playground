package com.playground.massinggen.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Use-case output mirroring the wire shape of ADR-18 §21 (the controller
 * trivially maps this record to JSON).
 *
 * @param outputId    {@code arch.outputs.id} — used to build {@code fileUrl}
 * @param programJson the extracted + schema-validated room program (LLM
 *                    output passed through; carries {@code rooms},
 *                    {@code siteWidthM}, {@code siteDepthM},
 *                    {@code floorHeightM})
 * @param totalAreaM2 sum of {@code rooms[].areaM2}
 * @param floorCount  computed floor count
 * @param summary     Korean-fixed summary per ADR-18 §5
 */
public record GenerateMassingResult(
        UUID outputId,
        JsonNode programJson,
        double totalAreaM2,
        int floorCount,
        String summary) {

    /** Relative URL the frontend uses for the {@code <a download>} button. */
    public String fileUrl() {
        return "/api/arch/outputs/" + outputId;
    }
}
