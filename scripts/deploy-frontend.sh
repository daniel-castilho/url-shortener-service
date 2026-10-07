#!/usr/bin/env bash
#
# Deploy / roll back the static frontend (SPA dist) on the compose host.
# Fully independent of the backend: no rebuild, no `compose up`, no Caddy
# reload — Caddy serves files from the `current` symlink on every request.
#
#   bash scripts/deploy-frontend.sh --placeholder      # minimal page (edge prep)
#   bash scripts/deploy-frontend.sh v0.3.0             # deploy a web release
#   bash scripts/deploy-frontend.sh --rollback v0.2.0  # point back at an extracted release
#   bash scripts/deploy-frontend.sh --current          # show what is live
#   bash scripts/deploy-frontend.sh --self-test
#
# Flow (deploy):
#   GitHub Release of url-shortener-web (tag vX.Y.Z, built by its release.yml)
#     -> gh release download (tar.gz + sbom + SHA256SUMS)
#     -> sha256sum -c SHA256SUMS            (fail-closed)
#     -> extract to releases/<tag>/         (dist/ stripped; VERSION stamped)
#     -> atomic `current` symlink flip      (ln -s + mv -T)
#     -> verify current/VERSION + index.html
#
# Rollback is the same symlink flip to any already-extracted release — backend
# tag, data, and routes are untouched (LEGO-piece deployment).
#
# Post-deploy edge verification (read-only legs) lives in
# docs/release-runbook.md §"Edge routing & static frontend".
set -euo pipefail

FRONTEND_DIR="${FRONTEND_DIR:-/home/daniel/projects/urlshortener/frontend}"
WEB_REPO="${WEB_REPO:-daniel-castilho/url-shortener-web}"
PLACEHOLDER_VERSION="0.0.0-placeholder"

log() { printf '[deploy-frontend %s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { echo "FATAL: $*" >&2; exit 1; }

usage() {
  sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//'
}

# Atomically point `current` at releases/<target> (target = "releases/<name>").
flip() {
  local target="$1" tmp
  [ -d "$FRONTEND_DIR/$target" ] || fail "release directory not found: $FRONTEND_DIR/$target"
  [ -f "$FRONTEND_DIR/$target/index.html" ] || fail "release has no index.html: $target"
  tmp="$FRONTEND_DIR/.current.$$"
  ln -s "$target" "$tmp"
  mv -Tf "$tmp" "$FRONTEND_DIR/current"
}

verify_current() {
  local want="$1" got
  got="$(cat "$FRONTEND_DIR/current/VERSION" 2>/dev/null || echo missing)"
  [ "$got" = "$want" ] || fail "current/VERSION is '$got', expected '$want'"
  log "OK: current -> $(readlink "$FRONTEND_DIR/current") (version $want, index.html present)"
}

install_placeholder() {
  local rel="$FRONTEND_DIR/releases/$PLACEHOLDER_VERSION"
  mkdir -p "$rel"
  cat >"$rel/index.html" <<'HTML'
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>tyny.ca — coming soon</title>
  </head>
  <body>
    <h1>tyny.ca</h1>
    <p>Frontend not deployed yet (edge preparation placeholder).</p>
  </body>
</html>
HTML
  printf '%s\n' "$PLACEHOLDER_VERSION" >"$rel/VERSION"
  flip "releases/$PLACEHOLDER_VERSION"
  verify_current "$PLACEHOLDER_VERSION"
}

install_version() {
  local ver="$1" rel="$FRONTEND_DIR/releases/$1" tmp
  [[ "$ver" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-+.][0-9A-Za-z.-]+)?$ ]] ||
    fail "version must be a release tag like vX.Y.Z (got: $ver)"
  if [ -d "$rel" ]; then
    log "release $ver already extracted — reusing"
  else
    tmp="$(mktemp -d "$FRONTEND_DIR/.dl.XXXXXX")"
    (
      cd "$tmp"
      gh release download "$ver" --repo "$WEB_REPO" \
        --pattern "url-shortener-web-${ver}.tar.gz" \
        --pattern "sbom-url-shortener-web-${ver}.json" \
        --pattern SHA256SUMS
      sha256sum -c SHA256SUMS
    ) || { rm -rf "$tmp"; fail "download or sha256 verification failed for $ver"; }
    mkdir -p "$rel"
    tar -xzf "$tmp/url-shortener-web-${ver}.tar.gz" -C "$rel" --strip-components=1
    rm -rf "$tmp"
    [ -f "$rel/index.html" ] || fail "artifact for $ver has no dist/index.html"
    printf '%s\n' "$ver" >"$rel/VERSION"
    chmod -R a+rX "$rel"
    log "extracted $ver into releases/$ver"
  fi
  flip "releases/$ver"
  verify_current "$ver"
}

rollback_to() {
  local ver="$1"
  [ -d "$FRONTEND_DIR/releases/$ver" ] ||
    fail "release $ver is not extracted under $FRONTEND_DIR/releases — cannot roll back to it"
  flip "releases/$ver"
  verify_current "$ver"
}

show_current() {
  [ -L "$FRONTEND_DIR/current" ] || fail "no current symlink at $FRONTEND_DIR/current"
  log "current -> $(readlink "$FRONTEND_DIR/current")"
  log "version : $(cat "$FRONTEND_DIR/current/VERSION" 2>/dev/null || echo '(no VERSION file)')"
}

self_test() {
  FRONTEND_DIR="$(mktemp -d /tmp/frontend-selftest.XXXXXX)"
  export FRONTEND_DIR
  # shellcheck disable=SC2064
  trap "rm -rf '$FRONTEND_DIR'" EXIT
  local fails=0
  ok() { echo "  case $1 OK"; }
  bad() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

  # 1. placeholder install + verify
  install_placeholder >/dev/null
  [ "$(readlink "$FRONTEND_DIR/current")" = "releases/$PLACEHOLDER_VERSION" ] &&
    [ -f "$FRONTEND_DIR/current/index.html" ] && ok "placeholder install" || bad "placeholder install"

  # 2. flip to a fabricated release + VERSION verify
  mkdir -p "$FRONTEND_DIR/releases/v9.9.9"
  echo '<html>x</html>' >"$FRONTEND_DIR/releases/v9.9.9/index.html"
  echo 'v9.9.9' >"$FRONTEND_DIR/releases/v9.9.9/VERSION"
  rollback_to v9.9.9 >/dev/null &&
    [ "$(cat "$FRONTEND_DIR/current/VERSION")" = "v9.9.9" ] && ok "flip + verify" || bad "flip + verify"

  # 3. rollback to the previous release
  rollback_to "$PLACEHOLDER_VERSION" >/dev/null &&
    [ "$(cat "$FRONTEND_DIR/current/VERSION")" = "$PLACEHOLDER_VERSION" ] && ok "rollback" || bad "rollback"

  # 4. rollback to a missing release fails closed (subshell: fail() exits)
  if (rollback_to v1.2.3) >/dev/null 2>&1; then bad "missing-release rollback must fail"; else ok "missing-release rollback fails"; fi

  # 5. invalid version strings rejected before any network call
  if (install_version "not-a-version") >/dev/null 2>&1; then bad "version validation"; else ok "version validation"; fi

  # 6. no leftover temp symlinks
  if compgen -G "$FRONTEND_DIR/.current.*" >/dev/null; then bad "temp symlink left behind"; else ok "no temp leftovers"; fi

  [ "$fails" -eq 0 ] || exit 1
  echo "self-test: PASS (6/6)"
}

case "${1:-}" in
  --placeholder) mkdir -p "$FRONTEND_DIR" && install_placeholder ;;
  --rollback) [ -n "${2:-}" ] || fail "usage: --rollback <version>"; mkdir -p "$FRONTEND_DIR" && rollback_to "$2" ;;
  --current) show_current ;;
  --self-test) self_test ;;
  -h | --help | "") usage ;;
  *) install_version "$1" ;;
esac
