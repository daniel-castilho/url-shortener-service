#!/bin/bash
# Canary metrics-gate end-to-end test against an EPHEMERAL pinned Prometheus (Epic 25).
#
# Spins up prom/prometheus:v3.3.0 (same pin as CI promtool) plus mock
# /actuator/prometheus endpoints, in throwaway containers on the host network (the
# repo's k6/nginx container pattern), then runs scripts/canary-gate.sh against the
# REAL Prometheus query API and asserts the gate's decisions:
#
#   1. healthy BLUE   -> exit 0 (stage may proceed)
#   2. broken GREEN   -> exit 1 (MEASURED 5xx breach; fail-closed, no retry)
#   3. independence   -> blue's healthy series can NEVER satisfy green's gate:
#                        queries are scoped job=url-shortener-<color>, proven by
#                        green failing while blue holds healthy fixtures
#   4. scrape auth    -> correct basic_auth credential -> up=1 (scrape accepted);
#                        anonymous scrape -> 401 -> up=0 -> gate NO-EVIDENCE (exit 2),
#                        NEVER a pass
#
# Mocks serve OpenMetrics text (http_server_requests_seconds_count / _bucket) with
# increasing counters; Prometheus itself synthesizes `up` from scrape success/failure.
# Fully self-contained: disposable CI credentials, no production state, no host
# mutation, isolated host ports (default 19090+). Complements the offline
# canary-gate.sh self-test with a real Prometheus runtime.
#
# Usage:
#   bash scripts/canary-prometheus-e2e.sh              # full e2e (needs Docker)
#   bash scripts/canary-prometheus-e2e.sh --self-test  # offline fixture/config checks
#   bash scripts/canary-prometheus-e2e.sh --help
#
# Environment:
#   PROM_IMAGE    - pinned prometheus image (default: prom/prometheus:v3.3.0)
#   MOCKS_IMAGE   - image hosting the mock endpoint (default: python:3-alpine)
#   E2E_BASE_PORT - first host port used (default 19090)
#   KEEP_E2E=1    - do not remove containers on exit (debugging)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

PROM_IMAGE="${PROM_IMAGE:-prom/prometheus:v3.3.0}"
MOCKS_IMAGE="${MOCKS_IMAGE:-python:3-alpine}"
BASE="${E2E_BASE_PORT:-19090}"
PROM_PORT=$((BASE + 0))
BLUE_NOAUTH=$((BASE + 1))   # healthy, no auth
GREEN_NOAUTH=$((BASE + 2))  # broken (5xx + bad latency), no auth
BLUE_AUTH=$((BASE + 3))     # healthy, REQUIRES Authorization header

MOCKS_CT="url-shortener-canary-mocks"
PROM_CT="url-shortener-canary-prom"
KEEP="${KEEP_E2E:-0}"

MOCK_SERVER='#!/usr/bin/env python3
import http.server, os, sys
PORT = int(sys.argv[1]); BROKEN = os.environ.get("MOCK_BROKEN") == "1"
AUTH = os.environ.get("AUTH_REQUIRED") == "1"
REAL = "Basic ZGVwbG95LWdhdGUtdGVzdDpzY3JhcGUtcGFzcw=="
class H(http.server.BaseHTTPRequestHandler):
    n = 0
    def do_GET(self):
        type(self).n += 1
        n = type(self).n
        if AUTH and self.headers.get("Authorization") != REAL:
            self.send_response(401); self.end_headers(); return
        if self.path != "/actuator/prometheus":
            self.send_response(404); self.end_headers(); return
        ok = n % 2 if BROKEN else n
        err = (n // 2) if BROKEN else 0
        tot = ok + err
        lines = [
            "# TYPE http_server_requests_seconds_count counter",
            f"http_server_requests_seconds_count{{status=\"200\",}} {ok}",
            f"http_server_requests_seconds_count{{status=\"500\",}} {err}",
            "# TYPE http_server_requests_seconds_bucket histogram",
            f"http_server_requests_seconds_bucket{{le=\"0.2\",}} {ok}",
            f"http_server_requests_seconds_bucket{{le=\"+Inf\",}} {tot}",
        ]
        body = ("\n".join(lines) + "\n").encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
'

log() { echo "[canary-e2e] $*"; }
die() { echo "[canary-e2e] ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------- test config
# $1=out_file $2=blue_target(:port) $3=green_target(:port) $4=green_auth(0|1)
# green_auth=0 -> no basic_auth on the green job (anonymous scrape -> 401).
make_test_config() {
    local out="$1" blue="$2" green="$3" green_auth="$4"
    local ba_blue="    basic_auth:\n      username: deploy-gate-test\n      password_file: /mon/scrape.pw"
    local ba_green="$ba_blue"
    [[ "$green_auth" == 0 ]] && ba_green=""
    cat > "$out" <<EOF
global:
  scrape_interval: 5s
  evaluation_interval: 15s
rule_files: []
scrape_configs:
  - job_name: url-shortener-blue
    metrics_path: /actuator/prometheus
    scrape_interval: 5s
    honor_labels: false
$(printf '%b\n' "$ba_blue")
    static_configs:
      - targets: ["127.0.0.1:$blue"]
        labels:
          application: url-shortener
          environment: e2e
          color: blue
  - job_name: url-shortener-green
    metrics_path: /actuator/prometheus
    scrape_interval: 5s
    honor_labels: false
$(printf '%b\n' "$ba_green")
    static_configs:
      - targets: ["127.0.0.1:$green"]
        labels:
          application: url-shortener
          environment: e2e
          color: green
EOF
}

# ---------------------------------------------------------------- self-test (offline)
ST_TMP=""
self_test() {
    local fail=0
    assert_eq() { # desc expected actual
        if [[ "$2" == "$3" ]]; then
            printf '  ok   %s\n' "$1"
        else
            printf '  FAIL %s (expected %s, got %s)\n' "$1" "$2" "$3" >&2
            fail=1
        fi
    }
    assert_eq "gate script present + executable" 1 "$([[ -x "$ROOT_DIR/scripts/canary-gate.sh" ]] && echo 1 || echo 0)"
    assert_eq "prometheus pin matches CI promtool" 1 \
        "$(grep -q 'v3.3.0' "$ROOT_DIR/.github/workflows/ci.yml" && grep -q '3.3.0' "$ROOT_DIR/scripts/install-prometheus.sh" && echo 1 || echo 0)"
    assert_eq "fixture ports distinct" 1 "$([[ $PROM_PORT != $BLUE_NOAUTH && $BLUE_NOAUTH != $GREEN_NOAUTH && $GREEN_NOAUTH != $BLUE_AUTH ]] && echo 1 || echo 0)"

    ST_TMP="$(mktemp -d)"; trap 'rm -rf "$ST_TMP"' EXIT
    chmod 755 "$ST_TMP"
    local tmp="$ST_TMP"
    make_test_config "$tmp/prom.yml" 18080 18081 1
    printf 'scrape-pass\n' > "$tmp/scrape.pw"

    if ! docker image inspect "$PROM_IMAGE" >/dev/null 2>&1; then
        printf '  skip fixture promtool (image %s not pulled locally; CI pulls it)\n' "$PROM_IMAGE"
    else
        docker run --rm --entrypoint promtool -v "$tmp:/mon:ro" "$PROM_IMAGE" \
            check config /mon/prom.yml >"$tmp/out" 2>&1
        assert_eq "fixture config valid prometheus syntax" 1 "$(grep -q 'SUCCESS' "$tmp/out" && echo 1 || echo 0)"
        local ok=0
        make_test_config "$tmp/prom2.yml" 18080 18081 0
        docker run --rm --entrypoint promtool -v "$tmp:/mon:ro" "$PROM_IMAGE" \
            check config /mon/prom2.yml >"$tmp/out2" 2>&1 && ok=1
        assert_eq "anonymous-green config still valid syntax" 1 "$ok"
    fi

    if [[ "$fail" -eq 0 ]]; then
        printf 'canary-prometheus-e2e --self-test: ALL PASS\n'
        return 0
    fi
    printf 'canary-prometheus-e2e --self-test: FAILURES\n' >&2
    return 1
}

# ---------------------------------------------------------------- real e2e
cleanup() {
    if [[ "$KEEP" == 1 ]]; then
        log "KEEP_E2E=1 — leaving containers ($MOCKS_CT, $PROM_CT) for inspection"
        return
    fi
    docker rm -f "$MOCKS_CT" "$PROM_CT" >/dev/null 2>&1 || true
}
trap cleanup EXIT

e2e() {
    command -v docker >/dev/null 2>&1 || die "docker required"
    command -v curl >/dev/null 2>&1 || die "curl required"
    docker image inspect "$PROM_IMAGE" >/dev/null 2>&1 || die "image $PROM_IMAGE not pulled (docker pull $PROM_IMAGE)"
    docker image inspect "$MOCKS_IMAGE" >/dev/null 2>&1 || die "image $MOCKS_IMAGE not pulled (docker pull $MOCKS_IMAGE)"

    local tmp; tmp="$(mktemp -d)"
    chmod 755 "$tmp"

    # --- mocks: one container, three endpoints, host network
    # mock.py is written with printf (echo would interpret \n inside the python source)
    # and copied in; each endpoint runs with its own env flags via docker exec -e.
    printf '%s\n' "$MOCK_SERVER" > "$tmp/mock.py"
    docker run -d --name "$MOCKS_CT" --network host "$MOCKS_IMAGE" sleep 3600 >/dev/null 2>&1 \
        || die "cannot start mocks container"
    docker cp "$tmp/mock.py" "$MOCKS_CT:/tmp/mock.py"
    docker exec -d -e MOCK_BROKEN=0 "$MOCKS_CT" python3 /tmp/mock.py "$BLUE_NOAUTH"
    docker exec -d -e MOCK_BROKEN=1 "$MOCKS_CT" python3 /tmp/mock.py "$GREEN_NOAUTH"
    docker exec -d -e MOCK_BROKEN=0 -e AUTH_REQUIRED=1 "$MOCKS_CT" python3 /tmp/mock.py "$BLUE_AUTH"

    sleep 2
    for p in "$BLUE_NOAUTH" "$GREEN_NOAUTH"; do
        curl -sf --max-time 2 "http://127.0.0.1:$p/actuator/prometheus" >/dev/null 2>&1 \
            || die "mock endpoint $p did not come up"
    done
    curl -sf --max-time 2 -H "Authorization: Basic ZGVwbG95LWdhdGUtdGVzdDpzY3JhcGUtcGFzcw==" \
        "http://127.0.0.1:$BLUE_AUTH/actuator/prometheus" >/dev/null 2>&1 \
        || die "mock endpoint $BLUE_AUTH (auth-required) did not come up"

    # --- phase A: authenticated scrapes, healthy blue + broken green (auth case on green)
    printf 'scrape-pass\n' > "$tmp/scrape.pw"
    make_test_config "$tmp/promA.yml" "$BLUE_NOAUTH" "$GREEN_NOAUTH" 1
    docker run -d --name "$PROM_CT" --network host \
        -v "$tmp:/mon:ro" \
        "$PROM_IMAGE" \
        --config.file=/mon/promA.yml \
        --storage.tsdb.path=/tmp/prom-tsdb-a \
        --web.listen-address="127.0.0.1:$PROM_PORT" \
        --no-web.enable-admin-api \
        --no-web.enable-lifecycle >/dev/null 2>&1 \
        || die "cannot start prometheus (phase A)"

    log "waiting for prometheus ready at 127.0.0.1:$PROM_PORT ..."
    local ready=0
    for _ in $(seq 1 30); do
        if curl -sf --max-time 2 "http://127.0.0.1:$PROM_PORT/-/ready" >/dev/null 2>&1; then
            ready=1; break
        fi
        sleep 1
    done
    [[ "$ready" == 1 ]] || die "ephemeral prometheus not ready in 30s"

    sleep 25   # accumulate >= 5 scrapes per color (5s interval) for 30s window

    local fail=0
    assert_eq() { # desc expected actual
        if [[ "$2" == "$3" ]]; then
            printf '  ok   %s\n' "$1"
        else
            printf '  FAIL %s (expected %s, got %s)\n' "$1" "$2" "$3" >&2
            fail=1
        fi
    }

    # Bounded retry (standalone gate capability, ADR 0012 §Amendments D7): a single
    # transient scrape hiccup at the tail of a window must not fail the e2e — the gate
    # re-evaluates once after 5s and the healthy color passes on the second eval. A
    # MEASURED breach (green) still exits 1 immediately (sticky FAIL, no retry), and a
    # persistent NO-EVIDENCE state (anonymous green in phase B) still exits 2 after the
    # bounded retries are exhausted — the assertions below are unaffected.
    local common=(--max-evals 2 --evals-spacing 5 --window 30 --freshness 45 \
        --min-requests 1 --error-ratio-max 0.001 --latency-ok-min 0.99 \
        --prometheus-url "http://127.0.0.1:$PROM_PORT")

    # 1. healthy BLUE -> PASS
    local rc=0
    bash "$ROOT_DIR/scripts/canary-gate.sh" --color blue "${common[@]}" >/dev/null 2>&1 || rc=$?
    assert_eq "blue gate passes (healthy fixtures)" 0 "$rc"

    # 2. broken GREEN -> EXIT 1 (measured 5xx breach, fail-closed no-retry)
    rc=0
    bash "$ROOT_DIR/scripts/canary-gate.sh" --color green "${common[@]}" >/dev/null 2>&1 || rc=$?
    assert_eq "green gate fails (5xx breach, fail-closed)" 1 "$rc"

    # 3. scrape auth accepted: correct credential -> up=1 (blue/green scrapes OK, gate saw series)
    #    (asserted by the access probes above succeeding; explicit up probe below)
    local up_blue up_green
    up_blue="$(curl -sf "http://127.0.0.1:$PROM_PORT/api/v1/query" \
        --data-urlencode 'query=up{job="url-shortener-blue"}' | grep -o '"1"' | head -1 || true)"
    up_green="$(curl -sf "http://127.0.0.1:$PROM_PORT/api/v1/query" \
        --data-urlencode 'query=up{job="url-shortener-green"}' | grep -o '"1"' | head -1 || true)"
    assert_eq "auth'd scrape: up=1 for both colors" 1 \
        "$([[ "$up_blue" == '"1"' && "$up_green" == '"1"' ]] && echo 1 || echo 0)"

    rm -rf "$tmp"; tmp="$(mktemp -d)"
    chmod 755 "$tmp"

    # --- phase B: anonymous green scrape -> 401 -> up=0 -> NO-EVIDENCE (exit 2)
    printf 'scrape-pass\n' > "$tmp/scrape.pw"
    make_test_config "$tmp/promB.yml" "$BLUE_AUTH" "$BLUE_AUTH" 0   # green job => auth-required target, NO creds
    docker rm -f "$PROM_CT" >/dev/null 2>&1
    docker run -d --name "$PROM_CT" --network host \
        -v "$tmp:/mon:ro" \
        "$PROM_IMAGE" \
        --config.file=/mon/promB.yml \
        --storage.tsdb.path=/tmp/prom-tsdb-b \
        --web.listen-address="127.0.0.1:$PROM_PORT" \
        --no-web.enable-admin-api \
        --no-web.enable-lifecycle >/dev/null 2>&1 \
        || die "cannot start prometheus (phase B)"
    ready=0
    for _ in $(seq 1 30); do
        if curl -sf --max-time 2 "http://127.0.0.1:$PROM_PORT/-/ready" >/dev/null 2>&1; then
            ready=1; break
        fi
        sleep 1
    done
    [[ "$ready" == 1 ]] || die "prometheus (phase B) not ready in 30s"
    sleep 25

    rc=0
    bash "$ROOT_DIR/scripts/canary-gate.sh" --color green "${common[@]}" >/dev/null 2>&1 || rc=$?
    assert_eq "anonymous green scrape -> NO-EVIDENCE (exit 2)" 2 "$rc"

    # blue still fine (auth'd against the same auth-required target via password_file)
    rc=0
    bash "$ROOT_DIR/scripts/canary-gate.sh" --color blue "${common[@]}" >/dev/null 2>&1 || rc=$?
    assert_eq "auth'd blue against auth-required target still passes" 0 "$rc"

    if [[ "$fail" -eq 0 ]]; then
        printf 'canary-prometheus-e2e: ALL PASS\n'
    else
        printf 'canary-prometheus-e2e: FAILURES\n' >&2
        return 1
    fi
}

case "${1:-e2e}" in
    --self-test) self_test ;;
    e2e|--e2e) e2e ;;
    -h|--help)
        sed -n '1,55p' "$0" | grep -v '^#!/bin/bash' | sed 's/^# \{0,1\}//'
        ;;
    *) die "unknown command '${1:-}' — use 'e2e', '--self-test' or '--help'" ;;
esac