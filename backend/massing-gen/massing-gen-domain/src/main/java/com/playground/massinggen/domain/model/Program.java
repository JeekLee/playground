package com.playground.massinggen.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable VO bundling the room program + site geometry + floor height per
 * ADR-18 §1.
 *
 * <p>The LLM-extracted program plus request-time fallbacks (siteWidth /
 * siteDepth / floorHeight defaults from {@code application.yml} per
 * ADR-18 §8) flow through this record into {@link
 * com.playground.massinggen.domain.algorithm.MassingAlgorithm#compute(Program, int)}.
 *
 * @param rooms          extracted room list — must be non-empty
 * @param site           rectangular site footprint
 * @param floorHeightM   floor-to-floor height in meters (single height for
 *                       every floor — M8 P0 invariant per ADR-18 §11)
 */
public record Program(List<Room> rooms, SiteFootprint site, double floorHeightM) {

    public Program {
        Objects.requireNonNull(rooms, "Program.rooms must not be null");
        if (rooms.isEmpty()) {
            throw new IllegalArgumentException("Program.rooms must not be empty");
        }
        Objects.requireNonNull(site, "Program.site must not be null");
        if (floorHeightM <= 0.0) {
            throw new IllegalArgumentException(
                    "Program.floorHeightM must be positive (was " + floorHeightM + ")");
        }
        // Defensive copy — record's accessor returns the same reference, so
        // wrapping unmodifiable here also protects external mutation.
        rooms = List.copyOf(rooms);
    }

    /** Total floor area = sum of room areas. */
    public double totalRoomAreaM2() {
        double sum = 0.0;
        for (Room r : rooms) {
            sum += r.areaM2();
        }
        return sum;
    }
}
