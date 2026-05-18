-- ADR-13 §D (inheriting ADR-10 §8 / ADR-12 §1) — Spring Modulith Events JPA
-- outbox table for the M3 publish side (rag.document.ingested).
-- Schema mirrors the JpaEventPublication entity in spring-modulith 1.2.5.
-- We use the JPA backend (`spring-modulith-events-jpa`), which does NOT
-- bundle a DDL script and relies on either hbm2ddl=update or hand-managed
-- schema. The Spring Boot app uses `spring.modulith.events.jdbc.schema-
-- initialization.enabled=true`, but mirroring docs's V202605180002 pattern
-- keeps the schema authored by Flyway for clarity + grep-ability.

SET search_path = rag, public;

CREATE TABLE IF NOT EXISTS event_publication (
    id                UUID                       NOT NULL,
    listener_id       TEXT                       NOT NULL,
    event_type        TEXT                       NOT NULL,
    serialized_event  TEXT                       NOT NULL,
    publication_date  TIMESTAMP WITH TIME ZONE   NOT NULL,
    completion_date   TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);

CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON event_publication (completion_date);
