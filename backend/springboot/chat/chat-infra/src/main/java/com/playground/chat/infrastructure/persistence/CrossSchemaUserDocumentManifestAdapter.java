package com.playground.chat.infrastructure.persistence;

import com.playground.chat.application.port.UserDocumentManifestPort;
import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.id.UserId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cross-schema SELECT into {@code docs.documents} for the caller's document
 * manifest per ADR-14 §3 (same posture as {@link IdentityDisplayNameAdapter}
 * and {@link CrossSchemaCitationResolverAdapter}). Ordered by {@code created_at
 * ASC} so the 1-indexed ordinal matches upload order ("두 번째 문서" = the
 * document uploaded second), capped at {@code limit}.
 */
@Component
public class CrossSchemaUserDocumentManifestAdapter implements UserDocumentManifestPort {

    private final JdbcTemplate jdbc;

    public CrossSchemaUserDocumentManifestAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UserDocumentRef> recentForUser(UserId userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query(
                "SELECT id, title, mime_type, extraction_status "
                        + "FROM docs.documents "
                        + "WHERE user_id = ? "
                        + "ORDER BY created_at ASC "
                        + "LIMIT ?",
                (rs, rowNum) -> new UserDocumentRef(
                        rowNum + 1,
                        (java.util.UUID) rs.getObject("id"),
                        rs.getString("title"),
                        rs.getString("mime_type"),
                        rs.getString("extraction_status")),
                userId.value(), limit);
    }
}
