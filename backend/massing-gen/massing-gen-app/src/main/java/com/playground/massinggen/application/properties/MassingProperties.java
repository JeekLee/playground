package com.playground.massinggen.application.properties;

/**
 * Plain POJO carrying the M8 algorithm + sidecar + docs-api tunables per
 * ADR-18 §19. The Spring Boot {@code @ConfigurationProperties} binding
 * lives in {@code massing-gen-infra} (per the ADR-02 v2 layering rule and
 * the rag-chat precedent in {@code RagChatPropertiesBinding} —
 * {@code @ConfigurationProperties} is a Spring Boot type and {@code -app}
 * is forbidden from importing {@code org.springframework.boot.*}). This
 * POJO is created by the binding's {@code toProperties()} accessor and
 * exposed as a Spring bean.
 *
 * <p>Fields (all required at construction; the binding fills them from
 * application.yml with the env-overridable defaults shipped by the
 * convention):
 * <ul>
 *   <li>{@code maxFloors} — algorithm cap per ADR-18 §8 (default 10)</li>
 *   <li>{@code defaultSiteWidthM} / {@code defaultSiteDepthM} /
 *       {@code defaultFloorHeightM} — request fallbacks per ADR-18 §8
 *       (defaults 20.0 / 10.0 / 3.5)</li>
 *   <li>{@code rhino3dmBridge} — sidecar URL + timeout per ADR-18 §11/§17</li>
 *   <li>{@code docsApi} — docs-api URL + body fetch timeout per
 *       ADR-08 §A08.12 Exception 5 + ADR-18 §3</li>
 * </ul>
 */
public record MassingProperties(
        int maxFloors,
        double defaultSiteWidthM,
        double defaultSiteDepthM,
        double defaultFloorHeightM,
        Rhino3dmBridge rhino3dmBridge,
        DocsApi docsApi) {

    public static final int DEFAULT_MAX_FLOORS = 10;
    public static final double DEFAULT_SITE_WIDTH_M = 20.0;
    public static final double DEFAULT_SITE_DEPTH_M = 10.0;
    public static final double DEFAULT_FLOOR_HEIGHT_M = 3.5;

    public record Rhino3dmBridge(String url, long timeoutMs) {}

    public record DocsApi(String url, long bodyFetchTimeoutMs) {}

    public static MassingProperties defaults() {
        return new MassingProperties(
                DEFAULT_MAX_FLOORS,
                DEFAULT_SITE_WIDTH_M,
                DEFAULT_SITE_DEPTH_M,
                DEFAULT_FLOOR_HEIGHT_M,
                new Rhino3dmBridge("http://rhino3dm-bridge:4000", 30_000L),
                new DocsApi("http://docs-api:18082", 5_000L));
    }
}
