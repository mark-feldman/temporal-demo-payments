#!/usr/bin/env bash
# Full reset: clears scenario config and workflow history. The API's /demo-api/reset
# only does the former -- wiping history needs a fresh dev-server database.
set -uo pipefail
cd "$(dirname "$0")/.."
"$(dirname "$0")/stop-demo.sh"
rm -f .temporal-demo.db .temporal-demo.db-shm .temporal-demo.db-wal .scenario-store.json
echo "Demo state cleared. Run 'make start' for a clean run."
