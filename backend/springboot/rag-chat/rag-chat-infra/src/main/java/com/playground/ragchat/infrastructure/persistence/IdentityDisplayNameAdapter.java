package com.playground.ragchat.infrastructure.persistence;

import com.playground.ragchat.application.port.OwnerDisplayNamePort;
import com.playground.ragchat.domain.model.id.UserId;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cross-schema SELECT into {@code identity.users} per ADR-14 §3.
 * Single-row, single-call-per-page-load — used to populate the chat header.
 * Empty result degrades to the email prefix at the frontend.
 */
@Component
public class IdentityDisplayNameAdapter implements OwnerDisplayNamePort {

    private final JdbcTemplate jdbc;

    public IdentityDisplayNameAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DisplayName> lookup(UserId userId) {
        try {
            DisplayName name = jdbc.queryForObject(
                    "SELECT display_name, avatar_url FROM identity.users WHERE id = ?",
                    (rs, n) -> new DisplayName(rs.getString("display_name"), rs.getString("avatar_url")),
                    userId.value());
            return Optional.ofNullable(name);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
