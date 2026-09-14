#!/usr/bin/env bash
# Run extra worker processes alongside the main one.
#
# Three workers means three JVMs: a WorkerFactory keys its workers by task queue and
# returns the existing one for a repeated call, so extra instances cannot come from a
# single process. Each instance here is the same jar with the same worker; the only
# argument that differs is the HTTP port, because :8081 is already taken by the primary.
#
#   scripts/scale-workers.sh up 2      start two extra workers  (3 total with the primary)
#   scripts/scale-workers.sh down      stop the extras, leave the primary running
#   scripts/scale-workers.sh status    who is polling the task queue
set -uo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-21.0.11+10.0.LTS}"
JAR=$(ls backend/kotlin/build/libs/payout-demo-*.jar 2>/dev/null | grep -v plain | head -1)
BASE_PORT=8091
MARKER=payout-demo-extra-worker

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

build_if_needed() {
  if [[ -z "$JAR" ]]; then
    echo "Building the jar first..."
    ( cd backend/kotlin && JAVA_HOME="$JAVA_HOME" ./gradlew bootJar -q --console=plain ) || exit 1
    JAR=$(ls backend/kotlin/build/libs/payout-demo-*.jar 2>/dev/null | grep -v plain | head -1)
  fi
}

case "${1:-status}" in
  up)
    n="${2:-2}"
    build_if_needed
    for i in $(seq 1 "$n"); do
      port=$((BASE_PORT + i - 1))
      if lsof -iTCP:"$port" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
        echo "  worker $i: port $port already in use, skipping"
        continue
      fi
      JAVA_HOME="$JAVA_HOME" nohup "$JAVA_HOME/bin/java" \
        -D$MARKER=$i -jar "$JAR" --server.port="$port" \
        >"/tmp/$MARKER-$i.log" 2>&1 </dev/null &
      echo "  worker $i: starting on :$port  (log /tmp/$MARKER-$i.log)"
    done
    echo "Waiting for them to register..."
    sleep 12
    "$0" status
    ;;
  down)
    pkill -f "$MARKER" 2>/dev/null && echo "Extra workers stopped." || echo "No extra workers running."
    echo "The primary on :8081 is untouched."
    ;;
  status)
    echo "Workers polling the 'payouts' task queue:"
    temporal task-queue describe --task-queue payouts 2>/dev/null \
      | sed -n '1,12p' | sed 's/^/  /' \
      || echo "  (temporal CLI unavailable or server not running)"
    echo
    echo "Processes:"
    pgrep -fl "$MARKER" 2>/dev/null | sed 's/^/  extra: /' || true
    pgrep -f 'PayoutsApplication|payout-demo.*\.jar' 2>/dev/null | head -1 >/dev/null \
      && echo "  primary: :8081" || echo "  primary: not running"
    ;;
  *) usage ;;
esac
