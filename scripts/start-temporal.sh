#!/usr/bin/env bash
# Temporal dev server with every custom search attribute registered up front.
# Registering them at startup rather than as a manual step means a visibility
# filter never fails because an attribute does not exist yet.
set -euo pipefail
cd "$(dirname "$0")/.."

DB="${TEMPORAL_DB:-.temporal-demo.db}"

if temporal operator cluster health >/dev/null 2>&1; then
  echo "Temporal is already running on localhost:7233 — reusing it."
  exit 0
fi

exec temporal server start-dev \
  --db-filename "$DB" \
  --ui-port 8233 \
  --metrics-port 8000 \
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
