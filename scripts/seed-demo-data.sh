#!/usr/bin/env bash
# Six workflows in six states, so the workflow list is already populated and interesting
# when you reach the observability section. Creating them live burns minutes and invites
# failure.
set -uo pipefail
BASE="${BASE:-http://localhost:8081}/demo-api"
start() { curl -s -X POST "$BASE/payouts" -H 'Content-Type: application/json' -d "$1" \
          | python3 -c 'import sys,json;print(json.load(sys.stdin)["workflowId"])'; }
sig()   { curl -s -X POST "$BASE/payouts/$1/$2" -H 'Content-Type: application/json' -d "$3" >/dev/null; }

echo "Seeding six workflows..."

W=$(start '{"scenario":"seed-success","amountMinor":24500}')
sleep 4; sig "$W" bank-status '{"status":"COMPLETED"}'
echo "  1 successful payout        -> Completed              $W"

W=$(start '{"scenario":"seed-retried","amountMinor":31000,"behavior":"FAIL_TRANSIENT","transientFailures":2}')
sleep 6; sig "$W" bank-status '{"status":"COMPLETED"}'
echo "  2 retried payout           -> Completed (3 attempts) $W"

W=$(start '{"scenario":"seed-approval","amountMinor":480000}')
echo "  3 awaiting approval        -> Running, on a signal   $W"

W=$(start '{"scenario":"seed-timeout","amountMinor":520000}')
echo "  4 approval timeout         -> Cancelled in ~30s      $W"

W=$(start '{"scenario":"seed-bankfail","amountMinor":27000,"behavior":"FAIL_PERMANENT"}')
echo "  5 bank failure             -> Failed + compensated   $W"

W=$(start '{"scenario":"seed-unknown","amountMinor":33000,"behavior":"ACCEPTED_NO_CALLBACK"}')
sleep 4; sig "$W" bank-status '{"status":"UNKNOWN"}'
echo "  6 payment status unknown   -> Running, needs review  $W"

echo
echo "Note: #6 is Running as far as Temporal is concerned. 'Needs investigation' is a"
echo "businessStatus search attribute, not an execution status -- which is exactly why"
echo "custom search attributes matter."
