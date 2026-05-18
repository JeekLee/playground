package com.playground.ragchat.infrastructure.persistence;

import com.pgvector.PGvector;
import com.playground.ragchat.application.port.ChunkRetrievalPort;
import com.playground.ragchat.application.properties.RagChatProperties;
import com.playground.ragchat.domain.enums.Visibility;
import com.playground.ragchat.domain.model.RetrievedChunk;
import com.playground.ragchat.domain.model.id.DocumentId;
import com.playground.ragchat.domain.model.id.UserId;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * pgvector retrieval per ADR-14 §3.2 (verbatim SQL). Cross-schema SELECT into
 * {@code rag.document_chunks} for similarity ranking plus a second batched
 * SELECT into {@code docs.documents} for title enrichment (per ADR-14 §3 —
 * the search_path covers both schemas, but we use fully-qualified table names
 * for clarity).
 *
 * <p>Tenant isolation is by parameter binding on the {@code user_id}
 * placeholder; no string concatenation.
 */
@Repository
public class PgvectorChunkRetrievalAdapter implements ChunkRetrievalPort {

    private static final Logger log = LoggerFactory.getLogger(PgvectorChunkRetrievalAdapter.class);

    private final JdbcTemplate jdbc;
    private final RagChatProperties properties;

    public PgvectorChunkRetrievalAdapter(JdbcTemplate jdbc, RagChatProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public List<RetrievedChunk> retrieve(UserId caller, float[] queryEmbedding, int k) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }

        String vectorLiteral = new PGvector(queryEmbedding).toString();

        // The retrieval SQL per ADR-14 §3.2. SET LOCAL only takes effect
        // inside an explicit transaction (outside, Postgres raises
        // "SET LOCAL can only be used in transaction blocks"), and the
        // pooled connection arrives in auto-commit mode. Drop auto-commit
        // for the duration of the call, COMMIT on success / ROLLBACK on
        // failure, and restore auto-commit before handing the connection
        // back to the pool.
        List<ChunkRow> rows;
        try {
            rows = jdbc.execute((java.sql.Connection conn) -> {
            boolean priorAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // `SET LOCAL …` is a utility statement and rejects bound
                // parameters (`syntax error at or near "$1"`). `set_config`
                // is a regular function call that accepts parameters and,
                // with `is_local = true`, has the same tx-scoped effect.
                try (var setLocal = conn.prepareStatement(
                        "SELECT set_config('hnsw.ef_search', ?, true)")) {
                    setLocal.setString(1, Integer.toString(properties.efSearch()));
                    setLocal.execute();
                }
                List<ChunkRow> collected;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT document_id, chunk_index, text, user_id, visibility "
                                + "FROM rag.document_chunks "
                                + "WHERE visibility = 'public' "
                                + "   OR (user_id = ? AND visibility = 'private') "
                                + "ORDER BY embedding <=> ?::public.vector "
                                + "LIMIT ?")) {
                    ps.setObject(1, caller.value());
                    ps.setString(2, vectorLiteral);
                    ps.setInt(3, k);
                    collected = new ArrayList<>(k);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            collected.add(new ChunkRow(
                                    (UUID) rs.getObject("document_id"),
                                    rs.getInt("chunk_index"),
                                    rs.getString("text"),
                                    (UUID) rs.getObject("user_id"),
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
            log.error("pgvector retrieval failed", ex);
            throw ex;
        }

        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        // Title enrichment — batched SELECT into docs.documents.
        Map<UUID, String> titlesByDocId = lookupTitles(rows.stream()
                .map(r -> r.documentId)
                .distinct()
                .toList());

        List<RetrievedChunk> out = new ArrayList<>(rows.size());
        int position = 1;
        for (ChunkRow r : rows) {
            String title = titlesByDocId.getOrDefault(r.documentId, "(untitled)");
            out.add(new RetrievedChunk(
                    position++,
                    DocumentId.of(r.documentId),
                    r.chunkIndex,
                    r.text,
                    title,
                    UserId.of(r.userId),
                    Visibility.fromWire(r.visibility)));
        }
        return out;
    }

    private Map<UUID, String> lookupTitles(List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        String idArray = documentIds.stream()
                .map(UUID::toString)
                .reduce((a, b) -> a + "," + b)
                .map(s -> "{" + s + "}")
                .orElse("{}");
        Map<UUID, String> out = new HashMap<>();
        jdbc.query(
                "SELECT id, title FROM docs.documents WHERE id = ANY (?::uuid[])",
                ps -> ps.setString(1, idArray),
                rs -> {
                    out.put((UUID) rs.getObject("id"), rs.getString("title"));
                });
        return out;
    }

    private record ChunkRow(UUID documentId, int chunkIndex, String text, UUID userId, String visibility) {}
}
