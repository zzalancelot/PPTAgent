#!/usr/bin/env bash
# Stop PPTAgent local dev stack (gateway, app, frontend).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT/.run/pids"

PORTS=(9091 9090 8080 5173)

free_port() {
  local port="$1"
  local pids
  pids="$(lsof -ti:"$port" 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "→ Stopping port $port (pid: $(echo "$pids" | tr '\n' ' '))"
    echo "$pids" | xargs kill -9 2>/dev/null || true
  fi
}

echo "==> Stopping PPTAgent dev stack"

if [[ -d "$PID_DIR" ]]; then
  for pidfile in "$PID_DIR"/*.pid; do
    [[ -f "$pidfile" ]] || continue
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      echo "→ Stopping $(basename "$pidfile" .pid) (pid $pid)"
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
  done
  rm -f "$PID_DIR"/*.port.pid
fi

for port in "${PORTS[@]}"; do
  free_port "$port"
done

echo "Done."
