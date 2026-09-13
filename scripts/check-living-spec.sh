#!/usr/bin/env bash
# check-living-spec.sh — Living Specification Gate
#
# Verifies the traceability contract between EARS requirements declared in
# package-info.java ("living specifications") and tests annotated with
# @TracesRequirement (the single source of truth for traces).
#
# Contract (see AGENTS.md "Living Specifications"):
#   1. Only components marked "@spec-complete true" are gated (ratchet, decision 5).
#   2. Of the declared requirements, at least THRESHOLD% must be traced to at
#      least one test (default 90, decision 2 — the threshold is contract;
#      if it cannot be met, refine the EARS granularity, never the threshold).
#   3. Untraced test classes inside a gated component's package must be listed
#      in the debt registry (AGENTS.md "Living-Spec Debt Registry") with class,
#      reason, owner and deadline — an unlisted class fails the gate (decision 4).
#   4. A @TracesRequirement referencing a requirement that does not exist in any
#      package-info.java (dangling trace) fails the gate — stale traces are lies.
#
# Usage: ./scripts/check-living-spec.sh [--self-test] [--threshold=N]
#   --self-test: plants violations in a temp dir and proves the gate catches
#                them (same discipline as check-boundaries.sh --self-test).
#
# Exit codes: 0 = PASS, 1 = FAIL (violation or self-test failure)

set -euo pipefail

SELF_TEST=false
THRESHOLD=90
SPEC_COMPLETE_MARKER="@spec-complete true"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

for arg in "$@"; do
    case "$arg" in
        --self-test) SELF_TEST=true ;;
        --threshold=*) THRESHOLD="${arg#*=}" ;;
        *) echo "Unknown argument: $arg" >&2; exit 1 ;;
    esac
done

# check <src-main> <src-test> <registry-file> — runs the gate over the given
# trees; returns 0 (PASS) or 1 (FAIL). All output goes to stdout.
run_gate() {
    local main_dir="$1" test_dir="$2" registry="$3"
    local failures=0

    # --- Collect declared requirements (all package-info files) and the
    # --- gated subset (spec-complete components only — ratchet, decision 5) ---
    declare -A REQ_COMPONENT=()
    declare -A GATED=()
    local gated_pkgs=()
    local package_infos=()
    mapfile -t package_infos < <(find "$main_dir" -name "package-info.java" -type f | sort)
    for file in "${package_infos[@]}"; do
        local component
        component=$(grep -m1 '# Component:' "$file" | sed 's/.*# Component:[[:space:]]*//' | sed 's/[[:space:]]*$//')
        if [[ -z "$component" ]]; then
            component=$(basename "$(dirname "$file")")
        fi
        local req
        while IFS= read -r req; do
            [[ -z "$req" ]] && continue
            REQ_COMPONENT["$req"]="$component"
            if grep -q "$SPEC_COMPLETE_MARKER" "$file"; then
                GATED["$req"]=1
            fi
        done < <(grep -oE '###[[:space:]]+REQ-[A-Z0-9-]+' "$file" | awk '{print $2}')
        if grep -q "$SPEC_COMPLETE_MARKER" "$file"; then
            local pkg
            pkg=$(dirname "$file")
            gated_pkgs+=("${pkg#"$main_dir"/}")
        fi
    done

    # --- Collect traces (single source of truth: @TracesRequirement) ---
    # Traces must reference a declared requirement in ANY package-info (gated or
    # not); a trace to a requirement nobody declared is a dangling reference.
    declare -A TRACED=()
    local dangling=()
    local trace
    while IFS= read -r trace; do
        [[ -z "$trace" ]] && continue
        if [[ -n "${REQ_COMPONENT["$trace"]:-}" ]]; then
            TRACED["$trace"]=1
        else
            dangling+=("$trace")
        fi
    done < <(grep -rhoE '@TracesRequirement\("[^"]+"\)' "$test_dir" --include="*.java" 2>/dev/null \
            | sed -E 's/@TracesRequirement\("([^"]+)"\)/\1/' | sort -u || true)

    # --- Untraced-test-class debt registry (decision 4) ---
    # Every test class located under a gated component's package that has no
    # @TracesRequirement at all must be listed in the registry.
    local untraced_unlisted=()
    if [[ ${#gated_pkgs[@]} -gt 0 ]]; then
        local test_file rel cls
        while IFS= read -r test_file; do
            rel="${test_file#"$test_dir"/}"
            cls="${rel//\//.}"; cls="${cls%.java}"
            local gated=false
            local pkg_rel
            pkg_rel=$(dirname "$rel")
            for pkg in "${gated_pkgs[@]}"; do
                [[ "$pkg_rel" == "$pkg" ]] && gated=true && break
            done
            $gated || continue
            grep -q '@TracesRequirement' "$test_file" && continue
            if [[ -f "$registry" ]] && grep -qF "$cls" "$registry"; then
                continue
            fi
            untraced_unlisted+=("$cls")
        done < <(find "$test_dir" -name "*Test.java" -o -name "*IT.java" | sort)
    fi

    # --- Requirement coverage report (gated requirements only) ---
    local total=0 traced_count=0 coverage=100
    for req in "${!GATED[@]}"; do
        total=$((total + 1))
        if [[ -n "${TRACED["$req"]:-}" ]]; then
            traced_count=$((traced_count + 1))
        fi
    done
    if [[ $total -gt 0 ]]; then
        coverage=$((traced_count * 100 / total))
    fi

    echo "=== Living Specification Gate ==="
    echo "Threshold: ${THRESHOLD}%"
    if [[ $total -eq 0 ]]; then
        echo "No spec-complete components with requirements found — gate not active yet."
    fi
    for req in "${!GATED[@]}"; do
        if [[ -n "${TRACED["$req"]:-}" ]]; then
            echo "  OK      $req (${REQ_COMPONENT["$req"]}) — traced"
        else
            echo "  MISSING $req (${REQ_COMPONENT["$req"]})"
        fi
    done
    echo "Coverage: $traced_count / $total requirements traced ($coverage%)"

    if [[ ${#dangling[@]} -gt 0 ]]; then
        echo "FAIL: dangling @TracesRequirement references (no such declared requirement):"
        for trace in "${dangling[@]}"; do echo "  - $trace"; done
        failures=$((failures + 1))
    fi
    if [[ ${#untraced_unlisted[@]} -gt 0 ]]; then
        echo "FAIL: untraced test classes inside gated component packages, absent from the debt registry:"
        for cls in "${untraced_unlisted[@]}"; do echo "  - $cls"; done
        echo "       List them under 'Living-Spec Debt Registry' in AGENTS.md (class, reason, owner, deadline)"
        echo "       or annotate their tests with @TracesRequirement."
        failures=$((failures + 1))
    fi
    if [[ $total -gt 0 && $coverage -lt $THRESHOLD ]]; then
        echo "FAIL: coverage $coverage% below the ${THRESHOLD}% threshold"
        failures=$((failures + 1))
    fi

    [[ $failures -eq 0 ]]
}

# --- Self-test mode ----------------------------------------------------------
if [[ "$SELF_TEST" == true ]]; then
    echo "=== Living Spec Gate Self-Test ==="
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT

    make_tree() {
        # $1 = spec-complete (true/false), $2 = extra untraced test class, $3 = trace annotations
        mkdir -p "$TMP/src/main/java/test/comp" "$TMP/src/test/java/test/comp"
        cat > "$TMP/src/main/java/test/comp/package-info.java" <<EOF
/**
 * # Component: TestComp
 *
 * ## Requirements (EARS)
 *
 * ### REQ-TEST-001
 * **When** condition, **the Business Component shall** respond.
 *
 * ### REQ-TEST-002
 * **When** other, **the Business Component shall** respond too.
 *
 * @spec-complete $1
 */
package test.comp;
EOF
        cat > "$TMP/src/test/java/test/comp/TracedTest.java" <<EOF
package test.comp;
import ca.tyny.urlshortener.core.annotation.TracesRequirement;
class TracedTest {
    @TracesRequirement("REQ-TEST-001")
    void a() {}
    $3
}
EOF
        if [[ -n "$2" ]]; then
            cat > "$TMP/src/test/java/test/comp/$2.java" <<EOF
package test.comp;
class $2 {
    void b() {}
}
EOF
        fi
        : > "$TMP/registry.md"
    }

    # Case 1: spec-complete, full coverage, no untraced classes -> PASS
    make_tree true "" '@TracesRequirement("REQ-TEST-002") void c() {}'
    if ! run_gate "$TMP/src/main/java" "$TMP/src/test/java" "$TMP/registry.md" > "$TMP/out1" 2>&1; then
        echo "FAIL: clean tree was rejected:"; cat "$TMP/out1"; exit 1
    fi

    # Case 2: untraced requirement below threshold -> FAIL
    make_tree true "" ""
    if run_gate "$TMP/src/main/java" "$TMP/src/test/java" "$TMP/registry.md" > "$TMP/out2" 2>&1; then
        echo "FAIL: 50% coverage did not fail the gate"; exit 1
    fi
    grep -q "MISSING REQ-TEST-002" "$TMP/out2" || { echo "FAIL: missing req not reported"; cat "$TMP/out2"; exit 1; }
    grep -q "below the 90% threshold" "$TMP/out2" || { echo "FAIL: threshold message missing"; cat "$TMP/out2"; exit 1; }

    # Case 3: untraced test class in gated package, not in registry -> FAIL
    make_tree true "StrayTest" '@TracesRequirement("REQ-TEST-002") void c() {}'
    if run_gate "$TMP/src/main/java" "$TMP/src/test/java" "$TMP/registry.md" > "$TMP/out3" 2>&1; then
        echo "FAIL: unregistered stray test class did not fail the gate"; exit 1
    fi
    grep -q "test.comp.StrayTest" "$TMP/out3" || { echo "FAIL: stray class not named"; cat "$TMP/out3"; exit 1; }

    # Case 4: stray class listed in the registry -> PASS (registry exempts it)
    make_tree true "StrayTest" '@TracesRequirement("REQ-TEST-002") void c() {}'
    echo "test.comp.StrayTest — awaiting UrlShortener component spec (owner: daniel, 2026-10-15)" > "$TMP/registry.md"
    if ! run_gate "$TMP/src/main/java" "$TMP/src/test/java" "$TMP/registry.md" > "$TMP/out4" 2>&1; then
        echo "FAIL: registered stray class was rejected:"; cat "$TMP/out4"; exit 1
    fi

    # Case 5: dangling @TracesRequirement -> FAIL
    make_tree true "" '@TracesRequirement("REQ-TEST-999") void c() {}'
    if run_gate "$TMP/src/main/java" "$TMP/src/test/java" "$TMP/registry.md" > "$TMP/out5" 2>&1; then
        echo "FAIL: dangling trace did not fail the gate"; exit 1
    fi
    grep -q "REQ-TEST-999" "$TMP/out5" || { echo "FAIL: dangling req not reported"; cat "$TMP/out5"; exit 1; }

    # Case 6: component NOT spec-complete -> gate not active (ratchet, decision 5)
    make_tree false "" ""
    if ! run_gate "$TMP/src/main/java" "$TMP/src/test/java" "$TMP/registry.md" > "$TMP/out6" 2>&1; then
        echo "FAIL: non-spec-complete component was gated"; cat "$TMP/out6"; exit 1
    fi
    grep -q "gate not active" "$TMP/out6" || { echo "FAIL: inactive message missing"; cat "$TMP/out6"; exit 1; }

    echo "PASS: self-test verified — gate detects missing traces, stray classes, dangling refs, and respects the ratchet."
    exit 0
fi

# --- Regular run --------------------------------------------------------------
REGISTRY="$ROOT_DIR/AGENTS.md"
if run_gate "$ROOT_DIR/src/main/java" "$ROOT_DIR/src/test/java" "$REGISTRY"; then
    echo "PASS: living specification gate."
else
    echo "FAIL: living specification gate — see violations above (AGENTS.md 'Living Specifications')."
    exit 1
fi
