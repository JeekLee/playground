package com.playground.massinggen.application.dto;

import java.util.UUID;

/**
 * Use-case-internal projection of the docs-api metadata response per
 * ADR-08 §A08.12 + ADR-18 §3.
 *
 * <p>{@code extractionStatus} is the wire value carried by
 * {@code docs.documents.extraction_status} — {@code "extracted"} means the
 * body is ready for consumption. Any other value triggers
 * {@code BRIEF_NOT_READY}.
 *
 * <p>{@code ownerUserId} + {@code visibility} drive the access check:
 * a private brief whose owner does not match the caller maps to
 * {@code BRIEF_NOT_ACCESSIBLE}.
 *
 * @param id               brief document UUID
 * @param title            brief title — used to derive the
 *                         Content-Disposition filename slug
 * @param body             the GFM-markdown body (may be empty when
 *                         extraction is in flight)
 * @param extractionStatus {@code "extracted"} / {@code "extracting"} /
 *                         {@code "pending_extraction"} / {@code "failed"}
 * @param visibility       {@code "public"} or {@code "private"}
 * @param ownerUserId      author/owner UUID
 */
public record BriefMetadata(
        UUID id,
        String title,
        String body,
        String extractionStatus,
        String visibility,
        UUID ownerUserId) {}
