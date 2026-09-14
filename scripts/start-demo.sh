#!/usr/bin/env bash
# One command. Temporal and the backend run on the host; only Caddy, Prometheus and
# Grafana are containers. That is what makes the kill-the-app moment a plain kill -9.
set -uo pipefail
cd "$(dirname "$0")/.."
LOG=/tmp/payout-demo-backend.log

port_busy() { lsof -iTCP:"$1" -sTCP:LISTEN -n -P >/dev/null 2>&1; }

for p in 8080 8081 9090 3000; do
  if port_busy "$p"; then
    echo "Port $p is already in use. Run 'make stop' first, or free it:"
    lsof -iTCP:"$p" -sTCP:LISTEN -n -P | tail -n +2 | sed 's/^/  /'
    exit 1
  fi
done

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

echo "3/4  Backend (Kotlin + Spring Boot + worker)..."
# Close stdin and redirect both streams, or the Gradle daemon keeps this script's pipe
# open and `make start` never returns. (No setsid on macOS; the subshell + nohup is enough.)
( cd backend/kotlin && JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-21.0.11+10.0.LTS" \
  nohup ./gradlew bootRun --console=plain -q >"$LOG" 2>&1 </dev/null & ) >/dev/null 2>&1
for _ in $(seq 1 90); do curl -sf http://localhost:8081/demo-api/health >/dev/null 2>&1 && break; sleep 2; done
curl -sf http://localhost:8081/demo-api/health >/dev/null 2>&1 \
  || { echo "  Backend failed to start. Last lines of $LOG:"; tail -20 "$LOG"; exit 1; }
echo "     UP on :8081"

echo "4/4  Ready."
echo
echo "     Demo          http://localhost:8080"
echo "     Temporal UI   http://localhost:8233   (also embedded in the right pane)"
echo "     Grafana       http://localhost:3000"
echo "     Prometheus    http://localhost:9090"
echo
echo "     Backend log:  tail -f $LOG"
