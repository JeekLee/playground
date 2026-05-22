package com.playground.massinggen.domain.model;

/**
 * Immutable VO describing the rectangular site footprint per ADR-18 §1 + §11.
 *
 * <p>{@code widthM} corresponds to the X axis; {@code depthM} to the Y axis.
 * The origin is at the lower-left corner of the site footprint
 * ({@code x = y = 0}) per ADR-18 §11's coordinate convention.
 */
public record SiteFootprint(double widthM, double depthM) {

    public SiteFootprint {
        if (widthM <= 0.0) {
            throw new IllegalArgumentException(
                    "SiteFootprint.widthM must be positive (was " + widthM + ")");
        }
        if (depthM <= 0.0) {
            throw new IllegalArgumentException(
                    "SiteFootprint.depthM must be positive (was " + depthM + ")");
        }
    }

    /** Area = widthM × depthM in square meters. */
    public double areaM2() {
        return widthM * depthM;
    }
}
