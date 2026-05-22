package com.playground.massinggen.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for {@code arch.outputs} persistence + owner-only fetch
 * per ADR-18 §13 + §21. The adapter ({@code ArchOutputJpaRepository} in
 * {@code massing-gen-infra}) lives over JPA + Postgres BYTEA.
 */
public interface ArchOutputRepository {

    /**
     * Insert a new arch.outputs row. Returns the generated row's metadata
     * for the use case to assemble its result DTO.
     */
    SavedOutput save(NewOutput payload);

    /**
     * Look up an arch.outputs row by (id, callerId) for the owner-only
     * download endpoint per ADR-18 §21. Returns empty when the row does
     * not exist OR when the caller does not own the row — tenant-isolation
     * invariant maps both to 404 at the controller boundary (M2 docs
     * pattern; never 403, never leak existence).
     */
    Optional<DownloadedOutput> findOwnedById(UUID outputId, UUID callerId);

    /** Use-case payload for {@link #save(NewOutput)}. */
    record NewOutput(
            UUID briefDocId,
            UUID userId,
            byte[] fileBytes,
            String programJsonString,
            double totalAreaM2,
            int floorCount,
            String summary,
            String briefSlug) {}

    /** Use-case result from {@link #save(NewOutput)}. */
    record SavedOutput(UUID id, java.time.Instant createdAt) {}

    /** Use-case result from {@link #findOwnedById(UUID, UUID)}. */
    record DownloadedOutput(
            UUID id,
            byte[] fileBytes,
            String briefSlug,
            java.time.Instant createdAt) {}
}
