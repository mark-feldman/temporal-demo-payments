#!/usr/bin/env bash
# Temporal dev server with every custom search attribute registered up front.
# Registering them at startup rather than as a manual step means a visibility
# filter never fails because an attribute does not exist yet.
#
# It also raises two history-service defaults that cap this demo's load simulator.
# `--history-shard-count` does not exist on `start-dev` (verified: "unknown flag" on CLI
# 1.8.3), and shard count is fixed at cluster creation anyway, so the capacity has to come
# from dynamic config. Both values below were read out of the server's own
# common/dynamicconfig/constants.go at v1.31.2 rather than guessed.
set -euo pipefail
cd "$(dirname "$0")/.."

source "$(dirname "$0")/temporal-bin.sh"
TEMPORAL="$(temporal_bin)" || exit 1

DB="${TEMPORAL_DB:-.temporal-demo.db}"

if "$TEMPORAL" operator cluster health >/dev/null 2>&1; then
  echo "Temporal is already running on localhost:7233 — reusing it."
  exit 0
fi

# history.shardIOConcurrency (default 1): all persistence inside a shard context is
#   serialised. Measured under the high-load preset, individual DB calls answered in 4-8ms
#   while history RPCs sat at 4.4s p95 and the shard's internal task queue at 1.83-7.65s --
#   a queue in front of a fast disk, which is what a concurrency of 1 looks like.
# The queue processors each poll persistence at 20 RPS by default, and the transfer queue is
#   the dispatch path -- it is what hands workflow and activity tasks to matching. 20 polls a
#   second is a hard ceiling on how fast tasks can leave the history service no matter how
#   much worker or disk capacity is behind it. The timer queue matters as much here: this demo
#   is timer-heavy (30s approval, 45s bank, activity retry backoff).
# history.taskSchedulerEnableExecutionQueueScheduler (default false) exists, in the server's
#   own words, "to avoid busy workflow errors" -- and resource_exhausted with cause
#   BusyWorkflow was showing up under load, so it is on.
# The per-host service RPS caps (history 3000, frontend 2400, matching 1200) are sized for a
#   real deployment with many hosts. Ten worker JVMs holding 600 long polls against ONE of
#   each service is not that shape, so they are raised rather than left to throttle.
# history.enableHostLevelEventsCache (default false): the per-shard events cache is 512KB,
#   far too small for thousands of live payouts, so ReadHistoryBranch ran at ~197/s
#   re-reading history that had just been written. A 256MB host-level cache already exists
#   and is simply switched off; turning it on is free.
exec "$TEMPORAL" server start-dev \
  --db-filename "$DB" \
  --ui-port 8233 \
  --metrics-port 8000 \
  --dynamic-config-value history.shardIOConcurrency=16 \
  --dynamic-config-value history.enableHostLevelEventsCache=true \
  --dynamic-config-value history.transferProcessorMaxPollRPS=200 \
  --dynamic-config-value history.timerProcessorMaxPollRPS=200 \
  --dynamic-config-value history.visibilityProcessorMaxPollRPS=100 \
  --dynamic-config-value history.taskSchedulerEnableExecutionQueueScheduler=true \
  --dynamic-config-value history.rps=8000 \
  --dynamic-config-value matching.rps=6000 \
  --dynamic-config-value frontend.rps=6000 \
  --dynamic-config-value frontend.namespaceRPS=6000 \
  --search-attribute payoutId=Keyword \
  --search-attribute customerId=Keyword \
  --search-attribute rail=Keyword \
  --search-attribute region=Keyword \
  --search-attribute amountMinor=Int \
  --search-attribute amountBand=Keyword \
  --search-attribute currency=Keyword \
  --search-attribute businessStatus=Keyword \
  --search-attribute requiresApproval=Bool \
  --search-attribute failureCategory=Keyword \
  --search-attribute scenarioName=Keyword \
  --dynamic-config-value system.forceSearchAttributesCacheRefreshOnRead=false
