#!/bin/bash
# CHANGELOG gate (Epic 8 story 8.2) — keep-a-changelog discipline at tag time.
#
# A release tag must not ship with draft entries: the `## [Unreleased]` section of
# CHANGELOG.md, at the tagged commit, must exist and be EMPTY (the promotion to
# `## [X.Y.Z] - <date>` happened before tagging). Tags with a dirty Unreleased are
# refused — the gate bites (same discipline as check-boundaries.sh --self-test).
#
# Usage:
#   bash scripts/check-changelog.sh <changelog-file>
#   bash scripts/check-changelog.sh --self-test   (plants a dirty Unreleased in a temp
#                                                  dir and asserts the gate catches it)
#
# Exit 0 = gate PASS; exit 1 = violation (or self-test failure).

set -uo pipefail

SELF_TEST=false
FILE=""
for arg in "$@"; do
  case "$arg" in
    --self-test) SELF_TEST=true ;;
    *) FILE="$arg" ;;
  esac
done

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*" ; exit 0; }

# Extract the Unreleased section (from `## [Unreleased]` up to the next `## ` header,
# exclusive) and decide whether it carries entries.
unreleased_dirty() {
  awk '
    /^## \[Unreleased\]/ { in_unreleased = 1; next }
    in_unreleased && /^## / { in_unreleased = 0; next }
    in_unreleased { print }
  ' "$1"
}

unreleased_missing() {
  ! grep -q '^## \[Unreleased\]' "$1"
}

gate() {
  local f="$1"
  [ -f "$f" ] || { echo "FAIL: changelog not found: $1" >&2; exit 1; }
  if ! grep -q '^## \[Unreleased\]' "$f"; then
    echo "FAIL: ## [Unreleased] section is MISSING from $f (keep-a-changelog: the section header must exist)" >&2
    exit 1
  fi
  local content
  content=$(awk '
    /^## \[Unreleased\]/ { in_unreleased = 1; next }
    in_unreleased && /^## / { in_unreleased = 0; next }
    in_unreleased { print }
  ' "$f")
  if printf '%s' "$content" | grep -qvE '^\s*(#.*)?$'; then
    echo "FAIL: ## [Unreleased] contains entries at $f — promote them to a version section before tagging" >&2
    exit 1
  fi
  echo "PASS: changelog gate — [Unreleased] exists and is empty (promotion happened)"
  exit 0
}

if [ "$SELF_TEST" = true ]; then
  echo "=== CHANGELOG Gate Self-Test ==="
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT

  # Case 1: clean Unreleased -> PASS
  cat > "$TMP/clean.md" <<'EOF'
# Changelog

## [Unreleased]

## [1.2.3] - 2026-09-12

### Added
- something

## [1.2.2] - 2026-09-01
EOF
  if gate "$TMP/clean.md" >/dev/null 2>&1; then
    echo "case 1 OK: clean Unreleased passes"
  else
    echo "FAIL: self-test: clean Unreleased must pass" >&2
    exit 1
  fi

  # Case 2: dirty Unreleased (entries) -> FAIL
  cat > "$TMP/dirty.md" <<'EOF'
# Changelog

## [Unreleased]

### Added
- draft entry that was never promoted

## [1.2.3] - 2026-09-12
EOF
  if gate "$TMP/dirty.md" >/dev/null 2>&1; then
    echo "FAIL: self-test: dirty Unreleased must fail the gate" >&2
    exit 1
  else
    echo "case 2 OK: dirty Unreleased is caught"
  fi

  # Case 3: missing Unreleased header -> FAIL
  cat > "$TMP/missing.md" <<'EOF'
# Changelog

## [1.2.3] - 2026-09-12

### Added
- something
EOF
  if gate "$TMP/missing.md" >/dev/null 2>&1; then
    echo "FAIL: self-test: missing Unreleased must fail the gate" >&2
    exit 1
  else
    echo "case 3 OK: missing Unreleased is caught"
  fi

  echo "OK: planted violations detected and clean changelog passes."
  echo "PASS: self-test verified — gate detects violations."
  exit 0
fi

# Regular run (no args -> repo default)
[ -n "$FILE" ] || FILE="$(cd "$(dirname "$0")/.." && pwd)/CHANGELOG.md"
gate "$FILE"