#!/usr/bin/env bash
# Quick health check for the local PPTAgent dev stack.
set -euo pipefail

check() {
  local name="$1"
  local url="$2"
  if curl -sf "$url" >/dev/null 2>&1; then
    printf "  %-14s UP   %s\n" "$name" "$url"
  else
    printf "  %-14s DOWN %s\n" "$name" "$url"
  fi
}

echo "PPTAgent dev stack status:"
check "gateway" "http://127.0.0.1:9091/actuator/health"
check "app"       "http://127.0.0.1:8080/v1/ppt/health"
check "frontend"  "http://127.0.0.1:5173"
