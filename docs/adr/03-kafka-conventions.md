# ADR-03: Kafka — KRaft Mode, Topic Naming, Event Envelope

## Status
Accepted

## Context
Cross-BC communication is event-driven. We need:
- Stable topic names that survive renames as new BCs arrive.
- A single, versioned envelope so consumers can evolve without breaking
  producers (and vice versa).
- A Kafka deployment that is light enough to run in `docker-compose` on a
  developer laptop.

Alternatives considered:
- Zookeeper-managed Kafka — rejected: KRaft is GA since Kafka 3.3 and removes a
  whole container.
- Schema Registry + Avro — rejected for now (overkill for a personal project);
  envelope is JSON with explicit `schemaVersion` field, upgradeable later.

## Decision

### Broker
- Image: **`apache/kafka:3.8.0`** (KRaft mode, official Apache image).
- Single broker, single controller in dev (combined mode).
- Host-exposed listener: **`19092`** (PLAINTEXT). Compose-internal listener:
  `kafka:9092`.
- Auto-create topics: **disabled** in non-dev profiles. In dev, enabled for
  convenience; production-style topic provisioning script lives at
  `infra/kafka/init-topics.sh`.

### Topic naming
Pattern: **`<bc>.<aggregate>.<event-past-tense>`** (all lowercase, dot-separated).

Examples:
- `identity.user.registered`
- `docs.document.uploaded`
- `docs.document.deleted`
- `rag.chunk.embedded`
- `metrics.snapshot.captured`

Rules:
- `<bc>` matches the module name from ADR-01 (e.g., `rag` for both `rag-ingestion`
  and `rag-chat` since they share the bounded context); use `rag-ingestion`
  prefix only for internal topics that other BCs must not consume.
- `<event-past-tense>` describes what *already happened* (`uploaded`, never
  `upload`).
- Dead-letter topics: append `.dlq` (e.g., `docs.document.uploaded.dlq`).

### Default topic settings (dev)
- Partitions: **3**
- Replication factor: **1** (single broker in dev; production sets 3)
- Retention: 7 days for business events, 1 day for DLQ
- Cleanup policy: `delete` (compaction reserved for future state-carrying topics)

### Event envelope (JSON)

Every message published to any business topic uses this envelope. The envelope
type lives in `shared-kernel` as a Java `record`:

```java
package dev.jeeklee.playground.shared.event;

public record EventEnvelope<T>(
    String  eventId,         // UUID v4
    String  eventType,       // matches the topic name, e.g., "docs.document.uploaded"
    Instant occurredAt,      // ISO-8601, UTC
    String  aggregateId,     // routing key, also the Kafka message key
    int     schemaVersion,   // payload schema version, starts at 1
    T       payload          // typed business payload
) {}
```

JSON shape on the wire:

```json
{
  "eventId": "9f2c1b3e-7a44-4f0c-9c2a-1b3e7a444f0c",
  "eventType": "docs.document.uploaded",
  "occurredAt": "2026-05-15T08:42:11.123Z",
  "aggregateId": "doc_01HXYZABCD",
  "schemaVersion": 1,
  "payload": { "...": "BC-specific" }
}
```

### Producer settings
- `acks=all`
- `enable.idempotence=true`
- Key = `aggregateId` (string)
- Value serializer: JSON via Jackson, configured in `shared-kernel`
- `linger.ms=10`, `compression.type=lz4`

### Consumer settings
- `enable.auto.commit=false` — manual offset commit after successful handling.
- `isolation.level=read_committed`.
- Container ack mode: `MANUAL_IMMEDIATE` (Spring Kafka default for our use).
- Retries: in-process retry with exponential backoff, then route to `<topic>.dlq`.
- Consumer group: `<service-name>` (e.g., `rag-ingestion`).

### Schema evolution
- Bump `schemaVersion` when payload changes shape. Consumers branch on it.
- Removing a field is a major change requiring a new event type
  (`docs.document.uploaded.v2` topic) — not a `schemaVersion` bump.

## Consequences
- Positive: One container fewer (no Zookeeper).
- Positive: Uniform envelope means cross-cutting concerns (idempotency,
  observability, replay) can be implemented once in `shared-kernel`.
- Negative: JSON envelope is verbose vs. Avro/Protobuf and lacks registry-backed
  compatibility checks — acceptable trade-off; revisit at M6+.
- Negative: Single-broker dev deployment cannot exercise true rebalancing
  scenarios; integration tests use Testcontainers to add a second broker as
  needed.
