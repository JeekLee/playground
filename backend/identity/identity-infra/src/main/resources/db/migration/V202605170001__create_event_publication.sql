-- ADR-10 §8 (transactional outbox) — Spring Modulith Events JPA outbox table.
-- We use the JPA backend (`spring-modulith-events-jpa`), which does NOT bundle
-- a DDL script and relies on either hbm2ddl=update or hand-managed schema.
-- Our `spring.jpa.hibernate.ddl-auto=validate` requires the latter, so the
-- table is owned by Flyway here.
--
-- Schema mirrors the JpaEventPublication entity in spring-modulith 1.2.5:
--   id, listener_id, event_type, serialized_event, publication_date, completion_date.
-- Indices on serialized_event (hash) and completion_date come from the
-- Modulith reference schema and accelerate the "incomplete publications"
-- query the outbox externalizer runs on startup.

SET search_path = identity, public;

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
