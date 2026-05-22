-- ADR-12 §1 (inheriting ADR-10 §8) — Spring Modulith Events JPA outbox table.
-- The schema mirrors the JpaEventPublication entity in spring-modulith 1.2.5.
-- We use the JPA backend (`spring-modulith-events-jpa`), which does NOT bundle
-- a DDL script and relies on either hbm2ddl=update or hand-managed schema.
-- Our `spring.jpa.hibernate.ddl-auto=validate` requires the latter, so the
-- table is owned by Flyway here.
--
-- S1 does not externalize any events yet (no DocumentUploaded / VisibilityChanged
-- / DocumentDeleted producers — those land in M2 S2 per M2 spec §5), but the
-- starter is on the classpath via the `bc-infra` convention plugin and
-- Hibernate scans the JpaEventPublication entity at boot. Pre-creating the
-- table keeps the boot path consistent with identity-infra's pattern and
-- avoids a separate migration in the S2 PR.
--
-- Indices come from the Modulith reference schema and accelerate the
-- "incomplete publications" query the outbox externalizer runs on startup.

SET search_path = docs, public;

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
