package com.playground.massinggen.application.dto;

import java.util.UUID;

/**
 * Use-case input carrying the tool-call payload + caller identity per
 * ADR-18 §21. {@code briefDocId} is required; the three numeric fields are
 * optional — null means "use the extracted value (if any) or the
 * application.yml default".
 */
public record GenerateMassingCommand(
        UUID briefDocId,
        Double siteWidth,
        Double siteDepth,
        Double floorHeight,
        UserContext caller) {}
