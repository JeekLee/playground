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

        // The retrieval SQL per ADR-14 §3.2. SET LOCAL must run in the same
        // transaction; JdbcTemplate runs each call on a fresh connection from
        // the pool, so wrap both statements in execute(ConnectionCallback) to
        // keep them on one connection / one tx.
        List<ChunkRow> rows = jdbc.execute((java.sql.Connection conn) -> {
            try (var setLocal = conn.prepareStatement("SET LOCAL hnsw.ef_search = ?")) {
                setLocal.setInt(1, properties.efSearch());
                setLocal.execute();
            }
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
                List<ChunkRow> collected = new ArrayList<>(k);
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
                return collected;
            }
        });

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
