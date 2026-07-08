#!/usr/bin/env bash
# Start PPTAgent local dev stack:
#   gateway-server  → gRPC :9090, HTTP :9091
#   app             → HTTP :8080
#   frontend (Vite) → HTTP :5173
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
LOG_DIR="$RUN_DIR/logs"
PID_DIR="$RUN_DIR/pids"

GATEWAY_HTTP_PORT=9091
GATEWAY_GRPC_PORT=9090
APP_PORT=8080
FRONTEND_PORT=5173

KILL_EXISTING=1
SKIP_FRONTEND=0

usage() {
  cat <<'EOF'
Usage: scripts/dev-up.sh [options]

Options:
  --no-kill          Do not stop processes already bound to dev ports
  --skip-frontend    Start gateway + app only
  -h, --help         Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-kill) KILL_EXISTING=0 ;;
    --skip-frontend) SKIP_FRONTEND=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
  esac
  shift
done

mkdir -p "$LOG_DIR" "$PID_DIR"

free_port() {
  local port="$1"
  if lsof -ti:"$port" >/dev/null 2>&1; then
    echo "→ Stopping existing process on port $port"
    lsof -ti:"$port" | xargs kill -9 2>/dev/null || true
    sleep 1
  fi
}

wait_http() {
  local url="$1"
  local label="$2"
  local timeout="${3:-180}"
  local elapsed=0

  echo -n "→ Waiting for $label"
  while (( elapsed < timeout )); do
    if curl -sf "$url" >/dev/null 2>&1; then
      echo " — ready"
      return 0
    fi
    echo -n "."
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo
  echo "ERROR: $label did not become ready within ${timeout}s ($url)" >&2
  echo "Check logs: $LOG_DIR" >&2
  return 1
}

start_gateway() {
  echo "==> Starting gateway-server (gRPC :$GATEWAY_GRPC_PORT, HTTP :$GATEWAY_HTTP_PORT)"
  (
    cd "$ROOT"
    nohup ./gradlew :gateway-server:bootRun --no-daemon \
      >>"$LOG_DIR/gateway-server.log" 2>&1 </dev/null &
    echo $! >"$PID_DIR/gateway-server.pid"
    disown
  )
  wait_http "http://127.0.0.1:$GATEWAY_HTTP_PORT/actuator/health" "gateway-server" 180
  lsof -ti:"$GATEWAY_HTTP_PORT" >"$PID_DIR/gateway-server.port.pid" 2>/dev/null || true
}

start_app() {
  echo "==> Starting app API (HTTP :$APP_PORT)"
  (
    cd "$ROOT"
    nohup ./gradlew :app:bootRun --no-daemon \
      >>"$LOG_DIR/app.log" 2>&1 </dev/null &
    echo $! >"$PID_DIR/app.pid"
    disown
  )
  wait_http "http://127.0.0.1:$APP_PORT/v1/ppt/health" "app" 120
  lsof -ti:"$APP_PORT" >"$PID_DIR/app.port.pid" 2>/dev/null || true
}

start_frontend() {
  echo "==> Starting frontend (Vite :$FRONTEND_PORT)"
  if [[ ! -d "$ROOT/frontend/node_modules" ]]; then
    echo "→ Installing frontend dependencies (npm install)"
    (cd "$ROOT/frontend" && npm install)
  fi
  (
    cd "$ROOT/frontend"
    nohup npm run dev -- --host 127.0.0.1 --port "$FRONTEND_PORT" \
      >>"$LOG_DIR/frontend.log" 2>&1 </dev/null &
    echo $! >"$PID_DIR/frontend.pid"
    disown
  )
  wait_http "http://127.0.0.1:$FRONTEND_PORT" "frontend" 60
  lsof -ti:"$FRONTEND_PORT" >"$PID_DIR/frontend.port.pid" 2>/dev/null || true
}

if [[ "$KILL_EXISTING" -eq 1 ]]; then
  echo "==> Freeing dev ports"
  free_port "$GATEWAY_HTTP_PORT"
  free_port "$GATEWAY_GRPC_PORT"
  free_port "$APP_PORT"
  if [[ "$SKIP_FRONTEND" -eq 0 ]]; then
    free_port "$FRONTEND_PORT"
  fi
fi

if [[ ! -f "$ROOT/ai-keys.yaml" ]]; then
  echo "WARN: $ROOT/ai-keys.yaml not found — LLM calls may fail (copy from ai-keys.yaml.example)" >&2
fi

start_gateway
start_app
if [[ "$SKIP_FRONTEND" -eq 0 ]]; then
  start_frontend
fi

cat <<EOF

PPTAgent dev stack is up.

  Frontend UI : http://127.0.0.1:$FRONTEND_PORT
  App API     : http://127.0.0.1:$APP_PORT/v1/ppt/health
  Gateway ops : http://127.0.0.1:$GATEWAY_HTTP_PORT/actuator/health

Logs : $LOG_DIR
PIDs : $PID_DIR
Stop : scripts/dev-down.sh

EOF
