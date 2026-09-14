#!/usr/bin/env bash
# Worker fleet control. The API supervises the worker JVMs, so this is a thin client
# over /demo-api/workers -- the same endpoints the UI button uses.
#
#   scripts/scale-workers.sh up 3      run three workers (the cap is 10)
#   scripts/scale-workers.sh kill      SIGKILL the most recent one
#   scripts/scale-workers.sh status
set -uo pipefail
BASE="${BASE:-http://localhost:8081}/demo-api/workers"
show() { curl -s "$BASE" | python3 -c '
import sys, json
d = json.load(sys.stdin)
print("  %d of %d worker(s) polling the payouts task queue" % (d["running"], d["max"]))
for w in d["workers"]:
    print("    worker %d  pid %d  :%d  %s" % (
        w["id"], w["pid"], w["port"], "alive" if w["alive"] else "dead"))
' 2>/dev/null || echo "  API not reachable on :8081"; }

case "${1:-status}" in
  up)     curl -s -X POST "$BASE/scale?count=${2:-2}" >/dev/null; sleep 8; show ;;
  kill)   curl -s -X POST "$BASE/kill" >/dev/null; sleep 1; show ;;
  down)   curl -s -X POST "$BASE/kill-all" >/dev/null; sleep 1; show ;;
  status) show ;;
  *)      sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
