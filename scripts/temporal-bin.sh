#!/usr/bin/env bash
# Resolves the PINNED temporal CLI, rather than whatever PATH order happens to yield.
#
# PATH order is not trustworthy here and has broken the demo twice. A stale mise install
# directory can sit ahead of the mise shim -- observed as:
#
#   .../mise/installs/github-temporalio-cli/v1.6.1/temporal   <- stale, first on PATH
#   .../mise/shims/temporal                                   -> the pinned 1.8.3
#   /opt/homebrew/bin/temporal                                -> also 1.8.3
#
# The CLI decides the bundled Server and Web UI versions, so the wrong one silently swaps
# the demo's server and UI (that is how the UI came up as 2.45.3 instead of the pinned
# 2.50.1). `mise which` answers from .mise.toml, which is the actual pin.
temporal_bin() {
  local bin
  bin="$(mise which temporal 2>/dev/null)" || true
  [[ -n "$bin" && -x "$bin" ]] || bin="$(command -v temporal 2>/dev/null)" || true
  [[ -n "$bin" ]] || { echo "No temporal CLI found. Run 'mise install'." >&2; return 1; }
  echo "$bin"
}
