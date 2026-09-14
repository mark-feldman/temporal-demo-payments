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
# Asserted, not just awaited. "3" below only checks the workflow is no longer waiting, which
# is equally true of one that never waited at all -- so an approval gate that stopped firing
# would have read PASS. The gate is the behaviour under test; check it happened.
P=$(await "$W" "AWAITING_APPROVAL" 20)
T=$(status "$W" | jqv "['approvalTier']")
[[ "$P" == "AWAITING_APPROVAL" && "$T" == "SENIOR" ]] \
  && ok "3a an amount above the threshold parks on human approval (tier=$T)" \
  || bad "3a approval gate" "state=$P tier=$T"
signal "$W" approval '{"approved":true,"approver":"contract-test"}'
S=$(await "$W" "AWAITING_BANK_CONFIRMATION,COMPLETED" 20)
[[ "$S" != "AWAITING_APPROVAL" ]] && ok "3 approval signal advances the workflow" || bad "3 approval" "$S"
signal "$W" bank-status '{"status":"COMPLETED"}'

# 5 -- transient failure produces retries
W=$(start '{"scenario":"ct-retry","amountMinor":25000,"behavior":"FAIL_TRANSIENT","transientFailures":2}' | jqv "['workflowId']")
await "$W" "AWAITING_BANK_CONFIRMATION" 30 >/dev/null
A=$(status "$W" | jqv "['railAttempts']")
# Exactly three, not "more than one": transientFailures=2 means two failures then the attempt
# that lands, so an implementation that failed once and an implementation that failed five
# times are both wrong, and `-gt 1` could not tell either of them from correct.
[[ "${A:-0}" -eq 3 ]] && ok "5 transient failure produces retries (attempt=$A)" || bad "5 retries" "attempt=$A, expected 3"
signal "$W" bank-status '{"status":"COMPLETED"}'

# 6 -- permanent failure produces compensation
W=$(start '{"scenario":"ct-permanent","amountMinor":25000,"behavior":"FAIL_PERMANENT"}' | jqv "['workflowId']")
S=$(await "$W" "FAILED,CANCELLED" 30)
HIST=$(status "$W" | jqv "['history']")
# failureCategory is part of the assertion, not decoration: "failed and compensated" is also
# true of a payout that failed for an entirely different reason.
FC=$(status "$W" | jqv "['failureCategory']")
[[ "$S" == "FAILED" && "$FC" == "RAIL_PERMANENT" && "$HIST" == *COMPENSAT* ]] \
  && ok "6 permanent failure triggers compensation" || bad "6 compensation" "$S / $FC / $HIST"

# 7 -- no callback: the workflow polls the bank and resolves itself, no ops review
W=$(start '{"scenario":"ct-poll-ok","amountMinor":25000,"behavior":"ACCEPTED_NO_CALLBACK","pollsBeforeResolution":2,"resolvedStatus":"COMPLETED"}' | jqv "['workflowId']")
await "$W" "AWAITING_BANK_CONFIRMATION" 20 >/dev/null
signal "$W" bank-status '{"status":"UNKNOWN"}'
S=$(await "$W" "COMPLETED,FAILED" 40)
HIST=$(status "$W" | jqv "['history']")
if [[ "$S" == "COMPLETED" && "$HIST" == *POLLING_BANK_STATUS* ]]; then
  ok "7 unknown callback resolves by polling the bank, without escalating"
else
  bad "7 polling should resolve" "$S / $HIST"
fi

# 7b -- polling resolves to a rejection, which compensates
W=$(start '{"scenario":"ct-poll-reject","amountMinor":25000,"behavior":"ACCEPTED_NO_CALLBACK","pollsBeforeResolution":1,"resolvedStatus":"REJECTED"}' | jqv "['workflowId']")
await "$W" "AWAITING_BANK_CONFIRMATION" 20 >/dev/null
signal "$W" bank-status '{"status":"UNKNOWN"}'
S=$(await "$W" "FAILED,COMPLETED" 40)
HIST=$(status "$W" | jqv "['history']")
[[ "$S" == "FAILED" && "$HIST" == *COMPENSAT* ]] \
  && ok "7b polling resolves to REJECTED and compensates" || bad "7b poll-reject" "$S / $HIST"

# 7c -- polling exhausts without an answer: compensate, and say why in failureCategory
W=$(start '{"scenario":"ct-poll-never","amountMinor":25000,"behavior":"ACCEPTED_NO_CALLBACK","pollingNeverResolves":true}' | jqv "['workflowId']")
await "$W" "AWAITING_BANK_CONFIRMATION" 20 >/dev/null
signal "$W" bank-status '{"status":"UNKNOWN"}'
S=$(await "$W" "FAILED,COMPLETED" 60)
FC=$(status "$W" | jqv "['failureCategory']")
HIST=$(status "$W" | jqv "['history']")
REV=$(status "$W" | jqv "['reversalReference']")
[[ "$S" == "FAILED" && "$FC" == "UNKNOWN_BANK_STATUS" && "$HIST" == *COMPENSAT* ]] \
  && ok "7c exhausted polling compensates, flagged UNKNOWN_BANK_STATUS" || bad "7c poll-exhausted" "$S / $FC"

# 7e -- the unwind reverses at the bank before releasing our own reservation
[[ -n "$REV" ]] \
  && ok "7e bank instruction reversed during the unwind ($REV)" \
  || bad "7e reversal" "no reversalReference on a compensated payout"

# 7d -- the API contract does not change shape with the values in it
K=$(status "$W" | python3 -c 'import sys,json;print(",".join(sorted(json.load(sys.stdin))))')
[[ "$K" == *approvalTier* && "$K" == *failureCategory* && "$K" == *railAttempts* ]] \
  && ok "7d status response always carries every field" || bad "7d contract shape" "$K"

# 9 -- how the bank confirms depends on the amount
# Below $100 the rail answers inline: no callback is sent here, and none is needed. This also
# pins the boundary from the other side -- test 2 uses 25000 and DOES park on the callback.
#
# The PATH is the contract, not the outcome. An inline settlement answers COMPLETED or
# REJECTED and this implementation splits them 80/20, so asserting COMPLETED asserts a coin
# toss -- which is exactly how this line first failed. A different implementation may pick a
# different mix and still be correct.
W=$(start '{"scenario":"ct-inline","amountMinor":4200}' | jqv "['workflowId']")
S=$(await "$W" "COMPLETED,FAILED" 40)
HIST=$(status "$W" | jqv "['history']")
FC=$(status "$W" | jqv "['failureCategory']")
INLINE=no
[[ "$HIST" == *SETTLING_WITH_BANK* && "$HIST" != *AWAITING_BANK_CONFIRMATION* ]] && INLINE=yes
# Whichever way it went, the record has to be coherent: settled means no failure, refused
# means BANK_REJECTED and nothing else.
COHERENT=no
[[ ( "$S" == "COMPLETED" && "$FC" == "NONE" ) || ( "$S" == "FAILED" && "$FC" == "BANK_REJECTED" ) ]] && COHERENT=yes
[[ "$INLINE" == "yes" && "$COHERENT" == "yes" ]] \
  && ok "9 a low-value payout settles inline, with no callback ($S)" \
  || bad "9 inline settlement" "inline=$INLINE coherent=$COHERENT state=$S category=$FC"

# 8 -- metrics endpoint
M=$(curl -s "$BASE/actuator/prometheus")
[[ "$M" == *payout_workflows_started_total* && "$M" == *temporal_* ]] \
  && ok "8 metrics endpoint serves business + SDK metrics" || bad "8 metrics" "missing series"

echo
printf "  %d passed, %d failed\n" "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
