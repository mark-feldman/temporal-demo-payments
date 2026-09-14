#!/usr/bin/env bash
# One command. Temporal and the backend run on the host; only Caddy, Prometheus and
# Grafana are containers. That is what makes the kill-the-app moment a plain kill -9.
set -uo pipefail
cd "$(dirname "$0")/.."
LOG=/tmp/payout-demo-backend.log

port_busy() { lsof -iTCP:"$1" -sTCP:LISTEN -n -P >/dev/null 2>&1; }

# 8000 and 7233 are Temporal's, and they are the ones that actually bite: the metrics port
# is still in TIME_WAIT for a few seconds after `make stop`, and the dev server exits with
# "can't set metrics port 8000" rather than anything about restarting too quickly. They were
# missing from this list, so the failure looked like a broken install.
for p in 8080 8081 9090 3000 8000 7233; do
  if port_busy "$p"; then
    echo "Port $p is already in use. Run 'make stop' first, or free it:"
    lsof -iTCP:"$p" -sTCP:LISTEN -n -P | tail -n +2 | sed 's/^/  /'
    exit 1
  fi
done

# ASSERT the pinned CLI, do not just hope for it. `make preflight` checks this, but nothing
# forces anyone to run preflight -- and the CLI is what decides the bundled Server and Web UI
# versions, so the wrong one on PATH silently changes the demo underneath you. It has happened
# twice: two CLIs installed, PATH order picked v1.6.1, and the UI came up as 2.45.3 instead of
# the pinned 2.50.1.
# Read from .mise.toml rather than restated here. The pin is the source of truth; a second
# copy of the number is just a third thing to forget to update.
EXPECTED_CLI=$(sed -n 's|.*temporalio/cli.*version = "v\{0,1\}\([0-9.]*\)".*|\1|p' .mise.toml)
[[ -n "$EXPECTED_CLI" ]] || { echo "Could not read the temporal CLI pin from .mise.toml"; exit 1; }
ACTUAL_CLI=$(temporal --version 2>/dev/null)
case "$ACTUAL_CLI" in
  *"$EXPECTED_CLI"*) ;;
  "") echo "No temporal CLI on PATH. Run 'mise install'."; exit 1 ;;
  *)  echo "Wrong temporal CLI: $ACTUAL_CLI"
      echo "  .mise.toml pins $EXPECTED_CLI, and the CLI decides the bundled Server and Web UI."
      echo "  Fix PATH order or run 'mise install', then 'make preflight' to confirm."
      exit 1 ;;
esac

echo "1/4  Temporal dev server..."
if ! temporal operator cluster health >/dev/null 2>&1; then
  nohup bash scripts/start-temporal.sh >/tmp/payout-demo-temporal.log 2>&1 &
  for _ in $(seq 1 30); do temporal operator cluster health >/dev/null 2>&1 && break; sleep 1; done
fi
temporal operator cluster health >/dev/null 2>&1 || { echo "  Temporal failed to start"; exit 1; }
echo "     SERVING on :7233, UI :8233, metrics :8000"

echo "2/4  Caddy, Prometheus, Grafana..."
docker compose up -d >/dev/null 2>&1
echo "     up"

echo "3/4  Backend (API) + worker process..."
# The API spawns worker JVMs from this jar, so it has to exist before boot.
( cd backend/kotlin && JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-21.0.11+10.0.LTS" \
  ./gradlew bootJar -q --console=plain ) || { echo "  jar build failed"; exit 1; }
# Close stdin and redirect both streams, or the Gradle daemon keeps this script's pipe
# open and `make start` never returns. (No setsid on macOS; the subshell + nohup is enough.)
( cd backend/kotlin && JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-21.0.11+10.0.LTS" \
  nohup ./gradlew bootRun --console=plain -q >"$LOG" 2>&1 </dev/null & ) >/dev/null 2>&1
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
