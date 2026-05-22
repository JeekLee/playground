package com.playground.docs.ingestion.application.repository;

import com.playground.docs.ingestion.domain.enums.Visibility;
import com.playground.docs.ingestion.domain.model.id.AuthorId;

/**
 * Projection of the immutable per-document ownership fields stored in any row
 * of {@code rag.document_chunks}. Used by {@code ReembedService} to reconstruct
 * the {@code (userId, visibility)} pair without an extra docs-api HTTP call
 * (ADR-08 — no new HTTP path per M3.1 plan §Task 17).
 *
 * <p>Both fields are invariant for a given {@code documentId}: every chunk row
 * for the same document carries the same {@code user_id} and {@code visibility}
 * and visibility changes are applied via {@code updateVisibility} — they do not
 * fork into different values per chunk.
 */
public record ChunkOwnerMeta(AuthorId userId, Visibility visibility) {}
