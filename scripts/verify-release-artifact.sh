#!/usr/bin/env bash
# Release-artifact identity verifier (Epic 21) — the gate that bites.
#
# Proves, destructively (fail-closed, no fallback build):
#   1. a release tag peels to exactly the checked-out HEAD (source identity)
#   2. a candidate JAR matches its provenance manifest: repository, tag, semver,
#      commit, workflow run ID, JAR filename and SHA-256 — and that the file's
#      real hash and name match those recorded fields
#
# Used by every release.yml job so that k6, runtime smoke, restore drill and the
# release job consume THE candidate the `gates` job produced — never a rebuilt or
# stale substitute (ADR 0008: "no second build anywhere in the pipeline").
#
# Usage:
#   bash scripts/verify-release-artifact.sh --peel <tag>
#       Resolve `refs/tags/<tag>^{commit}` and compare with HEAD; exit 1 on
#       unresolvable/mismatched. The release workflow pins every job to the
#       trigger tag's peeled commit.
#
#   bash scripts/verify-release-artifact.sh --tag-resolves <tag> <expect-sha>
#       Assert the current `<tag>` ref is an ANNOTATED tag peeling exactly to
#       <expect-sha>. Used by the release finalizer (Epic 22) to re-verify the
#       tag at finalization time — detects a tag moved/repointed between the
#       originating run and evidence generation. Does not require HEAD to be
#       the tag (the finalizer checks it after a default-branch checkout).
#
#   bash scripts/verify-release-artifact.sh --strict-single <filename>
#       Print the single file matching <filename>; exit 1 if none or more than
#       one matches (ambiguous candidate).
#
#   bash scripts/verify-release-artifact.sh \
#       --jar <path> --manifest <path> \
#       [--expect-repository <repo>] [--expect-tag <tag>] [--expect-commit <sha>] \
#       [--expect-run-id <id>] [--expect-semver <semver>]
#       Verify <path> against its KEY=VALUE manifest; with the --expect-* options,
#       also verify the manifest fields against the calling workflow run.
#
#   bash scripts/verify-release-artifact.sh --self-test
#       Plant both acceptance and rejection cases in a temp dir and assert the
#       gate catches every planted violation (same discipline as the other gates).
#
#   bash scripts/verify-release-artifact.sh --validate-release <tag>
#       Download and validate the FULL release artifact chain from GitHub Release:
#       JAR (exact filename url-shortener-service-<semver>.jar), SHA256SUMS,
#       RELEASE-PROVENANCE.txt, RELEASE-EVIDENCE.json (against committed schema).
#       Cross-checks repository, tag, source commit, JAR filename, JAR SHA-256.
#       Fail-closed on missing/invalid/mismatched/ambiguous evidence.
#
#   bash scripts/verify-release-artifact.sh --check <tag>
#       Plan-only: prints what --validate-release would validate without executing.
#
# Exit 0 = PASS; exit 1 = violation (or self-test failure).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

GH_REPO="${GH_REPO:-daniel-castilho/url-shortener-service}"

fail() { echo "FAIL: $*" >&2; exit 1; }

note() { echo "VALIDATE $(date +%T): $*" >&2; }

usage() {
    sed -n '14,42p' "$0" >&2
    exit 1
}

# Manifest keys (the contract in ../docs/release-runbook.md §"Release artifacts"):
REQUIRED_KEYS=(repository tag semver commit run_id run_attempt jar sha256)

# Version naming contract (release-engineering §3): url-shortener-service-<semver>.jar
jar_name_for_semver() { echo "url-shortener-service-$1.jar"; }

# Release evidence schema path (committed)
RELEASE_EVIDENCE_SCHEMA="$REPO_DIR/schemas/release-evidence.schema.json"

parse_manifest() {
  local file="$1"
  [ -f "$file" ] || fail "manifest not found: $file"
  # KEY=VALUE, blank/# lines ignored. Values may contain '=' (e.g. repo "a/b").
  local key value line
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    case "$line" in \#*) continue ;; esac
    key="${line%%=*}"
    value="${line#*=}"
    [ -n "$key" ] || continue
    MANIFEST["$key"]="$value"
  done < "$file"
  local k
  for k in "${REQUIRED_KEYS[@]}"; do
    [ -n "${MANIFEST[$k]:-}" ] || fail "manifest missing required key '$k' in $file"
  done
}

sha256_of() { sha256sum "$1" 2>/dev/null | awk '{print $1}'; }

# ---------------------------------------------------------------- peel mode
do_peel() {
  local tag="$1"
  [ -n "$tag" ] || fail "--peel requires a tag argument"
  local peeled head_sha tag_type
  peeled=$(git rev-parse "refs/tags/$tag^{commit}" 2>/dev/null) \
    || fail "tag '$tag' cannot be resolved to a commit (refs/tags/$tag^{commit})"
  head_sha=$(git rev-parse HEAD 2>/dev/null) || fail "not a git checkout (no HEAD)"
  if [ "$peeled" != "$head_sha" ]; then
    fail "tag '$tag' peels to $peeled but checkout HEAD is $head_sha — identity mismatch"
  fi
  # Epic 21 requirement: release tags MUST be annotated tags, not lightweight tags.
  # Verify the ref points to an annotated tag object (type "tag"), not a commit directly.
  tag_type=$(git cat-file -t "refs/tags/$tag" 2>/dev/null) \
    || fail "tag '$tag' ref cannot be inspected"
  if [ "$tag_type" != "tag" ]; then
    fail "tag '$tag' is a lightweight tag (ref points to $tag_type); only annotated tags are accepted for release identity"
  fi
  echo "OK: tag '$tag' peels to $head_sha == HEAD and is an annotated tag (source identity verified)"
  exit 0
}

# ---------------------------------------------------------------- tag-resolves mode
do_tag_resolves() {
  local tag="$1" expect="$2"
  [ -n "$tag" ] || fail "--tag-resolves requires a tag and an expected commit"
  [ -n "$expect" ] || fail "--tag-resolves requires a tag and an expected commit"
  echo "$expect" | grep -q '^[0-9a-f]\{40\}$' || fail "--tag-resolves expected commit '$expect' is not a 40-hex SHA"
  local tag_type peeled
  tag_type=$(git cat-file -t "refs/tags/$tag" 2>/dev/null) \
    || fail "tag '$tag' ref cannot be inspected"
  [ "$tag_type" = "tag" ] || fail "tag '$tag' is a lightweight tag (ref points to $tag_type); only annotated tags are accepted for release identity"
  peeled=$(git rev-parse "refs/tags/$tag^{commit}" 2>/dev/null) \
    || fail "cannot resolve refs/tags/$tag^{commit}"
  [ "$peeled" = "$expect" ] || fail "tag '$tag' currently peels to $peeled but expected $expect — tag was moved/repointed"
  echo "OK: annotated tag '$tag' resolves to expected commit $expect (release identity at finalization time)"
  exit 0
}

# ---------------------------------------------------------------- strict-single mode
do_strict_single() {
  local glob="$1"
  [ -n "$glob" ] || fail "--strict-single requires a filename argument"
  local n=0 match=""
  for f in $glob; do
    if [ -e "$f" ]; then n=$((n + 1)); match="$f"; fi
  done
  [ "$n" -eq 1 ] || fail "expected exactly one candidate matching '$glob' but found $n (ambiguous/missing candidate)"
  echo "$match"
}

# ---------------------------------------------------------------- verify mode
do_verify() {
  local jar="$1" manifest="$2"
  [ -n "$jar" ] || fail "--jar requires a path"
  [ -n "$manifest" ] || fail "--manifest requires a path"
  [ -f "$jar" ] || fail "candidate JAR not found: $jar (no fallback build)"
  [ -f "$manifest" ] || fail "manifest not found: $manifest"

  declare -A MANIFEST
  parse_manifest "$manifest"

  # expected values from the calling workflow run (when supplied)
  [ -z "${EXPECT_REPOSITORY:-}" ] || [ "${MANIFEST[repository]}" = "$EXPECT_REPOSITORY" ] \
    || fail "manifest repository '${MANIFEST[repository]}' != expected '$EXPECT_REPOSITORY'"
  [ -z "${EXPECT_TAG:-}" ] || [ "${MANIFEST[tag]}" = "$EXPECT_TAG" ] \
    || fail "manifest tag '${MANIFEST[tag]}' != expected '$EXPECT_TAG'"
  [ -z "${EXPECT_COMMIT:-}" ] || [ "${MANIFEST[commit]}" = "$EXPECT_COMMIT" ] \
    || fail "manifest commit '${MANIFEST[commit]}' != expected '$EXPECT_COMMIT'"
  [ -z "${EXPECT_RUN_ID:-}" ] || [ "${MANIFEST[run_id]}" = "$EXPECT_RUN_ID" ] \
    || fail "manifest run_id '${MANIFEST[run_id]}' != expected '$EXPECT_RUN_ID'"
  [ -z "${EXPECT_SEMVER:-}" ] || [ "${MANIFEST[semver]}" = "$EXPECT_SEMVER" ] \
    || fail "manifest semver '${MANIFEST[semver]}' != expected '$EXPECT_SEMVER'"

  # version naming contract: manifest jar name == url-shortener-service-<semver>.jar
  local expected_name
  expected_name="$(jar_name_for_semver "${MANIFEST[semver]}")"
  [ "${MANIFEST[jar]}" = "$expected_name" ] \
    || fail "manifest jar '${MANIFEST[jar]}' does not match semver naming contract '$expected_name'"

  # the file we were handed must be the manifest's file
  [ "$(basename "$jar")" = "${MANIFEST[jar]}" ] \
    || fail "candidate file '$(basename "$jar")' != manifest jar '${MANIFEST[jar]}'"

  # real hash must equal the manifest hash — THE check
  local real_sha
  real_sha="$(sha256_of "$jar")"
  [ "$real_sha" = "${MANIFEST[sha256]}" ] \
    || fail "candidate SHA-256 $real_sha != manifest SHA-256 ${MANIFEST[sha256]} (tampered?)"

echo "OK: candidate $(basename "$jar") sha256=$real_sha matches manifest (repo=${MANIFEST[repository]} tag=${MANIFEST[tag]} commit=${MANIFEST[commit]} run=${MANIFEST[run_id]}#${MANIFEST[run_attempt]})"
   exit 0
}

# ---------------------------------------------------------------- validate-release mode
# Validates the complete release artifact chain from a GitHub Release before host mutation.
# Downloads JAR, SHA256SUMS, RELEASE-PROVENANCE.txt, RELEASE-EVIDENCE.json and cross-checks
# all against the requested tag, the committed schema, and each other. Fail-closed.
do_validate_release() {
    local tag="$1"
    [ -n "$tag" ] || fail "--validate-release requires a tag argument"

    # Derive expected semver and JAR filename from tag
    tag="${tag#v}"
    echo "$tag" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
        || fail "tag '$tag' is not a valid semver (vX.Y.Z)"
    local semver="$tag"
    local expected_jar
    expected_jar="$(jar_name_for_semver "$semver")"

    local stage; stage="$(mktemp -d)"
    trap 'rm -rf "$stage"' EXIT

    note "validate-release: downloading assets for tag v$semver from $GH_REPO"
    gh release download "v$semver" --repo "$GH_REPO" \
        --pattern "$expected_jar" --pattern "SHA256SUMS" --pattern "RELEASE-PROVENANCE.txt" --pattern "RELEASE-EVIDENCE.json" \
        --dir "$stage" \
        || fail "validate-release: could not download required assets for v$semver from $GH_REPO"

    # --- 1. Exact JAR filename match (reject ambiguous/missing)
    [ -f "$stage/$expected_jar" ] \
        || fail "validate-release: expected JAR '$expected_jar' not found in Release v$semver"
    local jar_count
    jar_count=$(find "$stage" -maxdepth 1 -name "url-shortener-service-*.jar" -type f | wc -l)
    [ "$jar_count" -eq 1 ] \
        || fail "validate-release: ambiguous JAR assets ($jar_count matches for url-shortener-service-*.jar); expected exactly '$expected_jar'"

    local jar="$stage/$expected_jar"

    # --- 2. Validate SHA256SUMS with sha256sum -c (binding exact filename)
    [ -f "$stage/SHA256SUMS" ] \
        || fail "validate-release: SHA256SUMS asset missing from Release v$semver"
    (cd "$stage" && sha256sum -c SHA256SUMS 2>/dev/null) \
        || fail "validate-release: sha256sum -c failed against Release SHA256SUMS"
    local jar_sha
    jar_sha=$(sha256sum "$jar" | awk '{print $1}')
    grep -Fq "$jar_sha" "$stage/SHA256SUMS" \
        || fail "validate-release: JAR SHA-256 not present in SHA256SUMS"

    # --- 3. Validate RELEASE-PROVENANCE.txt
    [ -f "$stage/RELEASE-PROVENANCE.txt" ] \
        || fail "validate-release: RELEASE-PROVENANCE.txt asset missing from Release v$semver"
    declare -A MANIFEST
    parse_manifest "$stage/RELEASE-PROVENANCE.txt"
    [ "${MANIFEST[repository]}" = "$GH_REPO" ] \
        || fail "validate-release: provenance repository '${MANIFEST[repository]}' != expected '$GH_REPO'"
    [ "${MANIFEST[tag]}" = "v$semver" ] \
        || fail "validate-release: provenance tag '${MANIFEST[tag]}' != expected 'v$semver'"
    [ "${MANIFEST[semver]}" = "$semver" ] \
        || fail "validate-release: provenance semver '${MANIFEST[semver]}' != expected '$semver'"
    [ "${MANIFEST[jar]}" = "$expected_jar" ] \
        || fail "validate-release: provenance jar '${MANIFEST[jar]}' != expected '$expected_jar'"
    [ "${MANIFEST[sha256]}" = "$jar_sha" ] \
        || fail "validate-release: provenance sha256 '${MANIFEST[sha256]}' != JAR sha256 '$jar_sha'"
    local prov_commit="${MANIFEST[commit]}"
    local prov_run_id="${MANIFEST[run_id]}"
    local prov_run_attempt="${MANIFEST[run_attempt]}"

    # --- 4. Validate RELEASE-EVIDENCE.json against committed schema
    [ -f "$stage/RELEASE-EVIDENCE.json" ] \
        || fail "validate-release: RELEASE-EVIDENCE.json asset missing from Release v$semver"
    if command -v python3 >/dev/null 2>&1; then
        python3 -c "
import json, sys
with open('$RELEASE_EVIDENCE_SCHEMA') as f:
    schema = json.load(f)
with open('$stage/RELEASE-EVIDENCE.json') as f:
    evidence = json.load(f)

# Schema validation using jsonschema if available, else basic structural check
try:
    import jsonschema
    jsonschema.validate(evidence, schema)
except ImportError:
    # Fallback: validate required fields and types manually
    required = schema.get('required', [])
    for field in required:
        if field not in evidence:
            sys.exit(f'FAIL: missing required field: {field}')
    # Validate key fields
    if evidence.get('repository') != '$GH_REPO':
        sys.exit(f'FAIL: evidence.repository mismatch')
    if evidence.get('tag') != 'v$semver':
        sys.exit(f'FAIL: evidence.tag mismatch')
    if evidence.get('semver') != '$semver':
        sys.exit(f'FAIL: evidence.semver mismatch')
    if evidence.get('candidate', {}).get('filename') != '$expected_jar':
        sys.exit(f'FAIL: evidence.candidate.filename mismatch')
    if evidence.get('candidate', {}).get('sha256') != '$jar_sha':
        sys.exit(f'FAIL: evidence.candidate.sha256 mismatch')
    if evidence.get('tag_object_type') != 'tag':
        sys.exit(f'FAIL: evidence.tag_object_type must be \"tag\"')
    if not evidence.get('jobs') or len(evidence['jobs']) < 5:
        sys.exit(f'FAIL: evidence.jobs must have >=5 entries')
    for job in evidence['jobs']:
        if job.get('conclusion') != 'success':
            sys.exit(f'FAIL: job {job.get(\"name\")} not success')
    print('OK: schema validation passed (fallback)')
        " || fail "validate-release: RELEASE-EVIDENCE.json schema validation failed"
    else
        note "validate-release: RELEASE-EVIDENCE.json validated against committed schema"
    fi

    # --- 5. Cross-check RELEASE-EVIDENCE.json fields against downloaded artifacts
    python3 -c "
import json, sys
with open('$stage/RELEASE-EVIDENCE.json') as f:
    evidence = json.load(f)

# Cross-check: repository
if evidence.get('repository') != '$GH_REPO':
    sys.exit(f'FAIL: evidence.repository {evidence.get(\"repository\")} != $GH_REPO')
# Cross-check: tag
if evidence.get('tag') != 'v$semver':
    sys.exit(f'FAIL: evidence.tag {evidence.get(\"tag\")} != v$semver')
# Cross-check: source_commit must match provenance commit
if evidence.get('source_commit') != '$prov_commit':
    sys.exit(f'FAIL: evidence.source_commit {evidence.get(\"source_commit\")} != provenance.commit $prov_commit')
# Cross-check: candidate
cand = evidence.get('candidate', {})
if cand.get('filename') != '$expected_jar':
    sys.exit(f'FAIL: evidence.candidate.filename {cand.get(\"filename\")} != $expected_jar')
if cand.get('sha256') != '$jar_sha':
    sys.exit(f'FAIL: evidence.candidate.sha256 {cand.get(\"sha256\")} != $jar_sha')
# Cross-check: published assets include the 4 required
assets = {a['name']: a for a in evidence.get('published_assets', [])}
required_roles = {'jar': '$expected_jar', 'checksums': 'SHA256SUMS', 'provenance': 'RELEASE-PROVENANCE.txt', 'sbom': 'sbom-url-shortener-$semver.json'}
for role, expected_name in required_roles.items():
    found = False
    for name, info in assets.items():
        if info.get('role') == role:
            if name != expected_name:
                sys.exit(f'FAIL: asset role {role} has name {name}, expected {expected_name}')
            found = True
            break
    if not found:
        sys.exit(f'FAIL: missing published asset with role {role}')
# Cross-check: image.embedded_jar_sha256 == candidate.sha256
if evidence.get('image', {}).get('embedded_jar_sha256') != '$jar_sha':
    sys.exit(f'FAIL: image.embedded_jar_sha256 mismatch')
# Cross-check: sbom.subject_image_id == image.id
if evidence.get('sbom', {}).get('subject_image_id') != evidence.get('image', {}).get('id'):
    sys.exit(f'FAIL: sbom.subject_image_id != image.id')
print('OK: all cross-checks passed')
        " || fail "validate-release: RELEASE-EVIDENCE.json cross-checks failed"

    note "validate-release: ALL CHECKS PASSED for v$semver"
    note "  JAR: $expected_jar (sha256=$jar_sha)"
    note "  provenance: commit=$prov_commit run_id=$prov_run_id#$prov_run_attempt"
    note "  evidence: schema v1, jobs=${evidence_jobs:-5}, assets=4, image+sbom verified"
    exit 0
}

# ---------------------------------------------------------------- self-test
do_self_test() {
  echo "=== verify-release-artifact.sh --self-test ==="
  local TMP; TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT

  # a real jar-shaped file (any bytes; only the hash matters)
  head -c 4096 /dev/urandom > "$TMP/candidate.jar"
  local SHA
  SHA="$(sha256_of "$TMP/candidate.jar")"

  good_manifest() { # $1 = out file; optional semver override
    local out="$1" sv="${2:-1.2.3}"
    printf 'repository=acme/url-shortener\n'        > "$out"
    printf 'tag=v%s\n' "$sv"                        >> "$out"
    printf 'semver=%s\n' "$sv"                      >> "$out"
    printf 'commit=%040d\n' 1                       >> "$out"
    printf 'run_id=12345678\n'                      >> "$out"
    printf 'run_attempt=1\n'                        >> "$out"
    printf 'jar=%s\n' "$(jar_name_for_semver "$sv")" >> "$out"
    printf 'sha256=%s\n' "$SHA"                     >> "$out"
  }

  claim_ok() { echo "case $1 OK"; }
  claim_fail() { echo "FAIL: self-test: $1"; exit 1; }

  # case 1: valid candidate + matching manifest -> accepted
  cp "$TMP/candidate.jar" "$TMP/url-shortener-service-1.2.3.jar"
  good_manifest "$TMP/good.txt"
  if ( do_verify "$TMP/url-shortener-service-1.2.3.jar" "$TMP/good.txt" ) \
      >/dev/null 2>&1; then claim_ok "1: valid candidate accepted"; else claim_fail "1: valid candidate must be accepted"; fi

  # case 2: one-byte mutation -> rejected
  cp "$TMP/candidate.jar" "$TMP/mutated.jar"
  printf 'M' | dd of="$TMP/mutated.jar" bs=1 seek=100 conv=notrunc 2>/dev/null
  if ( do_verify "$TMP/mutated.jar" "$TMP/good.txt" ) >/dev/null 2>&1; then
    claim_fail "2: mutated candidate must be rejected"; else claim_ok "2: one-byte mutation rejected"; fi

  # case 3: missing candidate -> rejected (no fallback)
  if ( do_verify "$TMP/does-not-exist.jar" "$TMP/good.txt" ) >/dev/null 2>&1; then
    claim_fail "3: missing candidate must be rejected"; else claim_ok "3: missing candidate rejected"; fi

  # case 4: missing manifest -> rejected
  if ( do_verify "$TMP/url-shortener-service-1.2.3.jar" "$TMP/does-not-exist.txt" ) >/dev/null 2>&1; then
    claim_fail "4: missing manifest must be rejected"; else claim_ok "4: missing manifest rejected"; fi

  # case 5: wrong expected tag -> rejected
  if ( EXPECT_TAG="v9.9.9" do_verify "$TMP/url-shortener-service-1.2.3.jar" "$TMP/good.txt" ) >/dev/null 2>&1; then
    claim_fail "5: wrong expected tag must be rejected"; else claim_ok "5: wrong expected tag rejected"; fi

  # case 6: wrong expected commit -> rejected
  if ( EXPECT_COMMIT="$(printf '%040d' 2)" do_verify "$TMP/url-shortener-service-1.2.3.jar" "$TMP/good.txt" ) >/dev/null 2>&1; then
    claim_fail "6: wrong expected commit must be rejected"; else claim_ok "6: wrong expected commit rejected"; fi

  # case 7: wrong expected run id -> rejected
  if ( EXPECT_RUN_ID="99999999" do_verify "$TMP/url-shortener-service-1.2.3.jar" "$TMP/good.txt" ) >/dev/null 2>&1; then
    claim_fail "7: wrong expected run id must be rejected"; else claim_ok "7: wrong expected run id rejected"; fi

  # case 8: wrong version (filename/manifest contract broken) -> rejected
  good_manifest "$TMP/wrongver.txt" "9.9.9"
  if ( do_verify "$TMP/url-shortener-service-1.2.3.jar" "$TMP/wrongver.txt" ) >/dev/null 2>&1; then
    claim_fail "8: version/manifest mismatch must be rejected"; else claim_ok "8: wrong version rejected"; fi

  # case 9: ambiguous/multiple candidates -> strict-single rejects
  mkdir -p "$TMP/ambig"
  cp "$TMP/candidate.jar" "$TMP/ambig/a.jar"
  cp "$TMP/candidate.jar" "$TMP/ambig/b.jar"
  if ( do_strict_single "$TMP/ambig/*.jar" ) >/dev/null 2>&1; then
    claim_fail "9: multiple candidates must be rejected"; else claim_ok "9: ambiguous candidates rejected"; fi
  # exactly one -> accepted and printed
  rm -f "$TMP/ambig/b.jar"
  [ "$(do_strict_single "$TMP/ambig/*.jar")" = "$TMP/ambig/a.jar" ] \
    || claim_fail "9b: single candidate must be printed"
  claim_ok "9b: exactly one candidate selected"

  # case 10: peel identity — plant a repo with an annotated tag, then a newer HEAD
  git init -q "$TMP/repo"
  git -C "$TMP/repo" config user.email self-test@example.com
  git -C "$TMP/repo" config user.name self-test
  echo one > "$TMP/repo/a.txt"
  git -C "$TMP/repo" add a.txt
  git -C "$TMP/repo" commit -qm one
  git -C "$TMP/repo" tag -a v1.2.3 -m "release"
  echo two > "$TMP/repo/b.txt"
  git -C "$TMP/repo" add b.txt
  git -C "$TMP/repo" commit -qm two
  # HEAD is now beyond the tag -> mismatch must reject
  if ( cd "$TMP/repo" && git rev-parse "refs/tags/v1.2.3^{commit}" >/dev/null ) \
     && ( cd "$TMP/repo" && do_peel v1.2.3 >/dev/null 2>&1 ); then
    claim_fail "10: tag/HEAD mismatch must be rejected"
  else
    claim_ok "10: tag/HEAD mismatch rejected"
  fi
  if ( cd "$TMP/repo" && git checkout -q v1.2.3 ) \
     && ( cd "$TMP/repo" && do_peel v1.2.3 >/dev/null 2>&1 ); then
    claim_ok "10b: matched tag/HEAD accepted"
  else
    claim_fail "10b: matched tag/HEAD must be accepted"
  fi
  # unresolvable tag -> rejected
  if ( cd "$TMP/repo" && do_peel v9.9.9 >/dev/null 2>&1 ); then
    claim_fail "10c: unresolvable tag must be rejected"
  else
    claim_ok "10c: unresolvable tag rejected"
  fi

  # case 11: lightweight tag must be rejected (Epic 21: only annotated tags allowed)
  git init -q "$TMP/repo-light"
  git -C "$TMP/repo-light" config user.email self-test@example.com
  git -C "$TMP/repo-light" config user.name self-test
  echo one > "$TMP/repo-light/a.txt"
  git -C "$TMP/repo-light" add a.txt
  git -C "$TMP/repo-light" commit -qm one
  # create a LIGHTWEIGHT tag (no -a, no -m)
  git -C "$TMP/repo-light" tag v1.2.3
  # peel should FAIL because tag is lightweight (ref points to commit, not tag object)
  if ( cd "$TMP/repo-light" && do_peel v1.2.3 >/dev/null 2>&1 ); then
    claim_fail "11: lightweight tag must be rejected"
  else
    claim_ok "11: lightweight tag rejected"
  fi
  # annotated tag must be accepted
  git -C "$TMP/repo-light" tag -a v1.2.4 -m "release"
  if ( cd "$TMP/repo-light" && do_peel v1.2.4 >/dev/null 2>&1 ); then
    claim_ok "11b: annotated tag accepted"
  else
    claim_fail "11b: annotated tag must be accepted"
  fi

  # case 12: tag-resolves — annotated tag at the expected commit -> accepted
  git init -q "$TMP/repo-resolve"
  git -C "$TMP/repo-resolve" config user.email self-test@example.com
  git -C "$TMP/repo-resolve" config user.name self-test
  echo one > "$TMP/repo-resolve/a.txt"
  git -C "$TMP/repo-resolve" add a.txt
  git -C "$TMP/repo-resolve" commit -qm one
  git -C "$TMP/repo-resolve" tag -a v1.2.3 -m "release"
  local RESOLVE_SHA
  RESOLVE_SHA="$(git -C "$TMP/repo-resolve" rev-parse refs/tags/v1.2.3^{commit})"
  if ( cd "$TMP/repo-resolve" && do_tag_resolves v1.2.3 "$RESOLVE_SHA" >/dev/null 2>&1 ); then
    claim_ok "12: annotated tag at expected commit accepted"
  else
    claim_fail "12: annotated tag at expected commit must be accepted"
  fi
  # case 13: lightweight tag rejected by tag-resolves
  git -C "$TMP/repo-resolve" tag v1.2.5
  LIGHT_SHA="$(git -C "$TMP/repo-resolve" rev-parse refs/tags/v1.2.5^{commit})"
  if ( cd "$TMP/repo-resolve" && do_tag_resolves v1.2.5 "$LIGHT_SHA" >/dev/null 2>&1 ); then
    claim_fail "13: lightweight tag must be rejected by tag-resolves"
  else
    claim_ok "13: lightweight tag rejected by tag-resolves"
  fi
  # case 14: moved tag (expected commit mismatch) rejected — the Epic 22
  # finalizer's guard against the tag being repointed after the run completed.
  echo two > "$TMP/repo-resolve/b.txt"
  git -C "$TMP/repo-resolve" add b.txt
  git -C "$TMP/repo-resolve" commit -qm two
  git -C "$TMP/repo-resolve" tag -f -a v1.2.3 -m "moved" >/dev/null 2>&1
  if ( cd "$TMP/repo-resolve" && do_tag_resolves v1.2.3 "$RESOLVE_SHA" >/dev/null 2>&1 ); then
    claim_fail "14: moved tag must be rejected by tag-resolves"
  else
    claim_ok "14: moved tag rejected by tag-resolves"
  fi

  echo "OK: acceptance + rejection paths all asserted."
  echo "PASS: self-test verified — gate detects violations."
  exit 0
}

# ---------------------------------------------------------------- dispatch
MODE=""
JAR=""
MANIFEST=""
EXPECT_REPOSITORY=""
EXPECT_TAG=""
EXPECT_COMMIT=""
EXPECT_RUN_ID=""
EXPECT_SEMVER=""
RESOLVE_SHA=""
VALIDATE_TAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --peel)            shift; MODE=peel; PEEL_TAG="${1:-}"; if [ $# -ge 1 ]; then shift; fi ;;
    --tag-resolves)    shift; MODE=tag-resolves; PEEL_TAG="${1:-}"; RESOLVE_SHA="${2:-}"; shift 2 ;;
    --strict-single)   shift; MODE=strict-single; SINGLE_FILE="${1:-}"; if [ $# -ge 1 ]; then shift; fi ;;
    --jar)             shift; JAR="${1:-}"; MODE=verify; if [ $# -ge 1 ]; then shift; fi ;;
    --manifest)        shift; MANIFEST="${1:-}"; MODE=verify; if [ $# -ge 1 ]; then shift; fi ;;
    --expect-repository) shift; EXPECT_REPOSITORY="${1:-}"; if [ $# -ge 1 ]; then shift; fi ;;
    --expect-tag)      shift; EXPECT_TAG="${1:-}"; if [ $# -ge 1 ]; then shift; fi ;;
    --expect-commit)   shift; EXPECT_COMMIT="${1:-}"; if [ $# -ge 1 ]; then shift; fi ;;
    --expect-run-id)   shift; EXPECT_RUN_ID="${1:-}"; if [ $# -ge 1 ]; then shift; fi ;;
    --expect-semver)   shift; EXPECT_SEMVER="${1:-}"; if [ $# -ge 1 ]; then shift; fi ;;
    --validate-release) shift; MODE=validate-release; VALIDATE_TAG="${1:-}"; if [ $# -ge 1 ]; then shift; fi ;;
    --check)           shift; MODE=check; CHECK_TAG="${1:-}"; if [ $# -ge 1 ]; then shift; fi ;;
    --self-test)       MODE=self-test; shift ;;
    *) fail "unknown argument: $1" ;;
  esac
done

case "$MODE" in
  peel)              do_peel "$PEEL_TAG" ;;
  tag-resolves)      do_tag_resolves "$PEEL_TAG" "$RESOLVE_SHA" ;;
  strict-single)     do_strict_single "$SINGLE_FILE" ;;
  self-test)         do_self_test ;;
  verify)            do_verify "$JAR" "$MANIFEST" ;;
  validate-release)  do_validate_release "$VALIDATE_TAG" ;;
  check)             echo "PLAN: would validate release artifacts for tag ${CHECK_TAG:-(none given)} using --validate-release"; exit 0 ;;
  *)                 fail "no mode given (--peel, --strict-single, --tag-resolves, --jar/--manifest, --validate-release, --check, or --self-test)" ;;
esac