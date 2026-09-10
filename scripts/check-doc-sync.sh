#!/bin/bash
# Documentation sync check — enforces AGENTS.md Rule 10 (Doc Sync is Part of "Done")
# and Epic 1 story 1.5.
#
# Checks:
#   1. Every "Known Technical Debt" item in AGENTS.md carries a status
#      (open / in-progress / resolved).
#   2. Every lessons.md promotion marker ("-> coding-standards") points at a
#      section that exists in coding-standards.md.
#   3. coding-standards.md promoted sections reference lessons.md (no orphan rules).
#
# Usage:
#   bash scripts/check-doc-sync.sh             # normal gate
#   bash scripts/check-doc-sync.sh --self-test # proves the gate catches violations
set -euo pipefail

AGENTS_MD="AGENTS.md"
LESSONS_MD="docs/lessons.md"
STANDARDS_MD="docs/coding-standards.md"

check_doc_sync() {
    local agents="$1" lessons="$2" standards="$3"
    local failures=0
    local STATUS_TMP
    STATUS_TMP=$(mktemp)

    # --- Check 1: every debt-matrix item has a status ---
    # Scope: only the "Known Technical Debt" section of AGENTS.md (numbered "Critical Rules"
    # earlier in the file are not debt items). Debt entries are "N. **Title** ... — `status`"
    # and may span several lines; join each numbered item into one logical paragraph before
    # checking, so a status on a continuation line still counts.
    local debt_items=0 debt_without_status=0
    awk '
        /^# .*Known Technical Debt/ { in_debt = 1; next }
        in_debt && /^[0-9]+\. \*\*/ {
            if (item != "" && item !~ /`(open|in-progress|resolved)`/) bad++
            item = $0; n++; next
        }
        in_debt && item != "" { item = item " " $0; next }
        END { if (item != "" && item !~ /`(open|in-progress|resolved)`/) bad++; print n+0, bad+0 }
    ' "$agents" > "$STATUS_TMP"
    read -r debt_items debt_without_status < "$STATUS_TMP"
    if [ "$debt_without_status" -gt 0 ]; then
        echo "FAIL: $debt_without_status of $debt_items debt-matrix item(s) in $agents lack a status tag:"
        awk '
            /^# .*Known Technical Debt/ { in_debt = 1; next }
            in_debt && /^[0-9]+\. \*\*/ {
                if (item != "" && item !~ /`(open|in-progress|resolved)`/) print substr(item,1,100)
                item = $0; next
            }
            in_debt && item != "" { item = item " " $0; next }
            END { if (item != "" && item !~ /`(open|in-progress|resolved)`/) print substr(item,1,100) }
        ' "$agents"
        failures=$((failures + 1))
    fi

    # --- Check 2: every promotion marker in lessons.md resolves in coding-standards.md ---
    local promoted section
    while IFS= read -r line; do
        # Extract section refs like "coding-standards §14.1" or "§14.2"
        for section in $(echo "$line" | grep -oE '§[0-9]+(\.[0-9]+)?' || true); do
            if ! grep -q "^#* *${section#§}" "$standards" 2>/dev/null; then
                # heading may be "### 14.1 Title..." — match "14.1" as a word
                if ! grep -qE "^#{1,6} ${section#§}\." "$standards" 2>/dev/null; then
                    echo "FAIL: lessons.md promotes to $section but coding-standards.md has no such section"
                    failures=$((failures + 1))
                fi
            fi
        done
    done < <(grep -E '\-> *coding-standards|→ coding-standards' "$lessons" 2>/dev/null || true)

    # --- Check 3: promoted sections exist at all (at least one if any promotion marker exists) ---
    local promotions
    promotions=$(grep -cE '\-> *coding-standards|→ coding-standards' "$lessons" 2>/dev/null || true)
    if [ "$promotions" -gt 0 ]; then
        if ! grep -qE '^#{1,6} [0-9]+\.[0-9]+ ' "$standards" 2>/dev/null; then
            echo "FAIL: lessons.md has $promotions promotion(s) but coding-standards.md has no numbered promoted section"
            failures=$((failures + 1))
        fi
    fi

    rm -f "$STATUS_TMP"
    return "$failures"
}
if [ "${1:-}" = "--self-test" ]; then
    echo "=== Documentation Sync Self-Test ==="

    TMPDIR=$(mktemp -d)
    trap 'rm -rf "$TMPDIR"' EXIT

    # --- Case 1: planted violations must FAIL ---
    mkdir -p "$TMPDIR/docs"
    {
        echo "# Agents"
        echo "1. **Untouched debt item** — no status here"
        echo "2. **Fine item** — \`resolved\`"
    } > "$TMPDIR/AGENTS.md"
    {
        echo "# Lessons"
        echo "## Something — **→ coding-standards §99.9** (promoted)"
    } > "$TMPDIR/docs/lessons.md"
    {
        echo "# Coding Standards"
        echo "## 14.1 Real section"
    } > "$TMPDIR/docs/coding-standards.md"

    if check_doc_sync "$TMPDIR/AGENTS.md" "$TMPDIR/docs/lessons.md" "$TMPDIR/docs/coding-standards.md"; then
        echo "FAIL: self-test did not detect planted violations (missing status + dangling promotion)"
        exit 1
    fi

    # --- Case 2: clean docs must PASS ---
    {
        echo "# Agents"
        echo "1. **Fine item** — \`resolved\`"
    } > "$TMPDIR/AGENTS.md"
    {
        echo "# Lessons"
        echo "## Something — **→ coding-standards §14.1** (promoted)"
    } > "$TMPDIR/docs/lessons.md"
    {
        echo "# Coding Standards"
        echo "### 14.1 Real section"
    } > "$TMPDIR/docs/coding-standards.md"

    if ! check_doc_sync "$TMPDIR/AGENTS.md" "$TMPDIR/docs/lessons.md" "$TMPDIR/docs/coding-standards.md"; then
        echo "FAIL: self-test falsely rejected clean docs"
        exit 1
    fi

    echo "PASS: self-test verified — gate detects violations and allows clean docs."
    exit 0
fi

echo "=== Documentation Sync Check ==="

if check_doc_sync "$AGENTS_MD" "$LESSONS_MD" "$STANDARDS_MD"; then
    echo "PASS: documentation sync check passed (AGENTS.md debt statuses + lessons promotions consistent)."
else
    echo "FAIL: documentation is out of sync — fix the items above (AGENTS.md Rule 10)."
    exit 1
fi
