#!/usr/bin/env bash
# Run exam public dev set (5 fixtures × 2 profiles) against local app :8080.
# Usage: ./scripts/exam-benchmark.sh [tradeoff|beauty|all]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
FIXTURES="$ROOT/docs/exam-fixtures"
OUT_JSONL="$ROOT/docs/benchmark-results.jsonl"
DEMO_ROOT="$ROOT/docs/demos"
APP_URL="${APP_URL:-http://127.0.0.1:8080}"
PROFILE_FILTER="${1:-all}"

declare -a FIXTURE_IDS=(01 02 03 04 05)
declare -a FIXTURE_NAMES=("Python 入门" "年度复盘" "咖啡豆" "Rust 重写" "京都两日")

profile_query() {
  case "$1" in
    tradeoff) echo "outlineModel=deepseek-pro&contentModel=deepseek-flash" ;;
    beauty)   echo "outlineModel=deepseek-pro&contentModel=deepseek-pro" ;;
    *) echo "unknown profile: $1" >&2; exit 1 ;;
  esac
}

run_one() {
  local id="$1" name="$2" profile="$3"
  local fixture
  fixture=$(ls "$FIXTURES"/${id}-*.json 2>/dev/null | head -1)
  if [[ -z "$fixture" ]]; then
    echo "Missing fixture for id=$id" >&2
    return 1
  fi

  local query outfile logfile
  query=$(profile_query "$profile")
  outfile=$(mktemp)
  logfile="$ROOT/docs/benchmark-${profile}-${id}.log"

  echo "==> [$profile] #$id $name"
  local start end wall http status slides file
  start=$(date +%s)
  http=$(curl -sS -o "$outfile" -w "%{http_code}" \
    -X POST "${APP_URL}/v1/ppt/run?stage=pptx&${query}" \
    -H "Content-Type: application/json" \
    --data-binary "@${fixture}" \
    --max-time 2400) || http="000"
  end=$(date +%s)
  wall=$((end - start))

  status=$(python3 -c "import json,sys; d=json.load(open('$outfile')); print(d.get('status','?'))" 2>/dev/null || echo "parse_error")
  slides=$(python3 -c "import json,sys; d=json.load(open('$outfile')); p=d.get('pptx') or {}; print(p.get('slideCount',''))" 2>/dev/null || echo "")
  file=$(python3 -c "import json,sys; d=json.load(open('$outfile')); p=d.get('pptx') or {}; print(p.get('fileName',''))" 2>/dev/null || echo "")

  echo "    http=$http status=$status wall=${wall}s slides=$slides file=$file" | tee -a "$logfile"

  python3 - "$id" "$name" "$profile" "$query" "$http" "$wall" "$outfile" "$OUT_JSONL" "$DEMO_ROOT" "$ROOT" <<'PY'
import json, shutil, sys
from pathlib import Path

id_, name, profile, query, http, wall, resp_path, jsonl_path, demo_root, root = sys.argv[1:11]
data = json.loads(Path(resp_path).read_text())
record = {
    "id": id_.lstrip("0") or "0",
    "name": name,
    "profile": profile,
    "query": query,
    "http": int(http),
    "wallSeconds": int(wall),
    "status": data.get("status"),
    "slideCount": (data.get("pptx") or {}).get("slideCount"),
    "pptxFile": (data.get("pptx") or {}).get("fileName"),
    "timingMs": data.get("timingMs") or {},
    "modelsUsed": data.get("modelsUsed") or {},
    "errors": data.get("errors") or [],
}
Path(jsonl_path).parent.mkdir(parents=True, exist_ok=True)
with open(jsonl_path, "a", encoding="utf-8") as f:
    f.write(json.dumps(record, ensure_ascii=False) + "\n")

if record["status"] == "ok" and record["pptxFile"]:
    src = Path(root) / "build/output/pptx" / record["pptxFile"]
    dest_dir = Path(demo_root) / profile
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / f"{id_}-{record['pptxFile']}"
    if src.is_file():
        shutil.copy2(src, dest)
        print(f"    copied -> {dest}")
PY

  rm -f "$outfile"
}

mkdir -p "$DEMO_ROOT/tradeoff" "$DEMO_ROOT/beauty"
: > "$OUT_JSONL"

profiles=()
if [[ "$PROFILE_FILTER" == "all" ]]; then
  profiles=(tradeoff beauty)
elif [[ "$PROFILE_FILTER" == "tradeoff" || "$PROFILE_FILTER" == "beauty" ]]; then
  profiles=("$PROFILE_FILTER")
else
  echo "Usage: $0 [tradeoff|beauty|all]" >&2
  exit 1
fi

for profile in "${profiles[@]}"; do
  for i in "${!FIXTURE_IDS[@]}"; do
    run_one "${FIXTURE_IDS[$i]}" "${FIXTURE_NAMES[$i]}" "$profile"
  done
done

echo "Results written to $OUT_JSONL"
