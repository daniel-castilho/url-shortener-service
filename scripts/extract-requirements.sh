#!/usr/bin/env bash
# extract-requirements.sh — Extract EARS requirement IDs from package-info.java files
#
# Usage: ./scripts/extract-requirements.sh [directory]
# Default directory: src/main/java
#
# Output: JSON array of objects with component, requirement ID, EARS text, and line number.
# Used by check-living-spec.sh and CI reporting.

set -euo pipefail

SEARCH_DIR="${1:-src/main/java}"

if [[ ! -d "$SEARCH_DIR" ]]; then
    echo "Directory not found: $SEARCH_DIR" >&2
    exit 1
fi

# Find all package-info.java files
mapfile -t FILES < <(find "$SEARCH_DIR" -name "package-info.java" -type f | sort)

if [[ ${#FILES[@]} -eq 0 ]]; then
    echo "[]"
    exit 0
fi

echo "["
first=true

for FILE in "${FILES[@]}"; do
    # Extract component name from the Javadoc: " * # Component: <Name>"
    COMPONENT=$(grep -m1 '# Component:' "$FILE" | sed 's/.*# Component:[[:space:]]*//' | sed 's/[[:space:]]*$//')
    # Fallback to directory name if not found
    if [[ -z "$COMPONENT" ]]; then
        COMPONENT=$(dirname "$FILE" | sed 's|.*/||')
    fi

    # Extract lines matching "### REQ-<ID>" pattern
    # Pattern: ### REQ-<COMPONENT>-<NNN> followed by text until next ### or ## or EOF
    while IFS= read -r line; do
        # Match lines like " * ### REQ-XXX" or "### REQ-XXX" (Javadoc comment prefix handled)
        if [[ "$line" =~ ^[[:space:]]*[*]?[[:space:]]*###[[:space:]]+(REQ-[A-Z0-9-]+) ]]; then
            REQ_ID="${BASH_REMATCH[1]}"
            # Get line number
            LINE_NUM=$(grep -n "### $REQ_ID" "$FILE" | head -1 | cut -d: -f1)
            # Extract EARS text (next non-empty lines until next ### or ## or @spec-complete)
            EARS_TEXT=$(awk -v start="$((LINE_NUM + 1))" '
                NR >= start {
                    if (/^[[:space:]]*###/ || /^[[:space:]]*##/ || /@spec-complete/) exit
                    if (NF > 0) {
                        gsub(/^[[:space:]]*[*]?[[:space:]]*/, "")
                        gsub(/"/, "\\\"")
                        print
                    }
                }
            ' "$FILE" | head -5 | paste -sd' ' -)
            # Clean up EARS_TEXT
            EARS_TEXT=$(echo "$EARS_TEXT" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')

            if [[ "$first" == "true" ]]; then
                first=false
            else
                echo ","
            fi
            echo "  {\"component\": \"$COMPONENT\", \"requirement\": \"$REQ_ID\", \"ears\": \"$EARS_TEXT\", \"line\": $LINE_NUM, \"file\": \"$FILE\"}"
        fi
    done < "$FILE"
done

echo "]"