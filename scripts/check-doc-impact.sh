#!/usr/bin/env bash
# Documentation Change-Impact Gate — wrapper for the Python checker
#
# Usage:
#   bash scripts/check-doc-impact.sh --base <sha> --head <sha> [--rationale-file <file> | --rationale "<str>"]
#   bash scripts/check-doc-impact.sh --self-test
#
# Exit codes:
#   0 = PASS (required docs changed or valid rationale)
#   1 = FAIL (missing docs, invalid rationale, unmapped in-scope path, map error)
#   2 = SKIP (all-zero before SHA — no comparison base)
set -euo pipefail

MAP_FILE="docs/documentation-impact-map.json"

usage() {
    cat <<'EOF'
Documentation Change-Impact Gate

Usage:
  bash scripts/check-doc-impact.sh --base <sha> --head <sha> [--rationale-file <file> | --rationale "<str>"]
  bash scripts/check-doc-impact.sh --self-test

Options:
  --base <sha>            Base commit SHA (required unless --self-test)
  --head <sha>            Head commit SHA (required unless --self-test)
  --rationale-file <file> Path to file containing no-impact rationale
  --rationale <str>       No-impact rationale string directly
  --self-test             Run self-tests (plants violations in temp fixtures)
  --map <file>            Impact map file (default: docs/documentation-impact-map.json)
  -h, --help              Show this help

Rationale format (in PR body or commit trailer):
  Docs-Impact: none - <specific reason>

Exit codes:
  0 = PASS (required documentation changed or valid rationale provided)
  1 = FAIL (missing documentation, invalid rationale, unmapped in-scope path, or map error)
  2 = SKIP (all-zero before SHA — no comparison base available)
EOF
}

check_doc_impact() {
    local base="" head="" rationale_file="" rationale="" map_file="$MAP_FILE"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --base)
                base="$2"; shift 2 ;;
            --head)
                head="$2"; shift 2 ;;
            --rationale-file)
                rationale_file="$2"; shift 2 ;;
            --rationale)
                rationale="$2"; shift 2 ;;
            --map)
                map_file="$2"; shift 2 ;;
            --self-test)
                python3 scripts/doc_impact.py --self-test
                return $?
                ;;
            -h|--help)
                usage
                return 0
                ;;
            *)
                echo "FAIL: unknown argument: $1" >&2
                usage
                return 1
                ;;
        esac
    done

    if [[ -z "$base" || -z "$head" ]]; then
        echo "FAIL: --base and --head are required" >&2
        usage
        return 1
    fi

    if [[ ! -f "$map_file" ]]; then
        echo "FAIL: impact map not found: $map_file" >&2
        return 1
    fi

    python3 scripts/doc_impact.py \
        --base "$base" \
        --head "$head" \
        ${rationale_file:+--rationale-file "$rationale_file"} \
        ${rationale:+--rationale "$rationale"} \
        --map "$map_file"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    check_doc_impact "$@"
fi