#!/bin/bash
# Load/performance baseline for the redirect (and shorten) hot path.
#
# Requirements:
#   - Docker (mongo + redis via docker-compose)
#   - Java 21 + Maven (to boot the app)
#   - k6 is executed through the grafana/k6 container (no host install needed)
#
# Behaviour:
#   1. ensures mongo + redis are up (docker-compose),
#   2. boots the app with relaxed per-IP rate limits for the load window,
#   3. runs shorten / redirect / mixed k6 scenarios with thresholds-as-code,
#   4. prints a summary table (p50/p95/p99 from the k6 summary export),
#   5. leaves/resolves the environment; records results in load-tests/results/.
#
# k6 resolution:
#   - a native `k6` binary on PATH is preferred (recommended when the app runs
#     natively on the host, e.g. under Docker Desktop where containers cannot
#     reach host-bound services);
#   - otherwise k6 is run through the grafana/k6 container with host networking
#     (works on native-Docker CI runners, where the host network is shared).
#
# Usage:
#   bash scripts/performance-baseline.sh [duration] [redirect-rps] [shorten-rps]
#   default: 1m / 200 / 20 (local dev-friendly; adjust for a heavier gate run)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

DURATION="${1:-1m}"
REDIRECT_RPS="${2:-200}"
SHORTEN_RPS="${3:-20}"
RESULTS_DIR="load-tests/results"
mkdir -p "$RESULTS_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
PORT="${PORT:-8080}"

log() { echo "[baseline] $*"; }

export DURATION REDIRECT_RPS SHORTEN_RPS BASE_URL="http://localhost:${PORT}"

if command -v k6 >/dev/null 2>&1; then
  log "using native k6: $(command -v k6)"
  K6_CMD=(k6)
else
  log "native k6 not found; using grafana/k6 container (host networking)"
  K6_CMD=(docker run --rm --network host \
    -v "$ROOT_DIR:/baseline" -w /baseline \
    --user "$(id -u):$(id -g)" \
    -e "BASE_URL=$BASE_URL" -e "DURATION=$DURATION" \
    -e "REDIRECT_RPS=$REDIRECT_RPS" -e "SHORTEN_RPS=$SHORTEN_RPS" \
    grafana/k6)
fi

cleanup() {
  log "stopping application (pid ${APP_PID:-none})..."
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
  wait "${APP_PID:-}" 2>/dev/null || true
}
trap cleanup EXIT

log "1/5 ensuring mongo + redis are up"
docker compose up -d
for i in $(seq 1 30); do
  if docker ps --filter name=urlshortener-mongo --format '{{.Status}}' | grep -q healthy; then
    break
  fi
  sleep 2
done

log "2/5 building and booting the app (relaxed rate limits)"
export RATE_LIMITER_LIMIT=1000000
export RATE_LIMITER_REDIRECT_LIMIT=1000000
mvn -q spring-boot:run > "$RESULTS_DIR/app-baseline.log" 2>&1 &
APP_PID=$!

for i in $(seq 1 60); do
  if curl -sf "http://localhost:${PORT}/actuator/health/liveness" > /dev/null 2>&1; then
    log "app is ready"
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    log "ERROR: app exited before becoming ready"
    tail -40 "$RESULTS_DIR/app-baseline.log"
    exit 1
  fi
  sleep 2
done
if ! curl -sf "http://localhost:${PORT}/actuator/health/liveness" > /dev/null 2>&1; then
  log "ERROR: app did not become ready in time"
  tail -40 "$RESULTS_DIR/app-baseline.log"
  exit 1
fi

K6_ARGS=(run --quiet)
run_k6() {
  local scenario="$1"
  shift
  log "3/5 running k6 scenario: $scenario"
  "${K6_CMD[@]}" "${K6_ARGS[@]}" "$@" \
    --summary-export="$RESULTS_DIR/${scenario}-${STAMP}.summary.json" \
    --summary-trend-stats='avg,p(50),p(95),p(99)'
}

run_k6 shorten load-tests/shorten.js || log "WARN: shorten thresholds breached"
run_k6 redirect load-tests/redirect.js || log "WARN: redirect thresholds breached"
run_k6 mixed load-tests/mixed.js || log "WARN: mixed thresholds breached"

log "4/5 summaries (from k6 summary export)"
for s in shorten redirect mixed; do
  json="$RESULTS_DIR/${s}-${STAMP}.summary.json"
  [ -f "$json" ] || continue
  echo "--- $s ---"
  python3 - "$json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
m = d.get("metrics", {})
for metric in ("http_req_duration", "http_req_failed"):
    if metric not in m:
        continue
    v = m[metric].get("values", {})
    if metric == "http_req_failed":
        print(f"  {metric}: rate={v.get('rate', '-')}")
    else:
        print(f"  {metric}: p50={v.get('p(50)', '-')} p95={v.get('p(95)', '-')} p99={v.get('p(99)', '-')} avg={v.get('avg', '-')} (ms)")
PY
done

log "5/5 done — thresholds enforced by k6 (exit != 0 on breach). Results in $RESULTS_DIR/"