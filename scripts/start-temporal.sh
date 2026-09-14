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

# The SQLite pragmas matter more than any of the history settings below, and were the last
#   thing found rather than the first. The dev server leaves the database in rollback-journal
#   mode -- a `.temporal-demo.db-journal` file next to the db is the tell -- and in that mode
#   readers and writers are MUTUALLY EXCLUSIVE. Under the high-load preset that showed up as
#   GetWorkflowExecution p95 1.788s / p99 2.765s, the single busiest operation at 384/s, while
#   UpdateWorkflowExecution -- a write -- answered in 0.130s. A read thirteen times slower than
#   a write is a lock, not a disk. WAL lets readers run alongside a writer, which is also why
#   raising shardIOConcurrency plateaued: more concurrent access to a lock-serialised file just
#   queues. synchronous=NORMAL is safe under WAL, and the cache/mmap sizes keep the hot pages
#   out of the page-fault path.
# history.shardIOConcurrency (default 1): all persistence inside a shard context is
#   serialised. Measured under the high-load preset, individual DB calls answered in 4-8ms
#   while history RPCs sat at 4.4s p95 and the shard's internal task queue at 1.83-7.65s --
#   a queue in front of a fast disk, which is what a concurrency of 1 looks like.
# The queue processors each poll persistence at 20 RPS by default, and the transfer queue is
#   the dispatch path -- it is what hands workflow and activity tasks to matching. 20 polls a
#   second is a hard ceiling on how fast tasks can leave the history service no matter how
#   much worker or disk capacity is behind it. The timer queue matters as much here: this demo
#   is timer-heavy (30s approval, 45s bank, activity retry backoff).
# matching.numTaskqueue{Read,Write}Partitions (default 4): a task queue is sharded into
#   partitions and each is matched independently, so this is the dispatch parallelism. 16 gives
#   the ten workers' 200 activity pollers more than four places to be matched against.
# The execution queue scheduler groups tasks per workflow to avoid BusyWorkflow errors. It was
#   tried, then removed, then put back with numbers -- removing it took BusyWorkflow from
#   0.5-1.5/s to 4.3-6.1/s, so it was earning its place. What it is NOT left on is its
#   defaults: queue concurrency 2 (the server caps <=0 to 1, so 2 really is near-serial per
#   workflow) -- but 8 was tried and is WRONG: eight goroutines per workflow queue manufactures
#   exactly the contention the scheduler exists to prevent, and BusyWorkflow went 4-6/s to
#   8-35/s. Left at the default 2. The genuinely useful change is 4000 queues instead of 500:
#   past 500 concurrent workflows the rest fall back to the base FIFO scheduler and get no
#   contention handling at all, and this simulation holds 250 in flight with thousands live.
# The per-host service RPS caps (history 3000, frontend 2400, matching 1200) are sized for a
#   real deployment with many hosts. Ten worker JVMs holding 600 long polls against ONE of
#   each service is not that shape, so they are raised rather than left to throttle.
# history.enableHostLevelEventsCache (default false): the per-shard events cache is 512KB,
#   far too small for thousands of live payouts, so ReadHistoryBranch ran at ~197/s
#   re-reading history that had just been written. A 256MB host-level cache already exists
#   and is simply switched off; turning it on is free.
exec "$TEMPORAL" server start-dev \
  --db-filename "$DB" \
  --sqlite-pragma journal_mode=WAL \
  --sqlite-pragma synchronous=NORMAL \
  --sqlite-pragma cache_size=-131072 \
  --sqlite-pragma mmap_size=268435456 \
  --sqlite-pragma temp_store=MEMORY \
  --sqlite-pragma busy_timeout=10000 \
  --ui-port 8233 \
  --metrics-port 8000 \
  --dynamic-config-value history.shardIOConcurrency=16 \
  --dynamic-config-value history.enableHostLevelEventsCache=true \
  --dynamic-config-value history.taskSchedulerEnableExecutionQueueScheduler=true \
  --dynamic-config-value history.taskSchedulerExecutionQueueSchedulerQueueConcurrency=2 \
  --dynamic-config-value history.taskSchedulerExecutionQueueSchedulerMaxQueues=4000 \
  --dynamic-config-value matching.numTaskqueueReadPartitions=16 \
  --dynamic-config-value matching.numTaskqueueWritePartitions=16 \
  --dynamic-config-value history.transferProcessorMaxPollRPS=200 \
  --dynamic-config-value history.timerProcessorMaxPollRPS=200 \
  --dynamic-config-value history.visibilityProcessorMaxPollRPS=100 \
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
