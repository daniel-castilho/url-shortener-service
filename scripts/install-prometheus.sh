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
#   - D4: scaffolds the scrape password file as root:root 0600 at
#         /etc/url-shortener/prometheus-scrape.password and the env file for
#         PROM_SCRAPE_USERNAME. The OPERATOR writes the real credential (never
#         committed/automated); scrapes fail closed (401) until then.
#
# Usage:
#   bash scripts/install-prometheus.sh               # idempotent install (root)
#   bash scripts/install-prometheus.sh --self-test   # offline self-test (no root/net)
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

    # --- unit contract (D1-D3)
    local unit="$SRC_UNIT"
    assert_eq "unit: User=prometheus"       1 "$(grep -q '^User=prometheus$' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: ProtectSystem=strict"  1 "$(grep -q '^ProtectSystem=strict' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: NoNewPrivileges=true"  1 "$(grep -q '^NoNewPrivileges=true' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: loopback only"         1 "$(grep -q -- '--web.listen-address=127.0.0.1:9090' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: admin API off"         1 "$(grep -q -- '--web.enable-admin-api=false' "$unit" && echo 1 || echo 0)"
    assert_eq "unit: lifecycle off"         1 "$(grep -q -- '--web.enable-lifecycle=false' "$unit" && echo 1 || echo 0)"
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
    avail_gi="$(df -BG "$PROM_DATA_DIR" | awk 'NR==2 {gsub("G","",$4); print $4}')"
    [[ "$avail_gi" =~ ^[0-9]+$ ]] || avail_gi=0
    if [[ "$avail_gi" -lt "$RETENTION_SIZE_CAP_GI" ]]; then
        die "free disk on $PROM_DATA_DIR is ${avail_gi}Gi < ${RETENTION_SIZE_CAP_GI}Gi cap (D2) — refusing to start"
    fi
    log "free disk: ${avail_gi}Gi (>= ${RETENTION_SIZE_CAP_GI}Gi cap)"

    install -o root -g root -m 0644 "$SRC_CONFIG_DIR/prometheus.yml"      "$PROM_CONFIG_DIR/prometheus.yml"
    install -o root -g root -m 0644 "$SRC_CONFIG_DIR/alerts.yml"          "$PROM_CONFIG_DIR/alerts.yml"
    install -o root -g root -m 0644 "$SRC_CONFIG_DIR/recording-rules.yml" "$PROM_CONFIG_DIR/recording-rules.yml"
    log "installed config -> $PROM_CONFIG_DIR"

    # D4: operator-owned credential scaffolds (fail closed until the operator writes them)
    if [[ ! -f "$PROM_ENV_FILE" ]]; then
        printf '# Scrape username for /actuator/prometheus (ADR 0012 D4).\nPROM_SCRAPE_USERNAME=\n' > "$PROM_ENV_FILE"
        chown root:root "$PROM_ENV_FILE"
        chmod 600 "$PROM_ENV_FILE"
        log "created $PROM_ENV_FILE — operator MUST set PROM_SCRAPE_USERNAME (else scrapes 401)"
    fi
    if [[ ! -f "$PROM_PASSWORD_FILE" ]]; then
        install -o root -g root -m 0600 /dev/null "$PROM_PASSWORD_FILE"
        log "created $PROM_PASSWORD_FILE (root:root 0600) — operator MUST write the real scrape password; waypoint for rotate+reload"
    fi

    install -o root -g root -m 0644 "$SRC_UNIT" "$PROM_UNIT_DIR/prometheus.service"
    systemctl daemon-reload
    log "installed unit -> $PROM_UNIT_DIR/prometheus.service"

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
    install|--install)
        if [[ "$(id -u)" -ne 0 ]]; then
            die "install must run as root (systemd unit + /etc + /var); use --self-test for an offline check"
        fi
        do_install
        ;;
    -h|--help) usage ;;
    *) die "unknown command '${1:-}' — use 'install', '--self-test' or '--help'" ;;
esac