#!/usr/bin/env sh
# Production-style Kafka topic provisioning for the playground stack.
#
# Per ADR-03 §"Default topic settings": partitions=3, replication=1 (dev),
# retention=7d for business topics. ADR-13 §G.1 supersedes the original
# 1d DLQ retention with 14d for the docs.*.dlq family (rag-ingestion's
# error-handler DLQ targets — operator triage window).
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
RETENTION_BUSINESS_MS=$((7 * 24 * 60 * 60 * 1000))    # 7 days
RETENTION_DLQ_MS=$((14 * 24 * 60 * 60 * 1000))        # 14 days (ADR-13 §G.1 amendment to ADR-03)

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

# --- M6.1 (docs BC) — per ADR-12 §A12.5 + §A12.8 ---
# In-BC async-extraction dispatch topic + its DLQ. Producer + consumer both
# live in docs-api (Spring Modulith outbox bridges to Kafka so the worker
# decouples from the upload request thread).
create_topic "docs.document.extraction-requested"      "${RETENTION_BUSINESS_MS}"
create_topic "docs.document.extraction-requested.dlq"  "${RETENTION_DLQ_MS}"

# --- M3 (rag-ingestion BC) — per ADR-13 §E + §G.1 ---
# Business event published when ingestion completes (consumed by future M4, M5).
create_topic "rag.document.ingested"                     "${RETENTION_BUSINESS_MS}"

# DLQs for the three docs.* topics rag-ingestion consumes (ADR-13 §8). The
# DLQ owner is rag-ingestion (its DefaultErrorHandler.recover() re-publishes
# the failed record). Retention is 14d (ADR-13 §G.1) for operator triage.
create_topic "docs.document.uploaded.dlq"                "${RETENTION_DLQ_MS}"
create_topic "docs.document.visibility-changed.dlq"      "${RETENTION_DLQ_MS}"
create_topic "docs.document.deleted.dlq"                 "${RETENTION_DLQ_MS}"

echo "[init-topics] all topics ensured."
