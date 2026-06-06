package com.playground.chat.infrastructure.persistence;

import com.playground.chat.application.service.SessionService;
import com.playground.chat.domain.model.id.DocumentId;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves a cited {@code (document_id, chunk_index)} pair into a
 * {@link SessionService.CitationResolver.Resolved} by JOINing
 * {@code docs.documents} (title) and {@code docs.document_chunks} (excerpt).
 *
 * <p>Deleted-doc path per ADR-14 §11: if the {@code docs.documents} row is
 * gone, returns {@link SessionService.CitationResolver.Resolved#deleted()}
 * regardless of whether the chunk row still exists (M3 cascades chunk
 * deletion on {@code docs.document.deleted}).
 *
 * <p>Excerpt is the first 160 characters of the chunk text per spec §7.3.
 */
@Component
public class CrossSchemaCitationResolverAdapter implements SessionService.CitationResolver {

    private static final int EXCERPT_CHARS = 160;

    private final JdbcTemplate jdbc;

    public CrossSchemaCitationResolverAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Resolved resolve(DocumentId documentId, int chunkIndex) {
        try {
            return jdbc.queryForObject(
                    "SELECT d.title AS title, c.text AS text "
                            + "FROM docs.documents d "
                            + "LEFT JOIN docs.document_chunks c "
                            + "  ON c.document_id = d.id AND c.chunk_index = ? "
                            + "WHERE d.id = ?",
                    (rs, n) -> {
                        String title = rs.getString("title");
                        String text = rs.getString("text");
                        String excerpt = text == null
                                ? ""
                                : (text.length() > EXCERPT_CHARS
                                        ? text.substring(0, EXCERPT_CHARS)
                                        : text);
                        return Resolved.present(title, excerpt);
                    },
                    chunkIndex, documentId.value());
        } catch (EmptyResultDataAccessException e) {
            return Resolved.markDeleted();
        }
    }
}
