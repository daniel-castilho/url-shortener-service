#!/usr/bin/env bash
# write-release-receipt.sh — emit a machine-readable release receipt (Epic 22).
#
# Each job of the release pipeline writes a receipt immediately AFTER it
# successfully verified the single release candidate (ADR 0008 — one build per
# tag). The finalizer later downloads every receipt by the exact originating
# run ID, validates that each binds to the same repository/tag/commit/run, and
# cross-checks that they all agree on the candidate SHA-256. Receipts are
# observed facts, never policy claims.
#
# The receipt format lives HERE, in one place, so the workflow files cannot
# drift from the contract understood by scripts/release_evidence.py.
#
# Usage:
#   bash scripts/write-release-receipt.sh \
#       --job gates|k6-gate|runtime-smoke|restore-drill|release \
#       --out <path> --commit <40-hex> \
#       --filename <jar-filename> --sha256 <64-hex> \
#       [--image-id sha256:<64-hex>] [--embedded-jar-sha256 <64-hex>] \
#       [--sbom <sbom-filename>] [--sbom-sha256 <64-hex>]
#
# Required environment (provided by GitHub Actions):
#   GITHUB_REPOSITORY, GITHUB_REF_NAME, GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT
#
#   bash scripts/write-release-receipt.sh --self-test

set -uo pipefail

fail() { echo "FAIL: $*" >&2; exit 1; }

HEX40='^[0-9a-f]\{40\}$'
HEX64='^[0-9a-f]\{64\}$'

write_receipt() {
  local job="$1" out="$2" commit="$3" filename="$4" sha256="$5"
  [ -n "$job" ] || fail "--job is required"
  [ -n "$out" ] || fail "--out is required"
  [ -n "$commit" ] || fail "--commit is required (source commit of the release)"
  [ -n "$filename" ] || fail "--filename is required (the candidate jar filename)"
  [ -n "$sha256" ] || fail "--sha256 is required (observed candidate hash)"
  case "$job" in gates|k6-gate|runtime-smoke|restore-drill|release) ;; *) fail "unknown --job '$job'" ;; esac
  [ -n "${GITHUB_REPOSITORY:-}" ] || fail "GITHUB_REPOSITORY is not set"
  [ -n "${GITHUB_REF_NAME:-}" ] || fail "GITHUB_REF_NAME is not set (expected the vX.Y.Z tag)"
  [ -n "${GITHUB_RUN_ID:-}" ] || fail "GITHUB_RUN_ID is not set"
  [ -n "${GITHUB_RUN_ATTEMPT:-}" ] || fail "GITHUB_RUN_ATTEMPT is not set"
  echo "$commit" | grep -q "$HEX40" || fail "--commit '$commit' is not a 40-hex SHA"
  echo "$sha256" | grep -q "$HEX64" || fail "--sha256 '$sha256' is not a 64-hex digest"

  python3 - "$job" "$out" "$commit" "$filename" "$sha256" ${IMAGE_ID:-} ${EMBEDDED_JAR_SHA:-} ${SBOM:-} ${SBOM_SHA:-} <<'PYEOF'
import datetime, json, os, sys
job, out, commit, filename, sha256 = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
image_id = os.environ.get("RECEIPT_IMAGE_ID") or ""
embedded = os.environ.get("RECEIPT_EMBEDDED_JAR_SHA") or ""
sbom = os.environ.get("RECEIPT_SBOM") or ""
sbom_sha = os.environ.get("RECEIPT_SBOM_SHA") or ""
receipt = {
    "repository": os.environ["GITHUB_REPOSITORY"],
    "tag": os.environ["GITHUB_REF_NAME"],
    "semver": os.environ["GITHUB_REF_NAME"].lstrip("v"),
    "source_commit": commit,
    "run_id": int(os.environ["GITHUB_RUN_ID"]),
    "run_attempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
    "job": job,
    "filename": filename,
    "sha256": sha256,
    "produced_at_utc": datetime.datetime.now(datetime.timezone.utc)
                             .strftime("%Y-%m-%dT%H:%M:%SZ"),
}
if image_id:
    receipt["image_id"] = image_id
if embedded:
    receipt["embedded_jar_sha256"] = embedded
if sbom:
    receipt["sbom"] = sbom
if sbom_sha:
    receipt["sbom_sha256"] = sbom_sha
with open(out, "w", encoding="utf-8") as fh:
    json.dump(receipt, fh, indent=2, sort_keys=True)
    fh.write("\n")
# fail-fast: the artifact must round-trip as JSON before it leaves the job
with open(out, "r", encoding="utf-8") as fh:
    json.load(fh)
print("OK: receipt written to %s (job=%s run=%s#%s)" % (
    out, job, os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"]))
PYEOF
}

self_test() {
  echo "=== write-release-receipt.sh --self-test ==="
  local TMP; TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  local ok=1
  local SHA; SHA="$(printf 'a%.0s' {1..64})"
  local COMMIT; COMMIT="$(printf 'b%.0s' {1..40})"
  local EXPORTENV="export GITHUB_REPOSITORY=acme/repo GITHUB_REF_NAME=v1.2.3 GITHUB_RUN_ID=424242 GITHUB_RUN_ATTEMPT=1;"

  # case 1: valid receipt written, JSON parseable, ints typed, semver derived
  ( eval "$EXPORTENV"; write_receipt gates "$TMP/ok.json" "$COMMIT" url-shortener-service-1.2.3.jar "$SHA" ) \
    >/dev/null 2>&1 && ok=$((ok && 1)) || { echo "FAIL: case 1: valid receipt must be written"; ok=0; }
  python3 - "$TMP/ok.json" <<'PYEOF' || { echo "FAIL: case 1b: receipt must be valid JSON"; ok=0; }
import json, sys
r = json.load(open(sys.argv[1]))
assert r["job"] == "gates"
assert r["run_id"] == 424242 and isinstance(r["run_id"], int)
assert r["semver"] == "1.2.3"
assert r["repository"] == "acme/repo" and r["tag"] == "v1.2.3"
PYEOF
  echo "case 1 OK"

  # case 2: missing --sha256 rejected
  if ( eval "$EXPORTENV"; write_receipt gates "$TMP/no-sha.json" "$COMMIT" url-shortener-service-1.2.3.jar "" ) \
      >/dev/null 2>&1; then echo "FAIL: case 2: missing sha256 must be rejected"; ok=0; else echo "case 2 OK"; fi

  # case 3: missing GITHUB_RUN_ID rejected
  if ( eval "$EXPORTENV"; unset GITHUB_RUN_ID; \
       write_receipt gates "$TMP/no-run.json" "$COMMIT" url-shortener-service-1.2.3.jar "$SHA" ) \
      >/dev/null 2>&1; then echo "FAIL: case 3: missing GITHUB_RUN_ID must be rejected"; ok=0; else echo "case 3 OK"; fi

  # case 4: unknown job rejected
  if ( eval "$EXPORTENV"; write_receipt deploy "$TMP/bad-job.json" "$COMMIT" url-shortener-service-1.2.3.jar "$SHA" ) \
      >/dev/null 2>&1; then echo "FAIL: case 4: unknown job must be rejected"; ok=0; else echo "case 4 OK"; fi

  # case 5: optional image/sbom fields included when provided
  ( eval "$EXPORTENV"; \
    RECEIPT_IMAGE_ID="sha256:$(printf 'c%.0s' {1..64})" \
    RECEIPT_EMBEDDED_JAR_SHA="$SHA" \
    RECEIPT_SBOM="sbom-url-shortener-1.2.3.json" \
    RECEIPT_SBOM_SHA="$(printf 'd%.0s' {1..64})" \
    write_receipt release "$TMP/full.json" "$COMMIT" url-shortener-service-1.2.3.jar "$SHA" ) \
    >/dev/null 2>&1 \
    || { echo "FAIL: case 5: full receipt must be written"; ok=0; }
  python3 - "$TMP/full.json" <<'PYEOF' || { echo "FAIL: case 5b: image/sbom fields missing"; ok=0; }
import json, sys
r = json.load(open(sys.argv[1]))
assert r["job"] == "release"
assert r["image_id"].startswith("sha256:")
assert r["embedded_jar_sha256"]
assert r["sbom_sha256"]
PYEOF
  echo "case 5 OK"

  if [ "$ok" -eq 1 ]; then echo "PASS: self-test verified — receipt writer is sound."; exit 0; fi
  echo "FAIL: self-test failed."; exit 1
}

JOB=""
OUT=""
COMMIT=""
FILENAME=""
SHA256=""
IMAGE_ID=""
EMBEDDED_JAR_SHA=""
SBOM=""
SBOM_SHA=""

while [ $# -gt 0 ]; do
  case "$1" in
    --job) shift; JOB="${1:-}" ;;
    --out) shift; OUT="${1:-}" ;;
    --commit) shift; COMMIT="${1:-}" ;;
    --filename) shift; FILENAME="${1:-}" ;;
    --sha256) shift; SHA256="${1:-}" ;;
    --image-id) shift; IMAGE_ID="${1:-}" ;;
    --embedded-jar-sha256) shift; EMBEDDED_JAR_SHA="${1:-}" ;;
    --sbom) shift; SBOM="${1:-}" ;;
    --sbom-sha256) shift; SBOM_SHA="${1:-}" ;;
    --self-test) self_test; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

# pass optionals via env to keep the python step argument list simple
export RECEIPT_IMAGE_ID="$IMAGE_ID" RECEIPT_EMBEDDED_JAR_SHA="$EMBEDDED_JAR_SHA" \
       RECEIPT_SBOM="$SBOM" RECEIPT_SBOM_SHA="$SBOM_SHA"
write_receipt "$JOB" "$OUT" "$COMMIT" "$FILENAME" "$SHA256"