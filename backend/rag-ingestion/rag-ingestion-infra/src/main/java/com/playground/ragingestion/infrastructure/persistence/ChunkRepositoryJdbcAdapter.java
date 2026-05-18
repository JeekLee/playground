package com.playground.ragingestion.infrastructure.persistence;

import com.pgvector.PGvector;
import com.playground.ragingestion.application.repository.ChunkRepository;
import com.playground.ragingestion.domain.enums.Visibility;
import com.playground.ragingestion.domain.model.DocumentChunk;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.BodyChecksum;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate-backed implementation of {@link ChunkRepository} per ADR-13
 * §F. JPA cannot model {@code vector(1024)} natively (no built-in Hibernate
 * UserType ships with pgvector-java 0.1.6), so the bulk-insert path runs
 * via native SQL with the {@link PGvector} parameter type.
 *
 * <p>The repository is intentionally narrow — only the four operations the
 * use-case needs. A full JPA repository for diagnostic reads
 * ({@link DocumentChunkJpaEntity}) is registered alongside but unused at M3
 * P0.
 */
@Repository
public class ChunkRepositoryJdbcAdapter implements ChunkRepository {

    private final JdbcTemplate jdbc;

    public ChunkRepositoryJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BodyChecksum> findBodyChecksum(DocumentId documentId) {
        try {
            String value = jdbc.queryForObject(
                    "SELECT body_checksum FROM rag.document_chunks "
                            + "WHERE document_id = ? "
                            + "LIMIT 1",
                    String.class,
                    documentId.value());
            return value == null ? Optional.empty() : Optional.of(BodyChecksum.of(value));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void replaceAll(DocumentId documentId, List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "replaceAll requires a non-empty chunk list — empty bodies short-circuit upstream");
        }
        // DELETE-then-bulk-INSERT pattern per ADR-13 §12 step 4. Both
        // statements share the same transaction (the caller is
        // @Transactional) so partial-write state is never observable.
        jdbc.update(
                "DELETE FROM rag.document_chunks WHERE document_id = ?",
                documentId.value());

        // created_at / updated_at use the column DEFAULT now() — keeps the
        // INSERT out of the JDBC driver's Instant → TIMESTAMP WITH TIME ZONE
        // conversion path (pgjdbc rejects Instant directly) and matches the
        // updateVisibility path below which also relies on `now()`.
        jdbc.batchUpdate(
                "INSERT INTO rag.document_chunks "
                        + "(document_id, chunk_index, user_id, visibility, embedding, text, body_checksum) "
                        + "VALUES (?, ?, ?, ?, ?::vector, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        DocumentChunk chunk = chunks.get(i);
                        ps.setObject(1, chunk.documentId().value());
                        ps.setInt(2, chunk.chunkIndex());
                        ps.setObject(3, chunk.userId().value());
                        ps.setString(4, chunk.visibility().wireValue());
                        // PGvector.toString() emits the bracketed array form
                        // ([0.1,0.2,...]) that the `?::vector` cast in the
                        // SQL above parses into a pgvector value.
                        ps.setString(5, new PGvector(chunk.embedding().values()).toString());
                        ps.setString(6, chunk.text().value());
                        ps.setString(7, chunk.bodyChecksum().value());
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
    }

    @Override
    public int updateVisibility(DocumentId documentId, Visibility visibility) {
        return jdbc.update(
                "UPDATE rag.document_chunks "
                        + "SET visibility = ?, updated_at = now() "
                        + "WHERE document_id = ?",
                visibility.wireValue(),
                documentId.value());
    }

    @Override
    public int deleteAll(DocumentId documentId) {
        return jdbc.update(
                "DELETE FROM rag.document_chunks WHERE document_id = ?",
                documentId.value());
    }

    @Override
    public int countByDocument(DocumentId documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag.document_chunks WHERE document_id = ?",
                Integer.class,
                documentId.value());
        return count == null ? 0 : count;
    }
}
