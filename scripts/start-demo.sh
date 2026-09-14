#!/usr/bin/env bash
# Temporal and the backend run on the host; only Caddy, Prometheus and Grafana are
# containers, so stopping the backend is a plain kill -9.
set -uo pipefail
cd "$(dirname "$0")/.."
LOG=/tmp/payout-demo-backend.log
source "$(dirname "$0")/temporal-bin.sh"
TEMPORAL="$(temporal_bin)" || exit 1
# Gradle's toolchain is fixed at 21, and the Gradle daemon and the worker JVMs it forks
# inherit this.
JAVA_HOME="$(bash scripts/java-home.sh)" || exit 1
export JAVA_HOME

port_busy() { lsof -iTCP:"$1" -sTCP:LISTEN -n -P >/dev/null 2>&1; }

# 8000 and 7233 are Temporal's. The metrics port stays in TIME_WAIT for a few seconds after
# `make stop`, and the dev server then exits with "can't set metrics port 8000".
for p in 8080 8081 9090 3000 8000 7233; do
  if port_busy "$p"; then
    echo "Port $p is already in use. Run 'make stop' first, or free it:"
    lsof -iTCP:"$p" -sTCP:LISTEN -n -P | tail -n +2 | sed 's/^/  /'
    exit 1
  fi
done

# The CLI decides the bundled Server and Web UI versions, so the pin is asserted here rather
# than relied on. `make preflight` checks the same thing.
# Read from .mise.toml, which holds the pin, rather than restated here.
EXPECTED_CLI=$(sed -n 's|.*temporalio/cli.*version = "v\{0,1\}\([0-9.]*\)".*|\1|p' .mise.toml)
[[ -n "$EXPECTED_CLI" ]] || { echo "Could not read the temporal CLI pin from .mise.toml"; exit 1; }
ACTUAL_CLI=$("$TEMPORAL" --version 2>/dev/null)
case "$ACTUAL_CLI" in
  *"$EXPECTED_CLI"*) ;;
  "") echo "No temporal CLI on PATH. Run 'mise install'."; exit 1 ;;
  *)  echo "Wrong temporal CLI: $ACTUAL_CLI"
      echo "  resolved to: $TEMPORAL"
      echo "  .mise.toml pins $EXPECTED_CLI, and the CLI decides the bundled Server and Web UI."
      echo "  Fix PATH order or run 'mise install', then 'make preflight' to confirm."
      exit 1 ;;
esac

echo "1/4  Temporal dev server..."
if ! "$TEMPORAL" operator cluster health >/dev/null 2>&1; then
  nohup bash scripts/start-temporal.sh >/tmp/payout-demo-temporal.log 2>&1 &
  for _ in $(seq 1 30); do temporal operator cluster health >/dev/null 2>&1 && break; sleep 1; done
fi
"$TEMPORAL" operator cluster health >/dev/null 2>&1 || { echo "  Temporal failed to start"; exit 1; }
echo "     SERVING on :7233, UI :8233, metrics :8000"

echo "2/4  Caddy, Prometheus, Grafana..."
docker compose up -d >/dev/null 2>&1
echo "     up"

echo "3/4  Backend (API) + worker process..."
# The API spawns worker JVMs from this jar, so it has to exist before boot.
( cd backend/kotlin && ./gradlew bootJar -q --console=plain ) || { echo "  jar build failed"; exit 1; }
# Close stdin and redirect both streams, or the Gradle daemon keeps this script's pipe
# open and `make start` never returns. (No setsid on macOS; the subshell + nohup is enough.)
( cd backend/kotlin && nohup ./gradlew bootRun --console=plain -q >"$LOG" 2>&1 </dev/null & ) >/dev/null 2>&1
for _ in $(seq 1 90); do curl -sf http://localhost:8081/demo-api/health >/dev/null 2>&1 && break; sleep 2; done
curl -sf http://localhost:8081/demo-api/health >/dev/null 2>&1 \
  || { echo "  Backend failed to start. Last lines of $LOG:"; tail -20 "$LOG"; exit 1; }
echo "     API UP on :8081"
for _ in $(seq 1 20); do
  n=$(curl -s http://localhost:8081/demo-api/workers 2>/dev/null | python3 -c 'import sys,json;print(json.load(sys.stdin)["running"])' 2>/dev/null || echo 0)
  [[ "$n" -ge 1 ]] && break; sleep 1
done
echo "     $n worker process(es) polling 'payouts'"

echo "4/4  Ready."
echo
echo "     Demo          http://localhost:8080"
echo "     Temporal UI   http://localhost:8233   (also embedded in the right pane)"
echo "     Grafana       http://localhost:3000"
echo "     Prometheus    http://localhost:9090"
echo
echo "     Backend log:  tail -f $LOG"
