#!/usr/bin/env bash
# Resolves the pinned temporal CLI rather than relying on PATH order.
#
# The CLI decides the bundled Server and Web UI versions, and more than one copy can be on
# PATH -- a stale mise install directory can sit ahead of the mise shim. `mise which` answers
# from .mise.toml, which holds the pin; `command -v` is the fallback.
temporal_bin() {
  local bin
  bin="$(mise which temporal 2>/dev/null)" || true
  [[ -n "$bin" && -x "$bin" ]] || bin="$(command -v temporal 2>/dev/null)" || true
  [[ -n "$bin" ]] || { echo "No temporal CLI found. Run 'mise install'." >&2; return 1; }
  echo "$bin"
}
