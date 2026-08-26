#!/bin/bash
# Architecture boundary check — enforces AGENTS.md Rule 1
# core/ must not import infra, Spring, MongoDB, Redisson, JWT, Micrometer, or Lombok types.
#
# Usage:
#   bash scripts/check-boundaries.sh            # normal gate
#   bash scripts/check-boundaries.sh --self-test # proves the gate catches violations
set -euo pipefail

CORE_DIR="src/main/java/ca/tyny/urlshortener/core"

check_boundaries() {
    local dir="$1"

    # Check 1: No infra imports in core
    INFRA_IMPORTS=$(grep -rEn "import ca\.tyny\.urlshortener\.infra" "$dir" 2>/dev/null || true)
    if [ -n "$INFRA_IMPORTS" ]; then
        echo "FAIL: core/ imports infra types:"
        echo "$INFRA_IMPORTS"
        return 1
    fi

    # Check 2: No framework imports in core
    FRAMEWORK_IMPORTS=$(grep -rlE "org\.springframework|org\.mongodb|org\.redisson|io\.jsonwebtoken|io\.micrometer|lombok" "$dir" 2>/dev/null || true)
    if [ -n "$FRAMEWORK_IMPORTS" ]; then
        echo "FAIL: core/ imports framework types:"
        echo "$FRAMEWORK_IMPORTS"
        return 1
    fi

    return 0
}

if [ "${1:-}" = "--self-test" ]; then
    echo "=== Architecture Boundary Self-Test ==="

    TMPDIR=$(mktemp -d)
    trap 'rm -rf "$TMPDIR"' EXIT

    # Create a fake core/ directory with a violation
    mkdir -p "$TMPDIR/fake-core"
    echo 'import lombok.RequiredArgsConstructor;' > "$TMPDIR/fake-core/Violation.java"

    if check_boundaries "$TMPDIR/fake-core"; then
        echo "FAIL: self-test did not detect planted violation"
        exit 1
    fi

    # Create a clean core/ directory — should pass
    mkdir -p "$TMPDIR/clean-core"
    echo 'import java.util.List;' > "$TMPDIR/clean-core/Clean.java"

    if ! check_boundaries "$TMPDIR/clean-core"; then
        echo "FAIL: self-test falsely rejected clean code"
        exit 1
    fi

    echo "PASS: self-test verified — gate detects violations and allows clean code."
    exit 0
fi

echo "=== Architecture Boundary Check ==="

if check_boundaries "$CORE_DIR"; then
    echo "PASS: Architecture boundary check passed (0 violations)."
else
    exit 1
fi
