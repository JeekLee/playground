package com.playground.ragchat.application.dto;

/**
 * Wire-shape citation for the {@code done} SSE event payload (spec §5.2,
 * revised in PR B). Built by {@code ChatTurnService} from a
 * {@code RetrievedChunk} after the citation-marker extraction filters
 * the retrieved set down to the actually-cited subset; the controller's
 * default Jackson serialization is enough — no manual map-building.
 *
 * <p>Field semantics mirror the (now-removed) retrieval event payload:
 * {@code n} is 1-indexed, matches the {@code [N]} markers that survived
 * in the assistant's final text. The excerpt is the first ~160 chars of
 * the chunk text (same trim {@code retrievalPayload} used previously).
 */
public record CitationDto(
        int n,
        String documentId,
        int chunkIndex,
        String title,
        String excerpt,
        String visibility) {
}
