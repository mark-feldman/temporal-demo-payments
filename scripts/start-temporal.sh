#!/usr/bin/env bash
# Temporal dev server with every custom search attribute registered up front.
# Registering them at startup rather than as a manual step means a visibility
# filter never fails because an attribute does not exist yet.
#
# The settings below raise dev-server defaults that cap throughput under load.
# `--history-shard-count` is not a `start-dev` flag, and the shard count is fixed at cluster
# creation, so capacity comes from dynamic config. The key names are those in the server's
# common/dynamicconfig/constants.go.
set -euo pipefail
cd "$(dirname "$0")/.."

source "$(dirname "$0")/temporal-bin.sh"
TEMPORAL="$(temporal_bin)" || exit 1

DB="${TEMPORAL_DB:-.temporal-demo.db}"

if "$TEMPORAL" operator cluster health >/dev/null 2>&1; then
  echo "Temporal is already running on localhost:7233 — reusing it."
  exit 0
fi

# SQLite pragmas. journal_mode=WAL lets readers run alongside a writer; the default
#   rollback-journal mode makes readers and writers mutually exclusive. synchronous=NORMAL is
#   safe under WAL, and the cache and mmap sizes keep hot pages out of the page-fault path.
# history.shardIOConcurrency (default 1) is the concurrency of persistence operations within a
#   shard context.
# The queue processors each poll persistence at 20 RPS by default. The transfer queue is the
#   dispatch path -- it hands workflow and activity tasks to matching -- and the timer queue
#   carries the approval and bank deadlines and activity retry backoff.
# matching.numTaskqueue{Read,Write}Partitions (default 4) shards a task queue into partitions
#   matched independently, so it sets the dispatch parallelism.
# The execution queue scheduler groups tasks per workflow to limit BusyWorkflow errors. Queue
#   concurrency stays at its default of 2, which is near-serial per workflow; raising it
#   reintroduces the contention the scheduler exists to prevent. Beyond MaxQueues concurrent
#   workflows the rest fall back to the base FIFO scheduler, so it is raised to 4000.
# The per-host service RPS caps (history 3000, frontend 2400, matching 1200) are sized for a
#   deployment with many hosts, not for one of each service on a laptop.
# history.enableHostLevelEventsCache (default false) switches the events cache from 512KB per
#   shard to a 256MB host-level cache.
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
