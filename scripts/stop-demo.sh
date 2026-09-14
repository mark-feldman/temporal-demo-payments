#!/usr/bin/env bash
# Stops everything and waits for the host ports to be released. `pkill` signals and returns,
# so without the wait `make stop && make start` races the JVM's shutdown and start-demo.sh
# refuses on a port that is still in LISTEN.
set -uo pipefail
cd "$(dirname "$0")/.."

# The API and the worker JVMs it supervises (8091-8100), and the dev server's gRPC, UI and
# metrics ports. Caddy, Prometheus and Grafana release 8080/9090/3000 when compose down returns.
HOST_PORTS="8081 8091 8092 8093 8094 8095 8096 8097 8098 8099 8100 7233 8233 8000"

# One lsof for the whole set, not one per port: fourteen calls cost over a second, which made
# an iteration-counted timeout mean whatever the fork cost happened to be. Prints the ports
# still in LISTEN, one per line.
listening() {
  local args="" p
  for p in $HOST_PORTS; do args="$args -iTCP:$p"; done
  lsof -nP -sTCP:LISTEN $args -Fn 2>/dev/null | sed -n 's/^n.*:\([0-9]\{1,\}\)$/\1/p' | sort -un
}

wait_for_ports() {
  local deadline=$((SECONDS + 20)) busy
  while :; do
    busy="$(listening | tr '\n' ' ')"
    [[ -z "${busy// /}" ]] && return 0
    if [[ $SECONDS -ge $deadline ]]; then
      echo "  still listening after 20s: $busy"
      echo "  'make start' will refuse until these are free."
      return 1
    fi
    sleep 0.5
  done
}

echo "Stopping backend..."; pkill -f 'payout-demo.*\.jar|PayoutsApplicationKt' 2>/dev/null || true
echo "Stopping containers..."; docker compose down 2>/dev/null || true
echo "Stopping Temporal..."; pkill -f 'temporal server start-dev' 2>/dev/null || true
wait_for_ports || exit 1
echo "Stopped."
