#!/usr/bin/env sh
# Production-style Kafka topic provisioning for the playground stack.
#
# Per ADR-03 §"Default topic settings": partitions=3, replication=1 (dev),
# retention=7d for business topics, 1d for DLQs (M3's ADR-13 amendment
# bumps DLQ retention to 14d for the docs.*.dlq family; not provisioned
# until M3 ships).
#
# The script is idempotent — `kafka-topics.sh --create --if-not-exists`
# is a no-op for topics that already exist. ADR-03 keeps auto-create
# enabled in dev, so this script is a belt-and-braces for ensuring the
# partition count matches the ADR default (auto-create uses the broker's
# `num.partitions=1` default otherwise).
#
# Invocation: run from inside a one-shot compose service against a healthy
# `kafka-playground` broker on the compose-internal listener
# (`kafka-playground:9092`). See `kafka-init` service in
# `infra/docker-compose.yml`.

set -eu

BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVERS:-kafka-playground:9092}"
KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin/kafka-topics.sh}"

# Default settings per ADR-03.
PARTITIONS_DEFAULT=3
REPLICATION_DEFAULT=1
RETENTION_BUSINESS_MS=$((7 * 24 * 60 * 60 * 1000))   # 7 days

create_topic() {
  topic_name="$1"
  retention_ms="$2"
  echo "[init-topics] ensuring topic: ${topic_name} (retention=${retention_ms}ms)"
  "${KAFKA_BIN}" \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --create \
    --if-not-exists \
    --topic "${topic_name}" \
    --partitions "${PARTITIONS_DEFAULT}" \
    --replication-factor "${REPLICATION_DEFAULT}" \
    --config "retention.ms=${retention_ms}" \
    --config "cleanup.policy=delete"
}

# --- M1 (identity BC) — per ADR-10 §8 outbox topics ---
create_topic "identity.user.registered"        "${RETENTION_BUSINESS_MS}"
create_topic "identity.user.profile-updated"   "${RETENTION_BUSINESS_MS}"

# --- M2 (docs BC) — per ADR-12 §5 + design spec §5 ---
create_topic "docs.document.uploaded"            "${RETENTION_BUSINESS_MS}"
create_topic "docs.document.visibility-changed"  "${RETENTION_BUSINESS_MS}"
create_topic "docs.document.deleted"             "${RETENTION_BUSINESS_MS}"

echo "[init-topics] all topics ensured."
