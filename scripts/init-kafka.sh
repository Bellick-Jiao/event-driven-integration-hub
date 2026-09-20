#!/usr/bin/env bash
# 创建 Kafka topics（KRaft 单节点）
# 用法：docker compose up -d 之后执行 ./scripts/init-kafka.sh
set -euo pipefail

KC="/opt/kafka/bin/kafka-topics.sh"
BOOTSTRAP="localhost:9092"

echo "==> 创建主 topic：customer.profile.events（compact，3 分区）"
docker compose exec kafka "$KC" --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists \
  --topic customer.profile.events \
  --partitions 3 --replication-factor 1 \
  --config cleanup.policy=compact

echo "==> 创建 DLQ topic：customer.profile.events.dlq"
docker compose exec kafka "$KC" --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists \
  --topic customer.profile.events.dlq \
  --partitions 3 --replication-factor 1

echo "==> 当前 topics："
docker compose exec kafka "$KC" --bootstrap-server "$BOOTSTRAP" --list
