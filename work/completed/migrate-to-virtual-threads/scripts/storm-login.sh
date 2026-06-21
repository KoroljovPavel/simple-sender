#!/usr/bin/env bash
set -u

COUNT="${1:-20}"
JAR="${JAR:-/tmp/vt-probe-jar.txt}"
LOG="${LOG:-/Users/pavlokorolov/IdeaProjects/simple-sender/work/migrate-to-virtual-threads/logs/working/task-11/storm-driver.log}"
BASE="${BASE:-http://localhost:8080}"

HALF=$(( COUNT / 2 ))
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR")
echo "=== LOGIN STORM ${COUNT}x mix (dummy-hash + Lettuce INCR) at $(date -Iseconds) ===" >> "$LOG"

for i in $(seq 1 "$HALF"); do
  curl -s -b "$JAR" -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
    -X POST \
    -d "{\"email\":\"unknown-$i@example.com\",\"password\":\"WrongPw1!\",\"rememberMe\":false}" \
    -w "%{http_code}\n" -o /dev/null \
    "$BASE/api/auth/login" &
done

for i in $(seq 1 "$HALF"); do
  curl -s -b "$JAR" -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
    -X POST \
    -d "{\"email\":\"unknown-other-$i@example.com\",\"password\":\"WrongPw1!\",\"rememberMe\":true}" \
    -w "%{http_code}\n" -o /dev/null \
    "$BASE/api/auth/login" &
done
wait
echo "=== LOGIN STORM DONE at $(date -Iseconds) ===" >> "$LOG"
