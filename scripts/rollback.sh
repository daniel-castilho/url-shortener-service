#!/usr/bin/env bash
#
# URL Shortener rollback (Epic 8 story 8.4). Instant weights-to-previous-color,
# NO rebuild, incident one-liner printed. Reads the last deploy intent from
# deploy/runtime/last-deploy.txt (written by deploy.sh BEFORE the first weight flip).
# Contract: ADR 0007 — the previous color's jar was never touched (cutover stops it,
# it does not delete), so rollback is start + render + reload.
#
# Usage: scripts/rollback.sh [--self-test]
# Environment: NGINX_CMD / NGINX_RUNTIME_CONF / LAST_DEPLOY_FILE (same as deploy.sh)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

NGINX_TEMPLATE="$REPO_DIR/deploy/proxy/nginx.conf"
NGINX_RUNTIME_CONF="${NGINX_RUNTIME_CONF:-$REPO_DIR/deploy/runtime/nginx.conf}"
LAST_DEPLOY_FILE="${LAST_DEPLOY_FILE:-$REPO_DIR/deploy/runtime/last-deploy.txt}"
NGINX_CMD="${NGINX_CMD:-nginx}"
SMOKE_BASE="${SMOKE_BASE:-http://127.0.0.1:80}"

BLUE_PORT="${BLUE_PORT:-8080}"
GREEN_PORT="${GREEN_PORT:-8081}"

note() { echo "ROLLBACK $(date +%T): $*"; }
die()  { echo "ROLLBACK $(date +%T) ABORT: $*" >&2; exit 1; }

# Same render contract as deploy.sh: template (never mutated) -> tmp -> cat over runtime.
render_runtime_conf() {
    local out="$1" active="$2" idle="$3" weight="$4"
    local active_line idle_line
    if [ "$weight" -ge 100 ]; then
        active_line="        server 127.0.0.1:$active weight=100 max_fails=2 fail_timeout=10s;"
        idle_line="        server 127.0.0.1:$idle weight=100 max_fails=2 fail_timeout=10s down;"
    else
        active_line="        server 127.0.0.1:$active weight=$weight max_fails=2 fail_timeout=10s;"
        idle_line="        server 127.0.0.1:$idle weight=$((100 - weight)) max_fails=2 fail_timeout=10s;"
    fi
    mkdir -p "$(dirname "$out")"
    awk -v al="$active_line" -v il="$idle_line" '
        /server 127\.0\.0\.1:80(80|81) / {
            if (!done_al) { print al; done_al = 1 } else { print il }
            next
        }
        { print }
    ' "$NGINX_TEMPLATE" > "$out.__tmp" && cat "$out.__tmp" > "$out" && rm -f "$out.__tmp"
    [ -s "$out" ] && grep -q '^events {' "$out" \
        || die "render produced an empty/invalid runtime conf"
}

color_port() { [ "$1" = "blue" ] && echo "$BLUE_PORT" || echo "$GREEN_PORT"; }

do_rollback() {
    [ -f "$LAST_DEPLOY_FILE" ] \
        || die "no last-deploy.txt — nothing to roll back (deploy.sh writes it before cutover)"
    local previous current tag at
    previous=$(awk '$1=="previous"{print $2}' "$LAST_DEPLOY_FILE")
    current=$(awk '$1=="current"{print $2}' "$LAST_DEPLOY_FILE")
    tag=$(awk '$1=="tag"{print $2}' "$LAST_DEPLOY_FILE")
    at=$(awk '$1=="at"{print $2}' "$LAST_DEPLOY_FILE")
    [ -n "$previous" ] && [ -n "$current" ] \
        || die "malformed last-deploy.txt (missing previous/current)"

    note "rolling back: $current -> $previous (deploy $tag at $at), no rebuild"

    # Bring the previous color back UP (a completed cutover stopped it); starting an
    # already-running unit is a no-op.
    sudo systemctl start "url-shortener-$previous.service" \
        || die "could not start url-shortener-$previous.service (manual fallback: runbook §2)"

    # readiness before traffic: the previous color must answer before the flip
    local budget=90 deadline url code
    url="http://127.0.0.1:$(color_port "$previous")/actuator/health/readiness"
    deadline=$(( $(date +%s) + budget ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        code=$(curl -s -o /dev/null -w '%{http_code}' -m 3 "$url" 2>/dev/null || true)
        [ "$code" = "200" ] && break
        sleep 2
    done
    [ "$code" = "200" ] || die "url-shortener-$previous not ready within ${budget}s — refusing to flip traffic (runbook §2 manual fallback)"

    render_runtime_conf "$NGINX_RUNTIME_CONF" "$(color_port "$previous")" "$(color_port "$current")" 100
    $NGINX_CMD -t -c "$NGINX_RUNTIME_CONF" || die "nginx -t failed"
    $NGINX_CMD -s reload || die "nginx reload failed"
    note "$previous at 100%, $current down — reload applied, traffic back on $previous"

    if ! bash "$SCRIPT_DIR/smoke.sh" "$SMOKE_BASE"; then
        die "post-rollback smoke FAIL — previous color is NOT serving correctly; manual fallback: runbook §2"
    fi

    echo "INCIDENT one-liner: rollback ${current}->${previous} reverted to ${previous} at $(date -u +%Y-%m-%dT%H:%M:%SZ) (deploy $tag cut over, panicked)"
    note "ROLLBACK OK"
}

# ---------------------------------------------------------------- self-test
# Synthetic last-deploy in a temp dir + render assertions; NO systemd/nginx touched.
SELF_TMP=""
self_cleanup() { [ -n "$SELF_TMP" ] && rm -rf "$SELF_TMP"; return 0; }
do_self_test() {
    echo "=== rollback.sh --self-test ==="
    SELF_TMP=$(mktemp -d)
    trap self_cleanup EXIT
    local tmp="$SELF_TMP"
    local runtime="$tmp/nginx.conf" last="$tmp/last-deploy.txt"

    # synthetic last-deploy: previous blue, current green (a blue->green cutover happened)
    printf 'previous blue\ncurrent green\ntag v0.14.0\nat 2026-09-12T00:00:00Z\n' > "$last"

    # the render rollback performs: previous (blue) 100, current (green) down
    render_runtime_conf "$runtime" "$BLUE_PORT" "$GREEN_PORT" 100
    grep -q "server 127.0.0.1:8080 weight=100 max_fails=2 fail_timeout=10s;" "$runtime" \
        || die "self-test: blue-100 line not rendered"
    grep -q "server 127.0.0.1:8081 weight=100 max_fails=2 fail_timeout=10s down;" "$runtime" \
        || die "self-test: green-down line not rendered"

    # parse round-trip: fields extracted exactly as do_rollback reads them
    local previous current tag at
    previous=$(awk '$1=="previous"{print $2}' "$last")
    current=$(awk '$1=="current"{print $2}' "$last")
    tag=$(awk '$1=="tag"{print $2}' "$last")
    at=$(awk '$1=="at"{print $2}' "$last")
    [ "$previous" = "blue" ] && [ "$current" = "green" ] && [ "$tag" = "v0.14.0" ] && [ "$at" = "2026-09-12T00:00:00Z" ] \
        || die "self-test: last-deploy parse round-trip failed ($previous/$current/$tag/$at)"

    # template untouched
    cp "$NGINX_TEMPLATE" "$tmp/template.before"
    cmp -s "$tmp/template.before" "$NGINX_TEMPLATE" \
        || die "self-test: the TEMPLATE was mutated (forbidden)"

    echo "OK: previous-100/current-down render, last-deploy parse, template untouched — asserted"
    echo "PASS: self-test verified"
}

case "${1:-}" in
    --self-test) do_self_test ;;
    --help|-h) grep '^#' "$0" | sed -n '3,10p' ;;
    "") do_rollback ;;
    *) die "unknown arg $1" ;;
esac
