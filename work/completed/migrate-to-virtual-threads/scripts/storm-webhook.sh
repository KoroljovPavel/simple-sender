#!/usr/bin/env bash
set -u

COUNT="${1:-20}"
PROJECT_ID="${PROJECT_ID:-000000000000000000000001}"
LOG="${LOG:-/Users/pavlokorolov/IdeaProjects/simple-sender/work/migrate-to-virtual-threads/logs/working/task-11/storm-driver.log}"
BASE="${BASE:-http://localhost:8080}"

echo "=== WEBHOOK STORM ${COUNT}x at $(date -Iseconds) ===" >> "$LOG"

for i in $(seq 1 "$COUNT"); do
  UPDATE_ID=$(( RANDOM * 1000 + i ))
  curl -s -H 'Content-Type: application/json' \
    -H "X-Telegram-Bot-Api-Secret-Token: stormSecret$i" \
    -X POST \
    -d "{\"update_id\": $UPDATE_ID, \"message\":{\"message_id\":$i,\"chat\":{\"id\":12345},\"text\":\"vt-storm-$i\"}}" \
    -w "%{http_code}\n" -o /dev/null \
    "$BASE/webhooks/telegram/$PROJECT_ID" &
done
wait
echo "=== WEBHOOK STORM DONE at $(date -Iseconds) ===" >> "$LOG"
