#!/bin/bash
# Architecture boundary check — enforces AGENTS.md Rule 1
# core/ must not import infra, Spring, MongoDB, Redisson, JWT, or Micrometer types.
set -euo pipefail

CORE_DIR="src/main/java/ca/tyny/urlshortener/core"

echo "=== Architecture Boundary Check ==="

# Check 1: No infra imports in core
INFRA_IMPORTS=$(grep -rEn "import ca\.tyny\.urlshortener\.infra" "$CORE_DIR" 2>/dev/null || true)
if [ -n "$INFRA_IMPORTS" ]; then
    echo "FAIL: core/ imports infra types:"
    echo "$INFRA_IMPORTS"
    exit 1
fi

# Check 2: No framework imports in core
FRAMEWORK_IMPORTS=$(grep -rlE "org\.springframework|org\.mongodb|org\.redisson|io\.jsonwebtoken|io\.micrometer" "$CORE_DIR" 2>/dev/null || true)
if [ -n "$FRAMEWORK_IMPORTS" ]; then
    echo "FAIL: core/ imports framework types:"
    echo "$FRAMEWORK_IMPORTS"
    exit 1
fi

echo "PASS: Architecture boundary check passed (0 violations)."
