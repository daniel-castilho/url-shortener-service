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

# Staging dir for the validated JAR (precondition 0). Script-global, NOT function-local,
# so the EXIT trap keeps working under `set -u` after deploy() has returned. Only the
# --self-test mode may pre-set it (to a path it owns) so the test can prove cleanup;
# production deploy always creates a fresh mktemp -d and never honors an exported value.
DEPLOY_TMP_DIR=""

note() { echo "DEPLOY $(date +%T): $*" >&2; }
warn() { echo "DEPLOY $(date +%T) WARN: $*" >&2; }
die()  { echo "DEPLOY $(date +%T) ABORT: $*" >&2; exit 1; }

usage() {
    cat >&2 <<'EOF'
Usage:  scripts/deploy.sh <tag>                  deploy release <tag> (canary 10,30,100)
        scripts/deploy.sh <tag> --canary 10,30,100
        scripts/deploy.sh --check <tag>          print the plan + last deploy, touch nothing
        scripts/deploy.sh --init                 render runtime conf (blue 100 / green down)
        scripts/deploy.sh --active               print the active color from the runtime conf
        scripts/deploy.sh --self-test            render + weight assertions + abort proof
                                                in a temp dir (no systemd, no nginx, no host mutation)

Environment (host deploy):
  NGINX_CMD     how to run nginx on this host (default: 'nginx').
  GH_REPO       GitHub repo for Release assets (default daniel-castilho/url-shortener-service)
  URLS_HOME     color homes (default /opt/url-shortener)
  SMOKE_BASE    base URL the smoke probe hits after each bump (default http://127.0.0.1:80)

Canary sequence rules:
  - comma-separated integers (no spaces)
  - strictly increasing: 10,30,100
  - each in [1,100]
  - final value MUST be exactly 100
EOF
    exit 1
}

validate_canary() {
    local canary="$1"
    [[ "$canary" =~ ^[0-9]+(,[0-9]+)*$ ]] || return 1
    local -a steps
    IFS=',' read -ra steps <<< "$canary"
    local prev=-1
    for w in "${steps[@]}"; do
        (( w >= 1 && w <= 100 )) || return 1
        (( w > prev )) || return 1
        prev=$w
    done
    (( prev == 100 )) || return 1
    return 0
}

validate_canary_or_die() {
    local canary="$1"
    [[ "$canary" =~ ^[0-9]+(,[0-9]+)*$ ]] || die "invalid canary syntax: '$canary' (comma-separated integers only, no spaces)"
    local -a steps
    IFS=',' read -ra steps <<< "$canary"
    local prev=-1
    for w in "${steps[@]}"; do
        (( w >= 1 && w <= 100 )) || die "canary weight $w out of range [1,100]"
        (( w > prev )) || die "canary weights must be strictly increasing (got $prev then $w)"
        prev=$w
    done
    (( prev == 100 )) || die "final canary weight must be 100 (got $prev)"
}

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
# The download + full chain validation happens ONCE, up front (precondition 0), and the
# SAME validated bytes are then staged into the idle color (no second download, no
# wildcard re-selection) via verify-release-artifact.sh --validate-release --output-jar.

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
#   3. validate_canary rejects invalid sequences and accepts valid ones
#   4. deploy argument parsing (the real CLI parser) rejects invalid invocations
#      before any network request or host mutation
SELF_TMP=""
self_cleanup() { [ -n "$SELF_TMP" ] && rm -rf "$SELF_TMP"; return 0; }
do_self_test() {
    echo "=== deploy.sh --self-test ==="
    SELF_TMP=$(mktemp -d)
    trap self_cleanup EXIT
    local tmp="$SELF_TMP"
    local runtime="$tmp/nginx.conf"

    # --- create stub commands to intercept external calls ---
    # Each stub records its invocation to $tmp/calls/<name> (so the test can prove
    # which commands a code path attempted). gh always fails (never legitimately
    # reached). systemctl/nginx/curl/sudo are CONTROLLED: the abort-proof probe wants
    # wait_ready to fail (curl stub returns 000 by default), while the full successful
    # deployment simulation needs restart/reload/readiness/install to succeed (set
    # CURL_STUB_CODE=200 in that subshell; sudo's `install` actually copies the JAR so
    # the same-bytes guarantee can be asserted). No real host command, no network,
    # no systemd, no nginx is ever reached.
    local stub_bin="$tmp/stub_bin" calls_dir="$tmp/calls"
    mkdir -p "$stub_bin" "$calls_dir"

    cat > "$stub_bin/gh" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$calls_dir/gh"
echo "STUB gh called with: \$* (must never be reached in self-test)" >&2
exit 1
EOF
    cat > "$stub_bin/systemctl" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$calls_dir/systemctl"
echo "STUB systemctl \$* (simulated success)" >&2
exit 0
EOF
    cat > "$stub_bin/nginx" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$calls_dir/nginx"
echo "STUB nginx \$* (simulated success)" >&2
exit 0
EOF
    cat > "$stub_bin/curl" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$calls_dir/curl"
echo "STUB curl \$* (simulated http code \${CURL_STUB_CODE:-000})" >&2
echo "\${CURL_STUB_CODE:-000}"
exit 0
EOF
    cat > "$stub_bin/sudo" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$calls_dir/sudo"
if [ "\${1:-}" = "install" ]; then
    # sudo install -D -o ... -m 0644 <src> <dst> — simulate faithfully: stage the copy.
    last=""; prev=""
    for a in "\$@"; do prev="\$last"; last="\$a"; done
    mkdir -p "\$(dirname "\$last")" || exit 1
    cp "\$prev" "\$last" || exit 1
    echo "STUB sudo install: staged \$prev -> \$last (simulated)" >&2
    exit 0
fi
echo "STUB sudo \$* (simulated success)" >&2
exit 0
EOF
    chmod +x "$stub_bin"/*
    # Prepend stub_bin to PATH so stubs are used instead of real commands
    PATH="$stub_bin:$PATH"

    # --- render weight assertions ---
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

    # --- abort proof ---
    if READY_BUDGET_SECONDS=1 wait_ready 65534 1; then
        die "self-test: wait_ready unexpectedly succeeded against a dead port"
    fi
    render_runtime_conf "$runtime" "$BLUE_PORT" "$GREEN_PORT" 100
    [ "$(active_color "$runtime")" = "blue" ] || die "self-test: abort render did not restore blue"

    # --- template untouched ---
    cp "$NGINX_TEMPLATE" "$tmp/template.before"
    render_runtime_conf "$runtime" "$GREEN_PORT" "$BLUE_PORT" 50 >/dev/null 2>&1 || true
    cmp -s "$tmp/template.before" "$NGINX_TEMPLATE" \
        || die "self-test: the TEMPLATE was mutated by rendering (forbidden)"

    # --- validate_canary assertions ---
    local canary
    # valid documented sequence
    canary="10,30,100"
    validate_canary "$canary" || die "self-test: documented canary sequence rejected"

    # valid alternative sequences
    validate_canary "5,50,100" || die "self-test: valid alt sequence rejected"
    validate_canary "100" || die "self-test: single-step 100 rejected"
    validate_canary "1,2,3,100" || die "self-test: multi-step sequence rejected"

    # invalid: non-numeric
    if validate_canary "10,foo,100" 2>/dev/null; then die "self-test: non-numeric accepted"; fi
    # invalid: out of range
    if validate_canary "0,50,100" 2>/dev/null; then die "self-test: weight 0 accepted"; fi
    if validate_canary "10,101,100" 2>/dev/null; then die "self-test: weight >100 accepted"; fi
    # invalid: decreasing
    if validate_canary "30,20,100" 2>/dev/null; then die "self-test: decreasing weights accepted"; fi
    # invalid: duplicate
    if validate_canary "10,30,30,100" 2>/dev/null; then die "self-test: duplicate weights accepted"; fi
    # invalid: doesn't end at 100
    if validate_canary "10,30,90" 2>/dev/null; then die "self-test: sequence not ending at 100 accepted"; fi
    # invalid: empty
    if validate_canary "" 2>/dev/null; then die "self-test: empty canary accepted"; fi
    # invalid: spaces
    if validate_canary "10, 30,100" 2>/dev/null; then die "self-test: spaces in canary accepted"; fi

    # --- deploy argument parsing: exercise the REAL CLI parser ---
    # We call deploy() itself (the function behind the dispatch) in subshells.
    # die() exits the subshell only, so the self-test keeps running.
    # A fake SCRIPT_DIR carries a stub verify-release-artifact.sh that records the
    # invocation and fails, so a VALID invocation provably reaches the

    # release-validation gate (precondition 0) but never a host mutation.
    local val_scripts="$tmp/val_scripts"
    mkdir -p "$val_scripts" "$calls_dir"
    cat > "$val_scripts/verify-release-artifact.sh" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$calls_dir/verify"
echo "STUB verify-release-artifact.sh invoked: \$*" >&2
exit 1
EOF
    chmod +x "$val_scripts/verify-release-artifact.sh"

    local rc
    calls_clear() { rm -f "$calls_dir"/*; }
    # The abort-proof wait_ready above already left a curl marker; reset before
    # the parser assertions so each one proves its own "no external call" claim.
    calls_clear
    # Expects: substring to grep in stderr, then the deploy args.
    parser_must_die() {
        local expect="$1"; shift
        rc=0
        ( SCRIPT_DIR="$val_scripts" deploy "$@" ) 2>"$tmp/err" >/dev/null || rc=$?
        [ "$rc" -ne 0 ] || die "self-test: CLI parser ACCEPTED: deploy $* (expected rejection: $expect)"
        grep -qF -- "$expect" "$tmp/err" || {
            echo "side:<$(grep -o 'ABORT:.*' "$tmp/err" | head -1)>" >&2
            die "self-test: CLI rejection message missing '$expect' for: deploy $*"
        }
    }

    echo "self-test: parser — unknown option rejected before any external call"
    parser_must_die "unknown argument: --bogus" v0.17.0 --bogus
    [ -z "$(ls -A "$calls_dir")" ] || die "self-test: unknown option reached an external command before rejection"

    echo "self-test: parser — missing --canary value rejected before any external call"
    calls_clear
    parser_must_die "--canary requires a value" v0.17.0 --canary
    [ -z "$(ls -A "$calls_dir")" ] || die "self-test: missing --canary value reached an external command before rejection"

    echo "self-test: parser — malformed canary rejected before any external call"
    calls_clear
    parser_must_die "invalid canary syntax: '10,foo,100' (comma-separated integers only, no spaces)" v0.17.0 --canary 10,foo,100
    [ -z "$(ls -A "$calls_dir")" ] || die "self-test: malformed canary reached an external command before rejection"

    echo "self-test: parser — canary not ending in 100 rejected before any external call"
    calls_clear
    parser_must_die "final canary weight must be 100" v0.17.0 --canary 10,30,90
    [ -z "$(ls -A "$calls_dir")" ] || die "self-test: non-100 canary reached an external command before rejection"

    echo "self-test: parser — duplicate canary weight rejected before any external call"
    calls_clear
    parser_must_die "strictly increasing" v0.17.0 --canary 10,30,30,100
    [ -z "$(ls -A "$calls_dir")" ] || die "self-test: duplicate canary weight reached an external command before rejection"

    echo "self-test: parser — documented <tag> --canary 10,30,100 ACCEPTED, validation gate first (no host mutation)"
    calls_clear
    rc=0
    ( SCRIPT_DIR="$val_scripts" deploy v0.17.0 --canary 10,30,100 ) 2>"$tmp/err" >/dev/null || rc=$?
    [ "$rc" -ne 0 ] || die "self-test: VALID invocation completed unexpectedly (deploy v0.17.0 --canary 10,30,100)"
    grep -qF "step validate: release artifact validation failed for v0.17.0" "$tmp/err" \
        || die "self-test: valid invocation did not reach the release-validation gate"
    [ -f "$calls_dir/verify" ] || die "self-test: verify-release-artifact.sh was not invoked for a valid deploy command"
    for cmd in gh systemctl nginx curl sudo; do
        [ -f "$calls_dir/$cmd" ] || continue
        cp "$calls_dir/$cmd" "$tmp/host-call-$cmd"
    done
    [ -z "$(ls "$tmp"/host-call-* 2>/dev/null)" ] \
        || die "self-test: VALID invocation attempted a host mutation before validation (see $tmp/host-call-*)"

    echo "self-test: parser — default canary 10,30,100 ACCEPTED, validation gate first (no host mutation)"
    calls_clear
    rc=0
    ( SCRIPT_DIR="$val_scripts" deploy v0.17.0 ) 2>"$tmp/err" >/dev/null || rc=$?
    [ "$rc" -ne 0 ] || die "self-test: VALID invocation completed unexpectedly (deploy v0.17.0)"
    grep -qF "step validate: release artifact validation failed for v0.17.0" "$tmp/err" \
        || die "self-test: default-canary invocation did not reach the release-validation gate"

    # --- successful mocked deployment: same validated bytes staged, temp cleaned up ---
    # The verifier is stubbed SUCCESSFUL and writes a known fixture JAR to the
    # --output-jar path; smoke.sh is stubbed; systemctl/nginx stubs succeed; curl
    # reports readiness 200; sudo's `install` faithfully stages the copy. With
    # DWELL_SECONDS=0 the full canary (10/30/100) runs without sleeping. This proves
    # the whole path: parser -> validation gate -> runtime --init -> install of the
    # EXACT validated bytes -> readiness -> nginx bumps -> smoke -> cutover.
    echo "self-test: successful mocked deploy — same validated JAR staged, temp staging cleaned on success"
    local fixture="$tmp/fixture.jar"
    head -c 4096 /dev/urandom > "$fixture"
    local fixture_sha; fixture_sha="$(sha256sum "$fixture" | awk '{print $1}')"
    local ok_scripts="$tmp/ok_scripts"
    mkdir -p "$ok_scripts" "$tmp/deploy-stage"
    cat > "$ok_scripts/verify-release-artifact.sh" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$calls_dir/verify"
out=""; prev=""
for a in "\$@"; do
    [ "\$prev" = "--output-jar" ] && out="\$a"
    prev="\$a"
done
[ -n "\$out" ] || { echo "STUB verify(ok): missing --output-jar value" >&2; exit 1; }
cp "$fixture" "\$out" || exit 1
echo "STUB verify(ok): validated and wrote \$out" >&2
exit 0
EOF
    cat > "$ok_scripts/smoke.sh" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$calls_dir/smoke"
echo "STUB smoke \$* (simulated success)" >&2
exit 0
EOF
    chmod +x "$ok_scripts/verify-release-artifact.sh" "$ok_scripts/smoke.sh"

    calls_clear
    rc=0
    # All test vars are set via `export` inside the subshell (NOT as prefixes to
    # deploy): (a) prefix assignments are restored after a function returns, which would
    # make the EXIT trap reference the reverted (empty) DEPLOY_TMP_DIR and skip cleanup;
    # (b) plain (unexported) assignments would never reach CHILD processes — wait_ready's
    # curl is the stub script itself, which must see CURL_STUB_CODE=200 to report ready.
    ( export SCRIPT_DIR="$ok_scripts" CURL_STUB_CODE=200 DWELL_SECONDS=0 \
            READY_BUDGET_SECONDS=5 \
            NGINX_RUNTIME_CONF="$tmp/deploy/runtime/nginx.conf" \
            LAST_DEPLOY_FILE="$tmp/deploy/runtime/last-deploy.txt" \
            URLS_HOME="$tmp/homes" DEPLOY_TMP_DIR="$tmp/deploy-stage"; \
      deploy v0.17.0 --canary 10,30,100 ) 2>"$tmp/err" >/dev/null || rc=$?
    [ "$rc" -eq 0 ] || { echo "--- deploy stderr ---" >&2; sed -n '/precondition 0/,$p' "$tmp/err" >&2; die "self-test: successful mocked deploy exited $rc (expected 0)"; }
    [ -f "$tmp/homes/green/url-shortener.jar" ] || die "self-test: staged JAR not found under the idle color home"
    local staged_sha; staged_sha="$(sha256sum "$tmp/homes/green/url-shortener.jar" | awk '{print $1}')"
    [ "$staged_sha" = "$fixture_sha" ] \
        || die "self-test: staged/installed JAR bytes differ from the validated fixture ($staged_sha != $fixture_sha)"
    [ ! -e "$tmp/deploy-stage" ] || die "self-test: temporary staging dir was NOT cleaned on success"
    [ -f "$tmp/deploy/runtime/nginx.conf" ] || die "self-test: runtime conf was not created during the mocked deploy"
    [ -f "$tmp/deploy/runtime/last-deploy.txt" ] || die "self-test: last-deploy record was not written during the mocked deploy"
    grep -q "DEPLOY OK" "$tmp/err" || die "self-test: mocked deploy did not report DEPLOY OK"
    # systemctl is ALWAYS invoked through `sudo systemctl`, so the sudo stub absorbs it
    # (only the `sudo` marker is written); nginx is invoked directly via $NGINX_CMD.
    for cmd in verify sudo nginx curl smoke; do
        [ -f "$calls_dir/$cmd" ] || die "self-test: mocked deploy never invoked stub '$cmd'"
    done

    # --- validation failure leaves runtime config + deploy state untouched ---
    echo "self-test: validation failure leaves runtime config + deploy state untouched"
    mkdir -p "$tmp/fail-stage"
    calls_clear
    rc=0
    ( export SCRIPT_DIR="$val_scripts" \
            NGINX_RUNTIME_CONF="$tmp/failcheck/runtime/nginx.conf" \
            LAST_DEPLOY_FILE="$tmp/failcheck/runtime/last-deploy.txt" \
            URLS_HOME="$tmp/failcheck-homes" DEPLOY_TMP_DIR="$tmp/fail-stage"; \
      deploy v0.17.0 --canary 10,30,100 ) 2>"$tmp/err" >/dev/null || rc=$?
    [ "$rc" -ne 0 ] || die "self-test: validation-failure deploy unexpectedly succeeded"
    grep -qF "step validate: release artifact validation failed for v0.17.0" "$tmp/err" \
        || die "self-test: validation-failure deploy did not fail at the release-validation gate"
    [ ! -e "$tmp/failcheck/runtime/nginx.conf" ] || die "self-test: validation failure created a runtime conf (host mutation before validation)"
    [ ! -e "$tmp/failcheck/runtime/last-deploy.txt" ] || die "self-test: validation failure wrote a last-deploy record (host mutation before validation)"
    [ ! -e "$tmp/failcheck-homes/green/url-shortener.jar" ] || die "self-test: validation failure staged a JAR (host mutation before validation)"
    [ ! -e "$tmp/fail-stage" ] || die "self-test: temporary staging dir was NOT cleaned on validation failure"
    [ -f "$calls_dir/verify" ] || die "self-test: verify stub was not reached on the validation-failure path"
    for cmd in systemctl nginx curl sudo; do
        [ -f "$calls_dir/$cmd" ] && die "self-test: validation failure attempted host mutation via stub '$cmd'"
    done

    echo "OK: render, abort render, validate_canary, CLI parser (reject+accept), same-bytes staging, cleanup on success+failure — all asserted"
    echo "PASS: self-test verified"
}

# ---------------------------------------------------------------- main deploy
deploy() {
    local tag="${1:?usage}" canary="10,30,100"
    shift
    # Parse optional --canary flag
    while [ $# -gt 0 ]; do
        case "$1" in
            --canary)
                [ $# -ge 2 ] || die "--canary requires a value"
                canary="$2"
                shift 2
                ;;
            *)
                die "unknown argument: $1 (only --canary is supported)"
                ;;
        esac
    done
    validate_canary_or_die "$canary"

    # Read-only preflight checks (no host mutation)
    [ -f "$NGINX_TEMPLATE" ] || die "nginx template missing: $NGINX_TEMPLATE"

    # PRECONDITION 0: Validate release artifact BEFORE any host mutation
    # This includes downloading and fully validating the artifact chain.
    # On failure, no host state has been modified.
    note "precondition 0/4: validate full release artifact chain for $tag"
    # Staging: a fresh temp DIRECTORY (mktemp -d, not a file) with the output path
    # inside it. The verifier's --output-jar requires a destination that does NOT yet
    # exist inside an EXISTING parent dir — a `mktemp --suffix=.jar` would pre-create the
    # file and make every deployment fail at the gate. Cleanup lives on the global
    # DEPLOY_TMP_DIR (see its declaration), so the EXIT trap survives deploy() returning
    # and never references a function-local under `set -u`.
    if [ "$MODE" != "--self-test" ] || [ -z "$DEPLOY_TMP_DIR" ]; then
        DEPLOY_TMP_DIR="$(mktemp -d)"
    fi
    trap '[ -n "$DEPLOY_TMP_DIR" ] && rm -rf "$DEPLOY_TMP_DIR"' EXIT
    local validated_jar="$DEPLOY_TMP_DIR/validated.jar"
    bash "$SCRIPT_DIR/verify-release-artifact.sh" --validate-release "$tag" --output-jar "$validated_jar" \
        || die "step validate: release artifact validation failed for $tag"
    note "validated JAR staged at $validated_jar"

    # Now safe to proceed with host mutation (runtime conf, services, nginx)
    local runtime="${NGINX_RUNTIME_CONF:-$NGINX_RUNTIME_CONF_DEFAULT}"
    local last_deploy="${LAST_DEPLOY_FILE:-$LAST_DEPLOY_FILE_DEFAULT}"

    [ -f "$runtime" ] || { note "runtime conf missing — running --init"; do_init; }

    local active idle
    active=$(active_color "$runtime")
    [ "$active" = "blue" ] || [ "$active" = "green" ] \
        || die "cannot determine active color from $runtime"
    if [ "$active" = "blue" ]; then idle="green"; else idle="blue"; fi
    note "active=$active idle=$idle"

    # precondition 1/4: stage the validated JAR into the idle color (no restart yet)
    note "precondition 1/4: stage validated JAR into $URLS_HOME/$idle"
    sudo install -D -o urlshortener -g urlshortener -m 0644 "$validated_jar" "$URLS_HOME/$idle/url-shortener.jar" \
        || abort "$idle" "$active" "step stage: could not install jar into $URLS_HOME/$idle" "$runtime"

    # precondition 2/4: start the idle color and wait readiness (named budget)
    note "precondition 2/4: start url-shortener-$idle + wait readiness (budget ${READY_BUDGET_SECONDS}s)"
    sudo systemctl restart "url-shortener-$idle.service" \
        || abort "$idle" "$active" "step systemctl: url-shortener-$idle.service failed to (re)start" "$runtime"
    wait_ready "$(color_port "$idle")" \
        || abort "$idle" "$active" "step readiness: url-shortener-$idle NOT ready within ${READY_BUDGET_SECONDS}s (port $(color_port "$idle"))" "$runtime"
    note "$idle READY on :$(color_port "$idle")"

    # record deploy intent BEFORE the first weight flip (rollback.sh reads this)
    { echo "previous $active"; echo "current $idle"; echo "tag $tag"; echo "at $(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$last_deploy"

    # precondition 3/4 + canary bumps: nginx -t BEFORE every reload, smoke after every bump
    note "precondition 3/4: nginx -t on the initial render"
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
    *)           deploy "$@" ;;
esac
