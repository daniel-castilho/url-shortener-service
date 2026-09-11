#!/bin/bash
# Security gate — enforces the Epic 2 (Secure by Design) invariants that are not
# already enforced by the compiler/tests:
#
#   1. logSafe() sink sanitizer present in every client-controlled log sink of infra/
#   2. the three HTTP security headers declared via Spring Security .headers()
#   3. ProdConfigValidator rejects short / default-dev JWT secrets in production
#   4. no hardcoded private/internal IP literals outside the SSRF blocklist
#
# Usage:
#   bash scripts/check-security.sh             # normal gate
#   bash scripts/check-security.sh --self-test  # proves the gate catches violations
set -euo pipefail

INITIAL_DIR="src/main/java/ca/tyny/urlshortener/infra"

LOGSAFE_SINKS=(
    "adapter/input/rest/advice/GlobalExceptionHandler.java"
    "adapter/output/analytics/RedisClickEventQueue.java"
    "adapter/output/persistence/MongoCustomDomainRepository.java"
    "adapter/output/redis/RedisCustomDomainRegistry.java"
    "adapter/output/validation/DefaultUrlValidator.java"
)

# Files that legitimately reference private/internal IP literals and must NOT be flagged:
#  - DefaultUrlValidator: the SSRF blocklist (AGENTS.md Rule 6)
#  - ProdConfigValidator: "127.0.0.1"/"localhost" as the patterns it REJECTS in prod
#    ("spring.mongodb.uri should not point to localhost"), not as hardcoded connections.
IP_ALLOWLIST_FILES=(
    "adapter/output/validation/DefaultUrlValidator.java"
    "config/ProdConfigValidator.java"
)

check_logsafe_sinks() {
    local root="${1:-$INITIAL_DIR}"
    local missing=0
    for rel in "${LOGSAFE_SINKS[@]}"; do
        local file="$root/$rel"
        if [ ! -f "$file" ] || ! grep -q "static String logSafe(" "$file"; then
            echo "  MISSING logSafe sink: $file"
            missing=1
        fi
    done
    return $missing
}

check_security_headers() {
    local file="${1:-$INITIAL_DIR/config/SecurityConfig.java}"
    local missing=0
    if [ ! -f "$file" ]; then
        echo "  MISSING SecurityConfig: $file"
        return 1
    fi
    for needle in "contentTypeOptions" "frameOptions" "referrerPolicy" "STRICT_ORIGIN_WHEN_CROSS_ORIGIN"; do
        if ! grep -q "$needle" "$file"; then
            echo "  MISSING header config: $needle in $file"
            missing=1
        fi
    done
    return $missing
}

check_jwt_secret_validation() {
    local file="${1:-$INITIAL_DIR/config/ProdConfigValidator.java}"
    local missing=0
    if [ ! -f "$file" ]; then
        echo "  MISSING ProdConfigValidator: $file"
        return 1
    fi
    for needle in "length() < 32" "app.jwt.secret is required" "isDefaultJwtSecret"; do
        if ! grep -q "$needle" "$file"; then
            echo "  MISSING JWT secret validation: $needle in $file"
            missing=1
        fi
    done
    return $missing
}

check_no_hardcoded_internal_ips() {
    local root="${1:-$INITIAL_DIR}"
    # RFC1918 + loopback + link-local + IPv4-mapped metadata + IPv6 ULA/loopback.
    local pattern='(127\.0\.0\.1|10\.0\.0\.1|10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.|169\.254\.169\.254|\[::1\]|fc00::)'
    local allow
    allow=$(printf '%s\\|' "${IP_ALLOWLIST_FILES[@]}")
    allow="${allow%\\|}"
    local hits
    hits=$(grep -rEn "$pattern" "$root" --include="*.java" 2>/dev/null | grep -v "$allow" || true)
    if [ -n "$hits" ]; then
        echo "  Hardcoded internal IP literals outside the allowlist files ($(IFS=,; echo "${IP_ALLOWLIST_FILES[*]}")):"
        echo "$hits"
        return 1
    fi
    return 0
}

run_all_checks() {
    local root="${1:-$INITIAL_DIR}"
    local pass=1
    check_logsafe_sinks "$root" || pass=0
    check_security_headers "$root/config/SecurityConfig.java" || pass=0
    check_jwt_secret_validation "$root/config/ProdConfigValidator.java" || pass=0
    check_no_hardcoded_internal_ips "$root" || pass=0
    return $((1 - pass))
}

if [ "${1:-}" = "--self-test" ]; then
    echo "=== Security Gate Self-Test ==="

    TMPDIR=$(mktemp -d)
    trap 'rm -rf "$TMPDIR"' EXIT

    # ---- Violation tree: every sink missing logSafe, headers half-gone,
    #      weak JWT validation, and a stray private IP.
    VIO="$TMPDIR/violation"
    mkdir -p "$VIO/adapter/input/rest/advice" \
             "$VIO/adapter/output/analytics" \
             "$VIO/adapter/output/persistence" \
             "$VIO/adapter/output/redis" \
             "$VIO/adapter/output/validation" \
             "$VIO/config"
    for rel in "${LOGSAFE_SINKS[@]}"; do
        mkdir -p "$(dirname "$VIO/$rel")"
        echo 'public class Fake { void log(String m) {} }' > "$VIO/$rel"
    done
    # Headers: only contentTypeOptions, missing the other two+policy.
    printf 'class SecurityConfig {\n  void headers() {\n    contentTypeOptions();\n  }\n}\n' > "$VIO/config/SecurityConfig.java"
    # Validator: short-circuit without the secret length/default checks.
    printf 'class ProdConfigValidator {\n  void validate() {}\n}\n' > "$VIO/config/ProdConfigValidator.java"
    # Stray private IP in a random adapter.
    mkdir -p "$VIO/adapter/output/persistence"
    printf 'class Leaky { String url = "http://127.0.0.1:27017/admin"; }\n' > "$VIO/adapter/output/persistence/BadConnString.java"

    if run_all_checks "$VIO"; then
        echo "FAIL: self-test did not detect planted violations"
        exit 1
    else
        echo "OK: planted violations detected (logSafe sinks, headers, JWT validator, hardcoded IP)."
    fi

    # ---- Clean tree: all checks pass. The real gate on the working tree serves as
    #      the no-false-positive direction (the blocklist-IP rule excludes the DefaultUrlValidator
    #      file from the RFC1918 scan).
    run_all_checks "$INITIAL_DIR" || { echo "FAIL: clean tree falsely rejected"; exit 1; }

    echo "PASS: self-test verified — gate detects violations."
    exit 0
fi

echo "=== Security Gate ==="

if run_all_checks "$INITIAL_DIR"; then
    echo "PASS: Security gate passed (logSafe sinks, HTTP headers, JWT validator, no stray internal IPs)."
    exit 0
else
    echo "FAIL: security gate — fix the items above."
    exit 1
fi