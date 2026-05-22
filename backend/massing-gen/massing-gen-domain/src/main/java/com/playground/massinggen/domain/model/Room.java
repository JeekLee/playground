package com.playground.massinggen.domain.model;

import java.util.Objects;

/**
 * Immutable VO describing one room in the extracted program per ADR-18 §1.
 *
 * <p>{@code name} is the human-friendly Korean (or mixed Korean/English) room
 * label as the LLM extracted it from the brief — e.g. {@code "강의실 #1"} or
 * {@code "로비 (Lobby)"}. {@code areaM2} is the required floor area in square
 * meters. Both fields are validated non-null/non-blank and {@code areaM2 > 0}.
 *
 * <p>The wider {@code Program} VO holds a {@code List&lt;Room&gt;} plus the site
 * footprint and floor height.
 */
public record Room(String name, double areaM2) {

    public Room {
        Objects.requireNonNull(name, "Room.name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Room.name must not be blank");
        }
        if (areaM2 <= 0.0) {
            throw new IllegalArgumentException(
                    "Room.areaM2 must be positive (was " + areaM2 + ")");
        }
    }
}
