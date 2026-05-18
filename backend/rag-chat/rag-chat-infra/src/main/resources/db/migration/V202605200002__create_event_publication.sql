-- M4 (rag-chat BC) Spring Modulith Events JPA outbox table.
--
-- M4 has no Kafka surface and publishes no events (ADR-14 §1 + §A
-- "Notable absences"). The bc-infra convention plugin still imports
-- spring-modulith-starter-jpa transitively, which registers the
-- JpaEventPublication entity at JPA startup. With
-- `spring.jpa.hibernate.ddl-auto=validate` the missing table fails
-- application bootstrap with:
--
--   SchemaManagementException: missing table [event_publication]
--
-- Adding the table as an empty no-op Flyway migration satisfies the
-- validator without changing M4's behavior. If a future M4.x feature
-- needs to publish events, the table is already there.
--
-- Schema matches V202605200002 in rag-ingestion (ADR-13 §D) and the
-- M2 docs V202605180002 migration — same JpaEventPublication entity
-- in spring-modulith 1.2.5.

SET search_path = chat, public;

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
