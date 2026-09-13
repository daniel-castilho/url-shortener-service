#!/usr/bin/env bash
# generate-package-info.sh — Generate a package-info.java template for a Business Component
#
# Usage: ./scripts/generate-package-info.sh <component-name> <package-path>
# Example: ./scripts/generate-package-info.sh RateLimiting ca/tyny/urlshortener/core/ports/outgoing
#
# The script creates a package-info.java template with EARS sections, Ports, and ADRs placeholders.
# It does NOT overwrite existing files.

set -euo pipefail

COMPONENT_NAME="${1:-}"
PACKAGE_PATH="${2:-}"

if [[ -z "$COMPONENT_NAME" || -z "$PACKAGE_PATH" ]]; then
    echo "Usage: $0 <component-name> <package-path>" >&2
    echo "Example: $0 RateLimiting ca/tyny/urlshortener/core/ports/outgoing" >&2
    exit 1
fi

# Convert path to package notation
PACKAGE_NOTATION="${PACKAGE_PATH//\//.}"
FILE_PATH="src/main/java/${PACKAGE_PATH}/package-info.java"

if [[ -f "$FILE_PATH" ]]; then
    echo "File already exists: $FILE_PATH" >&2
    echo "Not overwriting. Remove or backup first if you want to regenerate." >&2
    exit 1
fi

# Component ID for requirement prefixes (uppercase, hyphens allowed)
COMP_ID=$(echo "$COMPONENT_NAME" | tr '[:lower:]' '[:upper:]' | sed 's/[^A-Z0-9]/-/g')

# The Javadoc must come BEFORE the package statement in a package-info.java
# (a package statement may appear only once per file).
cat > "$FILE_PATH" <<EOF
/**
 * # Component: ${COMPONENT_NAME}
 *
 * ## Purpose
 * <Describe the business capability this component provides in 1-2 sentences.>
 *
 * ## Requirements (EARS)
 *
 * ### REQ-${COMP_ID}-001
 * **When** <trigger condition>,
 * **the Business Component shall** <observable response>.
 *
 * ### REQ-${COMP_ID}-002
 * **When** <trigger condition>,
 * **the Business Component shall** <observable response>.
 *
 * ### REQ-${COMP_ID}-003
 * **When** <trigger condition>,
 * **the Business Component shall** <observable response>.
 *
 * ## Ports (Contracts)
 * - Inbound: <UseCase1>, <UseCase2>
 * - Outbound: <Port1>, <Port2>
 *
 * ## Local Decisions (ADR inline)
 * - <Decision 1>: <justification>
 * - <Decision 2>: <justification>
 *
 * @spec-complete false
 */
package ${PACKAGE_NOTATION};
EOF

echo "Created template: $FILE_PATH"
echo "Edit it to add real EARS requirements, Ports, and local ADRs."
echo "Set @spec-complete true when all requirements are traced to tests."