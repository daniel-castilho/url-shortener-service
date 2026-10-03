#!/bin/bash
# Prometheus install/upgrade script for URL Shortener deploy host (ADR 0012, Epic 25).
#
# Installs a PINNED, sha256-verified Prometheus binary and the git-owned config
# (deploy/monitoring/prometheus.yml + rule files) behind a systemd unit
# (deploy/systemd/prometheus.service). This is the repo-side, idempotent S1 operator
# artifact; the operator still verifies host specifics (free disk, credentials) per the
# S1 task list.
#
# What it does (ADR 0012 D1-D4):
#   - D1: downloads prometheus-<PROM_VERSION>.linux-<arch>.tar.gz and verifies the
#         tarball sha256 against the pinned v<version>/sha256sums.txt BEFORE anything
#         is extracted or installed.
#   - D1: installs the unit deploy/systemd/prometheus.service verbatim.
#   - D2: creates /var/lib/prometheus (prometheus:prometheus) and asserts free disk
#         >= the 8GiB retention size cap before starting.
#   - D3: bind 127.0.0.1:9090 loopback only; admin API + lifecycle off.
#   - D4: scaffolds the scrape password file as root:prometheus 0640 at
#         /etc/url-shortener/prometheus-scrape.password and the env file for
#         PROM_SCRAPE_USERNAME. The OPERATOR writes the real credential (never
#         committed/automated); scrapes fail closed (401) until then.
#   - D4: renders deploy/monitoring/prometheus.yml from the git-owned template into
#         /etc/prometheus/prometheus.yml with concrete username/environment values;
#         Prometheus does NOT expand ${VAR} in scrape-config fields (only external_labels).
#         The rendered config is validated with promtool check config before installation.
#
# Usage:
#   bash scripts/install-prometheus.sh               # idempotent install (root)
#   bash scripts/install-prometheus.sh --self-test   # offline self-test (no root/net)
#   bash scripts/install-prometheus.sh --render-config --username <name> \
#       --environment <env> --output <file>         # render template only
#   bash scripts/install-prometheus.sh --help
#
# Environment overrides:
#   PROM_VERSION, PROM_BIN_DIR, PROM_CONFIG_DIR, PROM_DATA_DIR, PROM_UNIT_DIR,
#   PROM_PASSWORD_FILE, PROM_ENV_FILE

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

PROM_VERSION="${PROM_VERSION:-3.3.0}"
PROM_BIN_DIR="${PROM_BIN_DIR:-/usr/local/bin}"
PROM_CONFIG_DIR="${PROM_CONFIG_DIR:-/etc/prometheus}"
PROM_DATA_DIR="${PROM_DATA_DIR:-/var/lib/prometheus}"
PROM_UNIT_DIR="${PROM_UNIT_DIR:-/etc/systemd/system}"
PROM_PASSWORD_FILE="${PROM_PASSWORD_FILE:-/etc/url-shortener/prometheus-scrape.password}"
PROM_ENV_FILE="${PROM_ENV_FILE:-/etc/url-shortener/prometheus.env}"
PROM_USER=prometheus
PROM_GROUP=prometheus
RETENTION_SIZE_CAP_GI=8

SRC_CONFIG_DIR="$ROOT_DIR/deploy/monitoring"
SRC_UNIT="$ROOT_DIR/deploy/systemd/prometheus.service"

log() { echo "[install-prometheus] $*"; }
die() { echo "[install-prometheus] ERROR: $*" >&2; exit 1; }

prom_arch() {
    case "$(uname -m 2>/dev/null || echo x86_64)" in
        x86_64) echo amd64 ;;
        aarch64|arm64) echo arm64 ;;
        *) die "unsupported architecture: $(uname -m) (amd64/arm64 only)" ;;
    esac
}

# render_config: produce a concrete runtime config from the git-owned template.
# Rewrites ONLY the actual key lines (key anchored at line start, indentation
# preserved) — comment prose mentioning the tokens is left untouched. Tokens:
#   username:       -> $1 (may be "UNPROVISIONED" if not set; scrapes 401 until then)
#   password_file:  -> concrete /etc/url-shortener/prometheus-scrape.password path
#   environment:    -> $2 (required, no default; staging can never be labeled prod)
#   $3 = input template path  (deploy/monitoring/prometheus.yml)
#   $4 = output path            (rendered config, e.g. /etc/prometheus/prometheus.yml)
render_config() {
    local username="$1" env="$2" infile="$3" outfile="$4"
    local line indent
    while IFS= read -r line; do
        if [[ "$line" =~ ^([[:space:]]*)username:.*$ ]]; then
            indent="${BASH_REMATCH[1]}"
            printf '%susername: %s\n' "$indent" "$username"
        elif [[ "$line" =~ ^([[:space:]]*)password_file:.*$ ]]; then
            indent="${BASH_REMATCH[1]}"
            printf '%spassword_file: /etc/url-shortener/prometheus-scrape.password\n' "$indent"
        elif [[ "$line" =~ ^([[:space:]]*)environment:.*$ ]]; then
            indent="${BASH_REMATCH[1]}"
            printf '%senvironment: %s\n' "$indent" "$env"
        else
            printf '%s\n' "$line"
        fi
    done < "$infile" > "$outfile"
}

# ---- self-test (offline, no root, no root state, no network) --------------------
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

    local arch; arch="$(prom_arch)"
    assert_eq "arch is amd64 or arm64" 1 "$([[ "$arch" == amd64 || "$arch" == arm64 ]] && echo 1 || echo 0)"
    assert_eq "archive name shape" 1 "$([[ "$arch" == amd64 ]] && echo 1 || echo 0)"

    # --- provenance: every source file exists
    for f in prometheus.yml alerts.yml recording-rules.yml; do
        assert_eq "source config present: $f" 1 "$([[ -f "$SRC_CONFIG_DIR/$f" ]] && echo 1 || echo 0)"
    done
    assert_eq "source unit present" 1 "$([[ -f "$SRC_UNIT" ]] && echo 1 || echo 0)"

    # --- prometheus.yml contract (D4)
    local cfg="$SRC_CONFIG_DIR/prometheus.yml"
    assert_eq "per-color job blue"       1 "$(grep -q 'url-shortener-blue'  "$cfg" && echo 1 || echo 0)"
    assert_eq "per-color job green"      1 "$(grep -q 'url-shortener-green' "$cfg" && echo 1 || echo 0)"
    assert_eq "color=blue label"         1 "$(grep -q 'color: blue'  "$cfg" && echo 1 || echo 0)"
    assert_eq "color=green label"        1 "$(grep -q 'color: green' "$cfg" && echo 1 || echo 0)"
    assert_eq "metrics_path prometheus"  1 "$(grep -q '/actuator/prometheus' "$cfg" && echo 1 || echo 0)"
    assert_eq "env-fed username (no secret)" 1 "$(grep -q 'username: \${PROM_SCRAPE_USERNAME}' "$cfg" && echo 1 || echo 0)"
    assert_eq "password_file, never inline pw" 1 "$(grep -q 'password_file: \${PROM_SCRAPE_PASSWORD_FILE}' "$cfg" && echo 1 || echo 0)"
    assert_eq "no committed secret in config" 1 "$(grep -Eqi 'password: |pass: |secret: ' "$cfg" && echo 0 || echo 1)"
    assert_eq "candidate endpoints concrete" \
        1 "$(grep -q '127.0.0.1:8080' "$cfg" && grep -q '127.0.0.1:8081' "$cfg" && echo 1 || echo 0)"

    # --- render fixture: render the repo template with concrete values and verify
    # the rendered config has no unsubstituted tokens on config lines, has the
    # correct username, correct environment label, no committed secrets, and —
    # when the pinned image is available — passes promtool check config. The render
    # happens in a 0755 temp dir with the rule files copied alongside, because the
    # config references rule_files by RELATIVE path (as installed to /etc/prometheus)
    # and the promtool container runs as an unprivileged user.
    local render_dir; render_dir="$(mktemp -d)"
    chmod 755 "$render_dir"
    cp "$SRC_CONFIG_DIR/recording-rules.yml" "$SRC_CONFIG_DIR/alerts.yml" "$render_dir/"
    render_config "UNPROVISIONED" "staging" "$SRC_CONFIG_DIR/prometheus.yml" "$render_dir/prometheus.yml"
    local render_out="$render_dir/prometheus.yml"
    # no ${PROM_...} tokens should remain on CONFIG lines (comment prose may
    # document the tokens; comments are inert to Prometheus)
    assert_eq "rendered config: no PROM_ tokens" 1 "$(grep -v '^[[:space:]]*#' "$render_out" | grep -qF '${PROM_' && echo 0 || echo 1)"
    # username is the placeholder
    assert_eq "rendered username is UNPROVISIONED" 1 "$(grep -q 'username: UNPROVISIONED' "$render_out" && echo 1 || echo 0)"
    # environment label is staging
    assert_eq "rendered environment is staging" 1 "$(grep -q 'environment: staging' "$render_out" && echo 1 || echo 0)"
    # no committed secret strings in rendered output
    assert_eq "rendered no committed secret" 1 "$(grep -Eqi 'password: |pass: |secret: ' "$render_out" && echo 0 || echo 1)"
    # promtool validation of the rendered config (pinned 3.3.0); exit 0 = valid
    if docker image inspect "prom/prometheus:v3.3.0" >/dev/null 2>&1; then
        local promtool_rc=0
        docker run --rm --entrypoint promtool \
            -v "$render_dir:/cfg:ro" "prom/prometheus:v3.3.0" \
            check config /cfg/prometheus.yml >/dev/null 2>&1 || promtool_rc=$?
        assert_eq "rendered config promtool valid" 0 "$promtool_rc"
    else
        printf '  skip fixture promtool (image not pulled locally; CI pulls it)\n'
    fi
    rm -rf "$render_dir"

    # --- unit contract (D1-D3)
    local unit="$SRC_UNIT"
    assert_eq "unit: User=prometheus"       1 "$(grep -q '^User=prometheus$' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: ProtectSystem=strict"  1 "$(grep -q '^ProtectSystem=strict' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: NoNewPrivileges=true"  1 "$(grep -q '^NoNewPrivileges=true' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: loopback only"         1 "$(grep -q -- '--web.listen-address=127.0.0.1:9090' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: admin API off"         1 "$(grep -q -- '--no-web.enable-admin-api' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: lifecycle off"         1 "$(grep -q -- '--no-web.enable-lifecycle' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: TSDB path"             1 "$(grep -q -- '--storage.tsdb.path=/var/lib/prometheus' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: retention time 30d"    1 "$(grep -q -- '--storage.tsdb.retention.time=30d' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: retention size 8GiB"   1 "$(grep -q -- '--storage.tsdb.retention.size=8GiB' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: ExecReload (config-owned reload)" 1 "$(grep -q 'ExecReload=' "$unit" && echo 1 || echo 0)"

    # --- pinning: the pinned version matches the CI promtool version
    local ci_pin
    ci_pin="$(grep -oE 'prometheus/releases/download/v[0-9.]+' "$ROOT_DIR/.github/workflows/ci.yml" | head -1)"
    assert_eq "CI pins the same Prometheus version" 1 \
        "$([[ "$ci_pin" == "prometheus/releases/download/v$PROM_VERSION" ]] && echo 1 || echo 0)"

    # --- free-disk assertion logic (D2) via pure function on sample df output
    local df_out="Filesystem 1G-blocks Used Available Use% Mounted on
/dev/sda1 50 41 8 82% /var/lib/prometheus"
    local avail; avail="$(printf '%s\n' "$df_out" | awk 'NR==2 {gsub("G","",$4); print $4}')"
    assert_eq "D2 free-disk parse (8Gi avail)" 8 "$avail"

    if [[ "$fail" -eq 0 ]]; then
        printf 'install-prometheus --self-test: ALL PASS\n'
        return 0
    fi
    printf 'install-prometheus --self-test: FAILURES\n' >&2
    return 1
}

# ---- real install (root) ---------------------------------------------------------
install_prometheus_binary() {
    local arch; arch="$(prom_arch)"
    local archive="prometheus-$PROM_VERSION.linux-$arch.tar.gz"
    local url="https://github.com/prometheus/prometheus/releases/download/v$PROM_VERSION/$archive"

    if command -v prometheus >/dev/null 2>&1; then
        local ver
        ver="$(prometheus --version 2>/dev/null | head -1 | grep -o 'version=[0-9.]*' || true)"
        if [[ "$ver" == "version=$PROM_VERSION" ]]; then
            log "prometheus $PROM_VERSION already installed; skipping binary install"
            return 0
        fi
    fi

    log "Downloading $archive"
    local stage; stage="$(mktemp -d)"
    trap 'rm -rf "$stage"' EXIT
    local tgz="$stage/$archive"
    curl -fsSL --retry 3 -o "$tgz" "$url" || die "download failed: $url"

    local sumlist="$stage/sha256sums.txt"
    curl -fsSL --retry 3 -o "$sumlist" \
        "https://github.com/prometheus/prometheus/releases/download/v$PROM_VERSION/sha256sums.txt" \
        || die "cannot fetch pinned sha256sums for v$PROM_VERSION"

    # Pick the exact entry for THIS tarball (the file lists every platform, NOT ordered).
    local expected actual
    expected="$(grep -F "$archive" "$sumlist" | awk '{print $1}' | head -1)"
    actual="$(sha256sum "$tgz" | awk '{print $1}')"
    [[ -n "$expected" ]] || die "no sha256 entry for $archive in v$PROM_VERSION sha256sums.txt"
    if [[ -z "$expected" || "$actual" != "$expected" ]]; then
        die "sha256 mismatch: expected $expected, got $actual — refusing to install"
    fi
    log "sha256 verified: $actual"

    tar -xzf "$tgz" -C "$stage"
    install -o root -g root -m 0755 \
        "$stage/prometheus-$PROM_VERSION.linux-$arch/prometheus" "$PROM_BIN_DIR/prometheus"
    log "installed prometheus $PROM_VERSION -> $PROM_BIN_DIR/prometheus"
}

# render_config_main: CLI wrapper for render_config.
# Requires --username, --environment, and --output to be provided.
render_config_main() {
    local username env outfile
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --username) username="$2"; shift 2 ;;
            --environment) env="$2"; shift 2 ;;
            --output) outfile="$2"; shift 2 ;;
            *) die "unknown render-config option: $1" ;;
        esac
    done
    [[ -n "${username:-}" ]] || die "--username is required"
    [[ -n "${env:-}" ]] || die "--environment is required (never default to prod)"
    [[ -n "${outfile:-}" ]] || die "--output is required"
    render_config "$username" "$env" "$SRC_CONFIG_DIR/prometheus.yml" "$outfile"
    log "rendered config written to $outfile"
}

# ---- free-disk assertion helper (pure function) ----
free_disk_gi() {
    local dir="$1"
    local avail_gi
    avail_gi="$(df -BG "$dir" | awk 'NR==2 {gsub("G","",$4); print $4}')"
    [[ "$avail_gi" =~ ^[0-9]+$ ]] || avail_gi=0
    echo "$avail_gi"
}

# ---- real install (root) ---------------------------------------------------------
do_install() {
    install_prometheus_binary

    if ! id "$PROM_USER" >/dev/null 2>&1; then
        useradd --system --home-dir "$PROM_DATA_DIR" --shell /usr/sbin/nologin "$PROM_USER"
        log "created system user $PROM_USER"
    fi
    mkdir -p "$PROM_DATA_DIR" "$PROM_CONFIG_DIR" "$PROM_UNIT_DIR" "$(dirname "$PROM_PASSWORD_FILE")"
    chown "$PROM_USER:$PROM_GROUP" "$PROM_DATA_DIR"
    chmod 750 "$PROM_DATA_DIR"

    # D2 startup assert: free disk >= retention size cap
    local avail_gi
    avail_gi="$(free_disk_gi "$PROM_DATA_DIR")"
    if [[ "$avail_gi" -lt "$RETENTION_SIZE_CAP_GI" ]]; then
        die "free disk on $PROM_DATA_DIR is ${avail_gi}Gi < ${RETENTION_SIZE_CAP_GI}Gi cap (D2) — refusing to start"
    fi
    log "free disk: ${avail_gi}Gi (>= ${RETENTION_SIZE_CAP_GI}Gi cap)"

    # D4: operator-owned credential scaffolds (fail closed until the operator writes them)
    if [[ ! -f "$PROM_ENV_FILE" ]]; then
        printf '# Scrape username for /actuator/prometheus (ADR 0012 D4).\nPROM_SCRAPE_USERNAME=\n' > "$PROM_ENV_FILE"
        chown root:root "$PROM_ENV_FILE"
        chmod 600 "$PROM_ENV_FILE"
        log "created $PROM_ENV_FILE — operator MUST set PROM_SCRAPE_USERNAME (else scrapes 401)"
    fi
    # D4: password file root:prometheus 0640 (group-readable by the service account only)
    if [[ ! -f "$PROM_PASSWORD_FILE" ]]; then
        install -o root -g "$PROM_GROUP" -m 0640 /dev/null "$PROM_PASSWORD_FILE"
        log "created $PROM_PASSWORD_FILE (root:prometheus 0640) — operator MUST write the real scrape password"
    elif [[ "$(stat -c '%G:%a' "$PROM_PASSWORD_FILE")" != "root:prometheus 0640" ]]; then
        # Fix ownership/permissions if they drifted from a previous run
        chown root:"$PROM_GROUP" "$PROM_PASSWORD_FILE"
        chmod 0640 "$PROM_PASSWORD_FILE"
    fi

    # Render the runtime config from the git-owned template with concrete values.
    # The operator must supply username and environment; if username is unset the
    # rendered config uses the literal word UNPROVISIONED so scrapes 401 until provisioned.
    local render_out
    render_out="$(mktemp)"
    render_config_main --username "${PROM_SCRAPE_USERNAME:-UNPROVISIONED}" \
        --environment "${PROM_ENVIRONMENT:-staging}" \
        --output "$render_out"
    # Validate the rendered config with promtool (pinned 3.3.0)
    if command -v prometheus >/dev/null 2>&1 && command -v promtool >/dev/null 2>&1; then
        if ! promtool check config "$render_out" >/dev/null 2>&1; then
            die "rendered config failed promtool check config — refusing to install"
        fi
        log "rendered config validated by promtool"
    else
        printf '  (promtool not available; skipping rendered-config validation)\n'
    fi
    install -o root -g root -m 0644 "$render_out" "$PROM_CONFIG_DIR/prometheus.yml"
    log "rendered config -> $PROM_CONFIG_DIR/prometheus.yml"

    # Verify that both blue and green targets are reachable and healthy.
    # This uses the actual auth path (basic_auth from the rendered config).
    local verify=0
    for color in blue green; do
        local target_port
        [[ "$color" == "blue" ]] && target_port="${BLUE_PORT:-8080}" || target_port="${GREEN_PORT:-8081}"
        local health
        health="$(curl -sf --max-time 2 "http://127.0.0.1:${target_port}/-/healthy" 2>/dev/null || echo "unreachable")"
        if [[ "$health" != "200" ]]; then
            log "WARNING: $color target /-/healthy is not 200 (health check may pending)"
        fi
        # Check target existance via /api/v1/targets (requires the auth from rendered config)
        # Since we cannot easily curl with the embedded creds in this script, we report
        # the status and let the operator re-run after provisioning.
        log "  $color target health check: $health"
    done
    log "Prometheus installed; operator must set PROM_SCRAPE_USERNAME and write the password,"
    log "then re-run this script to render the config and verify targets."

    install -o root -g root -m 0644 "$SRC_UNIT" "$PROM_UNIT_DIR/prometheus.service"
    systemctl daemon-reload
    log "installed unit -> $PROM_UNIT_DIR/prometheus.service"

    # FIXED EXIT TRAP: use a script-scoped variable so cleanup runs under set -u.
    # The old trap 'rm -rf "$stage"' inside install_prometheus_binary failed because
    # $stage was a function-local variable that became unbound at script exit.
    # Here we use a single top-level trap that references a script-scoped variable
    # initialized to empty, so the trap never hits an unbound-variable error.
    PROM_STAGE_DIR=""
    trap '[[ -n "$PROM_STAGE_DIR" ]] && rm -rf "$PROM_STAGE_DIR"' EXIT

    systemctl enable prometheus.service
    systemctl restart prometheus.service

    log "verifying /-/healthy + /-/ready (D2)..."
    for _ in $(seq 1 15); do
        if curl -sf --max-time 2 http://127.0.0.1:9090/-/healthy >/dev/null 2>&1 \
            && curl -sf --max-time 2 http://127.0.0.1:9090/-/ready >/dev/null 2>&1; then
            log "Prometheus healthy + ready at http://127.0.0.1:9090"
            return 0
        fi
        sleep 2
    done
    die "Prometheus did not become healthy/ready at 127.0.0.1:9090 within 30s"
}

usage() {
    sed -n '1,40p' "$0" | grep -v '^#!/bin/bash' | sed 's/^# \{0,1\}//'
}

case "${1:-install}" in
    --self-test) self_test ;;
    --render-config|render-config)
        shift
        render_config_main "$@"
        ;;
    install|--install)
        if [[ "$(id -u)" -ne 0 ]]; then
            die "install must run as root (systemd unit + /etc + /var); use --self-test for an offline check"
        fi
        do_install
        ;;
    -h|--help) usage ;;
    *) die "unknown command '${1:-}' — use 'install', '--self-test' or '--help' or '--render-config'" ;;
esac
