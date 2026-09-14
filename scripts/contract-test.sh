#!/usr/bin/env bash
# The demo backend contract, executable. Any future SDK implementation must pass this
# same script -- that is what makes it a contract rather than an integration test.
set -uo pipefail
BASE="${BASE:-http://localhost:8081}/demo-api"
PASS=0; FAIL=0

ok()   { printf "  \033[32mPASS\033[0m  %s\n" "$1"; PASS=$((PASS+1)); }
bad()  { printf "  \033[31mFAIL\033[0m  %s — %s\n" "$1" "${2:-}"; FAIL=$((FAIL+1)); }
jqv()  { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }

start() { curl -s -X POST "$BASE/payouts" -H 'Content-Type: application/json' -d "$1"; }
status(){ curl -s "$BASE/payouts/$1/status"; }
signal(){ curl -s -X POST "$BASE/payouts/$1/$2" -H 'Content-Type: application/json' -d "$3" >/dev/null; }

# Poll until the workflow reaches one of the given states, or time out.
await() {
  local wid="$1" want="$2" secs="${3:-25}"
  for _ in $(seq 1 "$((secs*2))"); do
    local s; s=$(status "$wid" | jqv "['status']")
    [[ ",$want," == *",$s,"* ]] && { echo "$s"; return 0; }
    sleep 0.5
  done
  status "$wid" | jqv "['status']"; return 1
}

echo "Contract tests against $BASE"

# 1 -- health
H=$(curl -s "$BASE/health")
[[ "$(echo "$H" | jqv "['sdkLanguage']")" == "Kotlin" && "$(echo "$H" | jqv "['worker']")" == "UP" ]] \
  && ok "1 health reports SDK language, task queue, namespace, worker status" \
  || bad "1 health" "$H"

# 2 -- start and query
W=$(start '{"scenario":"ct-success","amountMinor":25000}' | jqv "['workflowId']")
S=$(await "$W" "AWAITING_BANK_CONFIRMATION" 20)
[[ "$S" == "AWAITING_BANK_CONFIRMATION" ]] && ok "2 payout starts and status is queryable" || bad "2 start/query" "$S"

# 4 -- bank status signal advances the workflow  (tested before 3, reusing W)
signal "$W" bank-status '{"status":"COMPLETED"}'
S=$(await "$W" "COMPLETED" 15)
[[ "$S" == "COMPLETED" ]] && ok "4 bank-status signal advances the workflow" || bad "4 bank signal" "$S"

# 3 -- approval signal advances the workflow
W=$(start '{"scenario":"ct-approval","amountMinor":250000}' | jqv "['workflowId']")
await "$W" "AWAITING_APPROVAL" 20 >/dev/null
signal "$W" approval '{"approved":true,"approver":"contract-test"}'
S=$(await "$W" "AWAITING_BANK_CONFIRMATION,COMPLETED" 20)
[[ "$S" != "AWAITING_APPROVAL" ]] && ok "3 approval signal advances the workflow" || bad "3 approval" "$S"
signal "$W" bank-status '{"status":"COMPLETED"}'

# 5 -- transient failure produces retries
W=$(start '{"scenario":"ct-retry","amountMinor":25000,"behavior":"FAIL_TRANSIENT","transientFailures":2}' | jqv "['workflowId']")
await "$W" "AWAITING_BANK_CONFIRMATION" 30 >/dev/null
A=$(status "$W" | jqv "['railAttempts']")
[[ "${A:-0}" -gt 1 ]] && ok "5 transient failure produces retries (attempt=$A)" || bad "5 retries" "attempt=$A"
signal "$W" bank-status '{"status":"COMPLETED"}'

# 6 -- permanent failure produces compensation
W=$(start '{"scenario":"ct-permanent","amountMinor":25000,"behavior":"FAIL_PERMANENT"}' | jqv "['workflowId']")
S=$(await "$W" "FAILED,CANCELLED" 30)
HIST=$(status "$W" | jqv "['history']")
[[ "$S" == "FAILED" && "$HIST" == *COMPENSAT* ]] \
  && ok "6 permanent failure triggers compensation" || bad "6 compensation" "$S / $HIST"

# 7 -- unknown bank status is represented AND DOES NOT COMPENSATE
W=$(start '{"scenario":"ct-unknown","amountMinor":25000,"behavior":"ACCEPTED_NO_CALLBACK"}' | jqv "['workflowId']")
await "$W" "AWAITING_BANK_CONFIRMATION" 20 >/dev/null
signal "$W" bank-status '{"status":"UNKNOWN"}'
S=$(await "$W" "UNKNOWN_BANK_STATUS" 20)
HIST=$(status "$W" | jqv "['history']")
if [[ "$S" == "UNKNOWN_BANK_STATUS" && "$HIST" != *COMPENSAT* ]]; then
  ok "7 unknown bank status represented and funds NOT released"
else
  bad "7 unknown must not compensate" "$S / $HIST"
fi

# 8 -- metrics endpoint
M=$(curl -s "$BASE/actuator/prometheus")
[[ "$M" == *payout_workflows_started_total* && "$M" == *temporal_* ]] \
  && ok "8 metrics endpoint serves business + SDK metrics" || bad "8 metrics" "missing series"

echo
printf "  %d passed, %d failed\n" "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
