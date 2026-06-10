package com.playground.docs.infrastructure.search;

import com.pgvector.PGvector;
import com.playground.docs.search.application.port.ChunkSearchPort;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * pgvector similarity search behind the {@code search_documents} tool
 * (agentic-search spec D1). Ported from chat's
 * {@code PgvectorChunkRetrievalAdapter} (ADR-14 §3.2) — same visibility filter
 * ({@code public OR (user_id=? AND private)}), same HNSW {@code ef_search}
 * runtime hint via {@code set_config(..., is_local=true)} inside an explicit
 * transaction, same {@link PGvector} literal serialization. Added here: an
 * optional {@code documentId} filter and a {@code docs.documents} title join
 * folded into the ranking query (chat did the title lookup as a second
 * batched SELECT; one join is simpler now that {@code k ≤ 20}).
 *
 * <p>Tenant isolation is by parameter binding on {@code user_id}; no string
 * concatenation.
 */
@Repository
@RequiredArgsConstructor
public class PgvectorChunkSearchAdapter implements ChunkSearchPort {

    private static final Logger log = LoggerFactory.getLogger(PgvectorChunkSearchAdapter.class);

    /**
     * ADR-13 §G.2 — runtime HNSW probe depth per search SELECT. Mirrors chat's
     * {@code ChatProperties.DEFAULT_EF_SEARCH}; the search tool has no tunable
     * properties surface yet, so the value is a constant (raise via a follow-up
     * properties binding if recall tuning is needed).
     */
    static final int EF_SEARCH = 40;

    private final JdbcTemplate jdbc;

    @Override
    public List<Row> search(UUID callerId, float[] embedding, int k, UUID documentIdOrNull) {
        if (embedding == null || embedding.length == 0) {
            return List.of();
        }

        String vectorLiteral = new PGvector(embedding).toString();
        String docIdLiteral = documentIdOrNull == null ? null : documentIdOrNull.toString();

        // SET LOCAL only takes effect inside an explicit transaction, and the
        // pooled connection arrives in auto-commit mode. Drop auto-commit for
        // the call, COMMIT on success / ROLLBACK on failure, restore before
        // returning the connection to the pool. (Verbatim from chat's adapter.)
        List<Row> rows;
        try {
            rows = jdbc.execute((java.sql.Connection conn) -> {
                boolean priorAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    // `SET LOCAL` rejects bound parameters; `set_config(..., true)`
                    // is the tx-scoped, parameter-accepting equivalent.
                    try (var setLocal = conn.prepareStatement(
                            "SELECT set_config('hnsw.ef_search', ?, true)")) {
                        setLocal.setString(1, Integer.toString(EF_SEARCH));
                        setLocal.execute();
                    }
                    List<Row> collected;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT c.document_id, c.chunk_index, c.text, c.visibility, d.title "
                                    + "FROM docs.document_chunks c "
                                    + "JOIN docs.documents d ON d.id = c.document_id "
                                    + "WHERE (c.visibility = 'public' "
                                    + "   OR (c.user_id = ? AND c.visibility = 'private')) "
                                    + "  AND (?::uuid IS NULL OR c.document_id = ?::uuid) "
                                    // OPERATOR(public.<=>): docs' datasource search_path is
                                    // `docs` only (?currentSchema=docs, no init-sql widening
                                    // like chat had), so the pgvector operator — installed in
                                    // `public` — must be schema-qualified explicitly.
                                    + "ORDER BY c.embedding OPERATOR(public.<=>) ?::public.vector "
                                    + "LIMIT ?")) {
                        ps.setObject(1, callerId);
                        ps.setString(2, docIdLiteral);
                        ps.setString(3, docIdLiteral);
                        ps.setString(4, vectorLiteral);
                        ps.setInt(5, k);
                        collected = new ArrayList<>(k);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                collected.add(new Row(
                                        (UUID) rs.getObject("document_id"),
                                        rs.getInt("chunk_index"),
                                        rs.getString("title"),
                                        rs.getString("text"),
                                        rs.getString("visibility")));
                            }
                        }
                    }
                    conn.commit();
                    return collected;
                } catch (RuntimeException | java.sql.SQLException ex) {
                    try {
                        conn.rollback();
                    } catch (java.sql.SQLException ignored) {
                        // best effort — original cause propagates below
                    }
                    throw ex;
                } finally {
                    conn.setAutoCommit(priorAutoCommit);
                }
            });
        } catch (RuntimeException ex) {
            log.error("pgvector search failed", ex);
            throw ex;
        }

        return rows == null ? List.of() : rows;
    }
}
