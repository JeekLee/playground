package com.playground.chat.application.port;

import com.playground.chat.domain.model.RetrievedChunk;
import com.playground.chat.domain.model.id.UserId;
import java.util.List;

/**
 * Outbound port for pgvector top-K retrieval per ADR-14 §3.2. Runs the
 * canonical retrieval SQL against the cross-schema {@code docs.document_chunks}
 * + {@code docs.documents} join.
 *
 * <p>{@code visibility} filter pinned at SQL: {@code WHERE visibility='public'
 * OR (user_id=:caller AND visibility='private')}. Tenant isolation is by
 * parameter binding, not string concatenation — matches the M3 ADR §F
 * invariant.
 */
public interface ChunkRetrievalPort {

    /**
     * Top-K nearest chunks for the query embedding, ordered by cosine distance
     * (ascending). The returned list is up to K long; may be empty if the
     * corpus has no public chunks and the caller owns none.
     *
     * @param caller          tenant identifier; matched against private chunks
     * @param queryEmbedding  1024-dim BGE-M3 vector for the user message
     * @param k               retrieval depth (default 6, configurable per ADR-14 §7)
     */
    List<RetrievedChunk> retrieve(UserId caller, float[] queryEmbedding, int k);
}
