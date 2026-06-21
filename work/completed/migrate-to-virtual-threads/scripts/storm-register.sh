#!/usr/bin/env bash
set -u

COUNT="${1:-20}"
JAR="${JAR:-/tmp/vt-probe-jar.txt}"
LOG="${LOG:-/Users/pavlokorolov/IdeaProjects/simple-sender/work/migrate-to-virtual-threads/logs/working/task-11/storm-driver.log}"
BASE="${BASE:-http://localhost:8080}"

XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR")
echo "=== REGISTER STORM ${COUNT}x at $(date -Iseconds) ===" >> "$LOG"

for i in $(seq 1 "$COUNT"); do
  curl -s -b "$JAR" -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
    -X POST \
    -d "{\"email\":\"vt-storm-$(date +%s)-$i@example.com\",\"password\":\"Password123!\"}" \
    -w "%{http_code}\n" -o /dev/null \
    "$BASE/api/auth/register" &
done
wait
echo "=== REGISTER STORM DONE at $(date -Iseconds) ===" >> "$LOG"
