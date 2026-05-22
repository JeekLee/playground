package com.playground.massinggen.domain.model;

import java.util.Objects;

/**
 * Immutable VO describing one rectangular room box produced by the
 * {@code MassingAlgorithm} per ADR-18 §1 + §11.
 *
 * <p>Coordinate convention (ADR-18 §11):
 * <ul>
 *   <li>{@code x}, {@code y} — lower-left corner of the box footprint, measured
 *       from the site footprint's lower-left origin {@code (0, 0)}. Meters.</li>
 *   <li>{@code z} — lower face of the box; equal to
 *       {@code (floor - 1) × floorHeight}. Meters.</li>
 *   <li>{@code widthM} — extent along X axis. Meters.</li>
 *   <li>{@code depthM} — extent along Y axis. Meters.</li>
 *   <li>{@code heightM} — extent along Z axis; equal to {@code floorHeight}
 *       for every room in M8 P0 (single-floor-height invariant).</li>
 *   <li>{@code roomName} — becomes the Rhino layer name in the sidecar
 *       serialization (preserves Korean room labels via UTF-8).</li>
 *   <li>{@code floor} — 1-indexed floor number; becomes a Rhino user-text
 *       attribute downstream of the sidecar.</li>
 * </ul>
 *
 * <p>Algorithm invariants enforced at construction:
 * {@code widthM &gt; 0}, {@code depthM &gt; 0}, {@code heightM &gt; 0}, {@code floor &ge; 1}.
 */
public record RoomBox(
        int floor,
        double x,
        double y,
        double widthM,
        double depthM,
        double heightM,
        String roomName) {

    public RoomBox {
        if (floor < 1) {
            throw new IllegalArgumentException(
                    "RoomBox.floor must be >= 1 (was " + floor + ")");
        }
        if (widthM <= 0.0) {
            throw new IllegalArgumentException(
                    "RoomBox.widthM must be positive (was " + widthM + ")");
        }
        if (depthM <= 0.0) {
            throw new IllegalArgumentException(
                    "RoomBox.depthM must be positive (was " + depthM + ")");
        }
        if (heightM <= 0.0) {
            throw new IllegalArgumentException(
                    "RoomBox.heightM must be positive (was " + heightM + ")");
        }
        if (x < 0.0) {
            throw new IllegalArgumentException(
                    "RoomBox.x must be non-negative (was " + x + ")");
        }
        if (y < 0.0) {
            throw new IllegalArgumentException(
                    "RoomBox.y must be non-negative (was " + y + ")");
        }
        Objects.requireNonNull(roomName, "RoomBox.roomName must not be null");
        if (roomName.isBlank()) {
            throw new IllegalArgumentException("RoomBox.roomName must not be blank");
        }
    }

    /** Footprint area for accounting (widthM × depthM). */
    public double footprintAreaM2() {
        return widthM * depthM;
    }
}
