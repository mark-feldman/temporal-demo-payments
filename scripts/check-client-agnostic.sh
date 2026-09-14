#!/usr/bin/env bash
# Keeps the repository free of customer-specific names, enforced mechanically rather than
# by review. Run it before publishing anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

# The banned terms are assembled rather than written out, so this file does not match itself.
TERMS="$(printf '%s|%s|%s|%s' 'gd''pn' 'gt''pn' 'dt''pn' 'airw''allex')"

if grep -rinE "$TERMS" . \
     --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle \
     --exclude-dir=node_modules --exclude-dir=tools \
     --exclude='*.md' --exclude="$(basename "$0")" 2>/dev/null; then
  echo "FAIL: customer-specific term found. The repo must stay client-agnostic."
  exit 1
fi
echo "OK: no customer-specific terms in code or config."
