#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
echo "Stopping backend..."; pkill -f 'payout-demo.*\.jar|PayoutsApplicationKt' 2>/dev/null || true
echo "Stopping containers..."; docker compose down 2>/dev/null || true
echo "Stopping Temporal..."; pkill -f 'temporal server start-dev' 2>/dev/null || true
echo "Stopped."
