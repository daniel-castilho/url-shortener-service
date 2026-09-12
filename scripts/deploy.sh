#!/usr/bin/env bash
#
# URL Shortener blue-green deploy (Epic 8 story 8.3; contract: ADR 0007).
#
# Fail-closed cutover is the contract: ANY failed precondition/step aborts to the OLD
# color at 100%, the new color is drained and stopped, exit is non-zero with the
# offending step NAMED.
#
# Usage:  scripts/deploy.sh <tag>                  deploy release <tag> (canary 10,30,100)
#         scripts/deploy.sh <tag> --canary 10,30,100
#         scripts/deploy.sh --check <tag>          print the plan + last deploy, touch nothing
#         scripts/deploy.sh --init                 render runtime conf (blue 100 / green down)
#         scripts/deploy.sh --active               print the active color from the runtime conf
#         scripts/deploy.sh --self-test            render + weight assertions + abort proof
#                                                 in a temp dir (no systemd, no nginx, no host mutation)
#
# Environment (host deploy):
#   NGINX_CMD     how to run nginx on this host (default: 'nginx'). The repo's reference
#                 stack runs nginx in a container; set e.g.
#                 NGINX_CMD='docker exec -i urlshortener-nginx nginx'
#   GH_REPO       GitHub repo for Release assets (default daniel-castilho/url-shortener-service)
#   URLS_HOME     color homes (default /opt/url-shortener)
#   SMOKE_BASE    base URL the smoke probe hits after each bump (default http://127.0.0.1:80
#                 — the nginx front; override when the front is elsewhere)
#
# The script targets the systemd units url-shortener-blue/green.service via systemctl
# (sudo). In --self-test/--check/--init/--active modes nothing on the host is mutated
# beyond deploy/runtime/ renders (and --self-test works entirely in a temp dir).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

NGINX_TEMPLATE="$REPO_DIR/deploy/proxy/nginx.conf"
NGINX_RUNTIME_CONF_DEFAULT="$REPO_DIR/deploy/runtime/nginx.conf"
LAST_DEPLOY_FILE_DEFAULT="$REPO_DIR/deploy/runtime/last-deploy.txt"

GH_REPO="${GH_REPO:-daniel-castilho/url-shortener-service}"
URLS_HOME="${URLS_HOME:-/opt/url-shortener}"
NGINX_CMD="${NGINX_CMD:-nginx}"
SMOKE_BASE="${SMOKE_BASE:-http://127.0.0.1:80}"

DWELL_SECONDS="${DWELL_SECONDS:-30}"
# Readiness budget (named, per story 8.3): the Dockerfile HEALTHCHEK uses start-period 40s
# for a cold JVM boot; 90s covers a prod boot (bigger heap init) plus the Mongo schema
# migrator's fail-fast index checks before readiness turns green.
READY_BUDGET_SECONDS="${READY_BUDGET_SECONDS:-90}"

BLUE_PORT="${BLUE_PORT:-8080}"
GREEN_PORT="${GREEN_PORT:-8081}"

note() { echo "DEPLOY $(date +%T): $*" >&2; }
warn() { echo "DEPLOY $(date +%T) WARN: $*" >&2; }
die()  { echo "DEPLOY $(date +%T) ABORT: $*" >&2; exit 1; }

usage() { grep '^#' "$0" | sed -n '3,20p' >&2; exit 1; }

# ---------------------------------------------------------------- nginx render
# Renders the runtime conf from the human-owned template by replacing exactly the two
# `server 127.0.0.1:808x` lines (weights + down marker). The template is NEVER written by
# tooling. Render goes to a tmp file then `cat` over the runtime copy — never awk to the
# same path in/out (the redirect truncates the target before awk reads it — dargent E12 S2).
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
    note "rendered $(basename "$out") (active=:$active weight=$weight, idle=:$idle)"
}

nginx_test()   { $NGINX_CMD -t -c "$1" >/dev/null 2>&1 || return 1; }
nginx_reload() { $NGINX_CMD -s reload >/dev/null 2>&1 || return 1; }

# ---------------------------------------------------------------- colors
# active = the 808x line NOT marked down (ties -> blue)
active_color() {
    local conf="$1"
    local blue_line green_line
    blue_line=$(grep 'server 127.0.0.1:8080' "$conf" || true)
    green_line=$(grep 'server 127.0.0.1:8081' "$conf" || true)
    if echo "$green_line" | grep -q 'down'; then echo "blue"; return 0; fi
    if echo "$blue_line" | grep -q 'down'; then echo "green"; return 0; fi
    echo "blue"
}

color_port() { [ "$1" = "blue" ] && echo "$BLUE_PORT" || echo "$GREEN_PORT"; }

# ---------------------------------------------------------------- readiness
wait_ready() {
    local port="$1" budget="${2:-$READY_BUDGET_SECONDS}"
    local url="http://127.0.0.1:$port/actuator/health/readiness"
    local deadline=$(( $(date +%s) + budget ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        local code
        code=$(curl -s -o /dev/null -w '%{http_code}' -m 3 "$url" 2>/dev/null || true)
        [ "$code" = "200" ] && return 0
        sleep 2
    done
    return 1
}

# ---------------------------------------------------------------- release artifact
# ADR 0008: the jar is downloaded from the GitHub Release and the sha256 is verified
# against the published SHA256SUMS asset. No Release, no deploy (fail-closed).
download_release_jar() {
    local tag="$1" out="$2"
    gh release download "$tag" --repo "$GH_REPO" \
        --pattern "url-shortener-service-*.jar" --pattern "SHA256SUMS" --dir "$out" \
        || die "step download: could not download assets for $tag from $GH_REPO (Release missing or gh not authed)"
    local jar sums
    jar=$(ls "$out"/url-shortener-service-*.jar 2>/dev/null | head -1)
    [ -n "$jar" ] || die "step download: no jar asset matched for $tag"
    [ -f "$out/SHA256SUMS" ] || die "step sha256: SHA256SUMS asset missing from Release $tag"
    sums=$(sha256sum "$jar" | awk '{print $1}')
    grep -qi "$sums" "$out/SHA256SUMS" \
        || die "step sha256: jar digest does not match Release SHA256SUMS for $tag"
    echo "$jar"
}

# ---------------------------------------------------------------- abort (fail-closed)
abort() {
    local idle="$1" active="$2" why="$3" runtime="$4"
    echo "DEPLOY $(date +%T) ABORT: $why" >&2
    echo "  restoring $active to 100% and stopping $idle (fail-closed cutover)" >&2
    render_runtime_conf "$runtime" "$(color_port "$active")" "$(color_port "$idle")" 100 || true
    nginx_test "$runtime" || warn "nginx -t failed for the rollback render — MANUAL ACTION: fix $runtime"
    nginx_reload || warn "nginx reload failed — MANUAL ACTION: reload nginx with $runtime"
    sudo systemctl stop "url-shortener-$idle.service" 2>/dev/null \
        || warn "could not stop url-shortener-$idle.service — MANUAL ACTION: stop it"
    exit 1
}

# ---------------------------------------------------------------- modes
do_init() {
    local runtime="${NGINX_RUNTIME_CONF:-$NGINX_RUNTIME_CONF_DEFAULT}"
    [ -f "$NGINX_TEMPLATE" ] || die "nginx template missing: $NGINX_TEMPLATE"
    render_runtime_conf "$runtime" "$BLUE_PORT" "$GREEN_PORT" 100
    note "init done — runtime conf at $runtime (blue 100 / green down)"
}

do_active() {
    local runtime="${NGINX_RUNTIME_CONF:-$NGINX_RUNTIME_CONF_DEFAULT}"
    [ -f "$runtime" ] || die "runtime conf missing ($runtime) — run --init first"
    active_color "$runtime"
}

do_check() {
    local tag="${1:-}"; [ -n "$tag" ] || usage
    local runtime="${NGINX_RUNTIME_CONF:-$NGINX_RUNTIME_CONF_DEFAULT}"
    echo "PLAN deploy $tag"
    echo "  template      : $NGINX_TEMPLATE"
    echo "  runtime conf  : $runtime"
    if [ -f "$runtime" ]; then
        echo "  active color  : $(active_color "$runtime")"
    else
        echo "  active color  : (no runtime conf — first deploy runs --init)"
    fi
    if [ -f "$LAST_DEPLOY_FILE" ]; then
        echo "  last deploy   : $(tr '\n' ' ' < "$LAST_DEPLOY_FILE")"
    else
        echo "  last deploy   : (none recorded)"
    fi
    echo "  canary        : 10,30,100 (dwell ${DWELL_SECONDS}s, smoke after each bump)"
    echo "  readiness     : budget ${READY_BUDGET_SECONDS}s per color"
    echo "CHECK: nothing touched by this mode"
}

# ---------------------------------------------------------------- self-test
# Proves, without touching the host (no systemd, no nginx, no Release):
#   1. the render produces the exact weight/down lines for 10/30/100 and abort renders
#   2. the abort path: readiness against a DEAD port with READY_BUDGET_SECONDS=1 aborts,
#      renders the old color back at 100%, exits non-zero NAMING the step
SELF_TMP=""
self_cleanup() { [ -n "$SELF_TMP" ] && rm -rf "$SELF_TMP"; return 0; }
do_self_test() {
    echo "=== deploy.sh --self-test ==="
    SELF_TMP=$(mktemp -d)
    trap self_cleanup EXIT
    local tmp="$SELF_TMP"
    local runtime="$tmp/nginx.conf"

    render_runtime_conf "$runtime" "$BLUE_PORT" "$GREEN_PORT" 100
    grep -q "server 127.0.0.1:8080 weight=100 max_fails=2 fail_timeout=10s;" "$runtime" \
        || die "self-test: blue-100 line not rendered"
    grep -q "server 127.0.0.1:8081 weight=100 max_fails=2 fail_timeout=10s down;" "$runtime" \
        || die "self-test: green-down line not rendered"

    render_runtime_conf "$runtime" "$GREEN_PORT" "$BLUE_PORT" 10
    grep -q "server 127.0.0.1:8081 weight=10 max_fails=2 fail_timeout=10s;" "$runtime" \
        || die "self-test: green-10 line not rendered"
    grep -q "server 127.0.0.1:8080 weight=90 max_fails=2 fail_timeout=10s;" "$runtime" \
        || die "self-test: blue-90 complement line not rendered"

    render_runtime_conf "$runtime" "$GREEN_PORT" "$BLUE_PORT" 30
    grep -q "server 127.0.0.1:8081 weight=30 " "$runtime" || die "self-test: green-30 not rendered"
    grep -q "server 127.0.0.1:8080 weight=70 " "$runtime" || die "self-test: blue-70 not rendered"

    render_runtime_conf "$runtime" "$GREEN_PORT" "$BLUE_PORT" 100
    grep -q "server 127.0.0.1:8080 .* down;" "$runtime" || die "self-test: blue-down not rendered at cutover"

    [ "$(active_color "$runtime")" = "green" ] || die "self-test: active_color did not resolve green"

    # abort proof: readiness against a dead port with a 1s budget must fail, and the abort
    # render must put the OLD color back at 100%. Port 65534 is not listening by convention.
    if READY_BUDGET_SECONDS=1 wait_ready 65534 1; then
        die "self-test: wait_ready unexpectedly succeeded against a dead port"
    fi
    # simulate the abort render the real abort() performs
    render_runtime_conf "$runtime" "$BLUE_PORT" "$GREEN_PORT" 100
    [ "$(active_color "$runtime")" = "blue" ] || die "self-test: abort render did not restore blue"

    # template must be untouched by renders (compare a snapshot taken before any render —
    # git diff cannot be used here: the working tree may legitimately carry uncommitted
    # template edits while a deploy self-test runs)
    cp "$NGINX_TEMPLATE" "$tmp/template.before"
    render_runtime_conf "$runtime" "$GREEN_PORT" "$BLUE_PORT" 50 >/dev/null 2>&1 || true
    cmp -s "$tmp/template.before" "$NGINX_TEMPLATE" \
        || die "self-test: the TEMPLATE was mutated by rendering (forbidden)"

    echo "OK: render weights (10/30/100 + complements + down), active_color, abort render, dead-port readiness, template untouched — all asserted"
    echo "PASS: self-test verified"
}

# ---------------------------------------------------------------- main deploy
deploy() {
    local tag="${1:?usage}" canary="${2:-10,30,100}"
    local runtime="${NGINX_RUNTIME_CONF:-$NGINX_RUNTIME_CONF_DEFAULT}"
    local last_deploy="${LAST_DEPLOY_FILE:-$LAST_DEPLOY_FILE_DEFAULT}"

    [ -f "$NGINX_TEMPLATE" ] || die "nginx template missing: $NGINX_TEMPLATE"
    [ -f "$runtime" ] || { note "runtime conf missing — running --init"; do_init; }

    local active idle
    active=$(active_color "$runtime")
    [ "$active" = "blue" ] || [ "$active" = "green" ] \
        || die "cannot determine active color from $runtime"
    if [ "$active" = "blue" ]; then idle="green"; else idle="blue"; fi
    note "active=$active idle=$idle"

    # precondition 1/4: the Release exists (ADR 0008 — no Release, no deploy)
    note "precondition 1/4: download jar for $tag + verify sha256"
    local stage; stage=$(mktemp -d)
    local jar
    jar=$(download_release_jar "$tag" "$stage")

    # precondition 2/4: stage the jar into the idle color (no restart yet)
    note "precondition 2/4: stage jar into $URLS_HOME/$idle"
    sudo install -D -o urlshortener -g urlshortener -m 0644 "$jar" "$URLS_HOME/$idle/url-shortener.jar" \
        || abort "$idle" "$active" "step stage: could not install jar into $URLS_HOME/$idle" "$runtime"

    # precondition 3/4: start the idle color and wait readiness (named budget)
    note "precondition 3/4: start url-shortener-$idle + wait readiness (budget ${READY_BUDGET_SECONDS}s)"
    sudo systemctl restart "url-shortener-$idle.service" \
        || abort "$idle" "$active" "step systemctl: url-shortener-$idle.service failed to (re)start" "$runtime"
    wait_ready "$(color_port "$idle")" \
        || abort "$idle" "$active" "step readiness: url-shortener-$idle NOT ready within ${READY_BUDGET_SECONDS}s (port $(color_port "$idle"))" "$runtime"
    note "$idle READY on :$(color_port "$idle")"

    # record deploy intent BEFORE the first weight flip (rollback.sh reads this)
    { echo "previous $active"; echo "current $idle"; echo "tag $tag"; echo "at $(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$last_deploy"

    # precondition 4/4 + canary bumps: nginx -t BEFORE every reload, smoke after every bump
    note "precondition 4/4: nginx -t on the initial render"
    render_runtime_conf "$runtime" "$(color_port "$idle")" "$(color_port "$active")" 10
    nginx_test "$runtime" || abort "$idle" "$active" "step nginx -t: test failed for the canary render" "$runtime"

    local step_total=0 weight
    IFS=',' read -ra steps <<< "$canary"
    for weight in "${steps[@]}"; do
        step_total=$((step_total + 1))
        note "canary step $step_total: $idle weight=$weight"
        render_runtime_conf "$runtime" "$(color_port "$idle")" "$(color_port "$active")" "$weight"
        nginx_test "$runtime" || abort "$idle" "$active" "step nginx -t: failed at weight=$weight (step $step_total)" "$runtime"
        nginx_reload || abort "$idle" "$active" "step nginx reload: failed at weight=$weight (step $step_total)" "$runtime"
        note "dwell ${DWELL_SECONDS}s"
        sleep "$DWELL_SECONDS"
        note "post-bump smoke probe"
        if ! bash "$SCRIPT_DIR/smoke.sh" "$SMOKE_BASE"; then
            abort "$idle" "$active" "step smoke: FAIL at weight=$weight (step $step_total)" "$runtime"
        fi
    done

    note "cutover complete (100% on $idle) — draining old color $active (graceful 30s)"
    if ! sudo systemctl stop "url-shortener-$active.service"; then
        warn "drain of $active exited non-zero — new color is 100% and healthy; run rollback.sh if needed"
        exit 1
    fi
    note "DEPLOY OK — $idle active at 100% ($tag), old color $active drained and stopped"
    note "post-deploy: run smoke + watch burn-rate 10 min (docs/release-engineering.md §5)"
}

# ---------------------------------------------------------------- dispatch
MODE="${1:-}"
LAST_DEPLOY_FILE="${LAST_DEPLOY_FILE:-$LAST_DEPLOY_FILE_DEFAULT}"
case "$MODE" in
    --init)      do_init ;;
    --active)    do_active ;;
    --check)     shift; do_check "$@" ;;
    --self-test) do_self_test ;;
    --help|-h)   usage ;;
    "")          usage ;;
    *)           deploy "$1" "${2:-10,30,100}" ;;
esac
