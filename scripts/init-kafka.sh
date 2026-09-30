#!/usr/bin/env bash
# Create the Kafka topics (single-node KRaft).
# Usage: after `docker compose up -d`, run ./scripts/init-kafka.sh
# Git Bash on Windows: MSYS_NO_PATHCONV=1 ./scripts/init-kafka.sh
set -euo pipefail

KC="/opt/kafka/bin/kafka-topics.sh"
BOOTSTRAP="localhost:9092"

echo "==> Create main topic: customer.profile.events (compacted, 3 partitions)"
docker compose exec kafka "$KC" --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists \
  --topic customer.profile.events \
  --partitions 3 --replication-factor 1 \
  --config cleanup.policy=compact

echo "==> Create DLQ topic: customer.profile.events.dlq"
docker compose exec kafka "$KC" --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists \
  --topic customer.profile.events.dlq \
  --partitions 3 --replication-factor 1

echo "==> Current topics:"
docker compose exec kafka "$KC" --bootstrap-server "$BOOTSTRAP" --list
