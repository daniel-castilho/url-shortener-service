#!/usr/bin/env bash
# finalize-release-evidence.sh — post-run release finalizer (Epic 22, story 22.3).
#
# Runs ONLY after the `Release` workflow run completes, from the
# `workflow_run`-triggered release-finalizer workflow. It verifies the tag and
# the run state, cross-checks the same-run receipts against the draft release
# assets, generates the canonical RELEASE-EVIDENCE.json (+ Markdown), attaches
# them to the draft and publishes the release as the LAST action. Any failure
# leaves the release unpublished and reports the precise named check.
#
# Identity/design contract:
#   * Never identifies the source from the finalizer's own GITHUB_SHA/attempt —
#     the originating run comes from the workflow_run event payload + GitHub
#     API for the given --origin-run-id.
#   * The current annotated tag must resolve to the originating head commit
#     right now (guards against the tag being moved after the run completed).
#   * Completion time is the GitHub jobs API max over `completed_at`; the
#     generator's clock is only the `recorded_at` timestamp.
#   * Observed facts only; no policy claims. No self-hash. Unknown schema
#     versions rejected. Verified: run, jobs, receipts, draft assets
#     (jar/provenance/sha256sums/SBOM), then generate/render/validate.
#
# Usage:
#   bash scripts/finalize-release-evidence.sh --origin-run-id <id>
#                    [--dry-run] [--fixture <dir>] [--self-test]
#
#   --dry-run   run all validation + generation but do NOT upload/publish.
#   --fixture   run against a local fixture tree (forces --dry-run) — used by
#               the self-test and rehearsals; no GitHub/git access performed.
#
# Required env in live mode: GITHUB_TOKEN/GH_TOKEN, GITHUB_REPOSITORY, and the
# EVT_* fields exposed by the workflow_run trigger (set by the workflow YAML).
#
#   Re-entry/idempotency: if the release is already published and already
#   carries RELEASE-EVIDENCE.json, the finalizer exits 0 (already finalized).

set -uo pipefail

fail() { echo "FAIL: $*" >&2; exit 1; }

ORIGIN=""
DRY_RUN=0
FIXTURE=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${GITHUB_REPOSITORY:-}"

usage() { sed -n '2,45p' "${BASH_SOURCE[0]}"; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --origin-run-id) shift; ORIGIN="${1:-}"; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --fixture) shift; FIXTURE="${1:-}"; shift ;;
    --self-test) exec python3 "$SCRIPT_DIR/finalize-release-evidence-self-test.py" ;;
    -h|--help) usage ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[ -n "$ORIGIN" ] || fail "EV-TRIGGER: --origin-run-id is required"
grep -qE '^[0-9]+$' <<< "$ORIGIN" || fail "EV-TRIGGER: --origin-run-id must be numeric, got '$ORIGIN'"

if [ -n "$FIXTURE" ]; then
  DRY_RUN=1
  [ -d "$FIXTURE" ] || fail "EV-FIXTURE: fixture dir not found: $FIXTURE"
  set -a
  # shellcheck disable=SC1090
  . "$FIXTURE/events.env"
  set +a
fi

# --------------------------------------------------------------------------
# Phase A — trigger guards (workflow_run event payload)
[ -n "${EVT_WORKFLOW_NAME:-}" ] || fail "EV-TRIGGER: EVT_WORKFLOW_NAME is not set; this script must run from the Release-workflow workflow_run trigger"
if [ "$EVT_WORKFLOW_NAME" != "Release" ]; then
  echo "SKIP: triggering workflow is '$EVT_WORKFLOW_NAME', not 'Release'; not a release finalization event"
  exit 0
fi
[ "${EVT_WORKFLOW_RUN_ID:-x}" = "$ORIGIN" ] 2>/dev/null || fail "EV-TRIGGER: origin run id $ORIGIN != workflow_run event id ${EVT_WORKFLOW_RUN_ID:-unset}"
[ -n "$REPO" ] || REPO="${EVT_ORIGIN_REPO:-}"
[ "${EVT_ORIGIN_REPO:-}" = "$REPO" ] || fail "EV-TRIGGER: origin repository '${EVT_ORIGIN_REPO:-unset}' != current '$REPO' (untrusted event)"
[ "${EVT_ORIGIN_EVENT:-}" = "push" ] || fail "EV-TRIGGER: originating event is '${EVT_ORIGIN_EVENT:-unset}', not a tag push"
TAG="${EVT_ORIGIN_BRANCH:-}"
case "$TAG" in
  v[0-9]*.[0-9]*.[0-9]*) ;;
  *) fail "EV-TRIGGER: originating head branch '$TAG' is not a vX.Y.Z release tag" ;;
esac
SEMVER="${TAG#v}"
SHA="${EVT_ORIGIN_SHA:-}"
grep -qE '^[0-9a-f]{40}$' <<< "$SHA" || fail "EV-TRIGGER: originating head sha '$SHA' is not a 40-hex commit"
[ "${EVT_ORIGIN_STATUS:-}" = "completed" ] || fail "EV-TRIGGER: originating run status is '${EVT_ORIGIN_STATUS:-unset}', not completed (still in progress)"
[ "${EVT_ORIGIN_CONCLUSION:-}" = "success" ] || fail "EV-TRIGGER: originating run conclusion is '${EVT_ORIGIN_CONCLUSION:-unset}', not success — nothing to finalize, release stays unpublished"

JAR_NAME="url-shortener-service-$SEMVER.jar"
SBOM_NAME="sbom-url-shortener-$SEMVER.json"

# --------------------------------------------------------------------------
# Phase B — current-tag identity (live only)
if [ -z "$FIXTURE" ]; then
  bash "$SCRIPT_DIR/verify-release-artifact.sh" --tag-resolves "$TAG" "$SHA" \
    || fail "EV-TAG: current refs/tags/$TAG does not resolve to the originating commit $SHA"
fi

# --------------------------------------------------------------------------
# Phase C — gather originating run state, job list and draft release
API_RUN=api_run.json
API_JOBS=api_jobs.json
DRAFT_JSON=draft.json
RECEIPTS_DIR=receipts
DRAFT_ASSETS_DIR=draft-assets

if [ -n "$FIXTURE" ]; then
  cp "$FIXTURE/run.json" "$API_RUN"
  cp "$FIXTURE/jobs.json" "$API_JOBS"
  cp "$FIXTURE/draft.json" "$DRAFT_JSON"
  RECEIPTS_DIR="$FIXTURE/receipts"
  DRAFT_ASSETS_DIR="$FIXTURE/draft-assets"
else
  [ "$REPO" = "$GITHUB_REPOSITORY" ] || fail "EV-TRIGGER: not running on the release repository"
  command -v gh >/dev/null 2>&1 || fail "EV-API: gh CLI is required in live mode"
  gh api "repos/$REPO/actions/runs/$ORIGIN" > "$API_RUN" 2>/dev/null || fail "EV-API: cannot fetch originating run $ORIGIN"
  gh api "repos/$REPO/actions/runs/$ORIGIN/jobs?per_page=100" > "$API_JOBS" 2>/dev/null || fail "EV-API: cannot fetch originating run jobs $ORIGIN"
  gh release view "$TAG" --json tagName,isDraft,assets > "$DRAFT_JSON" 2>/dev/null \
    || fail "EV-DRAFT: cannot view release for $TAG"
  mkdir -p "$DRAFT_ASSETS_DIR"; mkdir -p "$RECEIPTS_DIR"
  gh release download "$TAG" --dir "$DRAFT_ASSETS_DIR" \
    --pattern "$JAR_NAME" --pattern SHA256SUMS --pattern RELEASE-PROVENANCE.txt --pattern "$SBOM_NAME" \
    >/dev/null 2>&1 || fail "EV-DRAFT: cannot download draft assets for $TAG"
  # receipts/ must already be populated by the workflow's download-artifact steps
  # (named receipts are fetched by the originating run id); we never recreate it.
fi

# --------------------------------------------------------------------------
# Phase D+E+F — verify receipts, draft assets, provenance, SBOM; assemble facts
# The python below is the single authority for the evidence pipeline. It fails
# with named EV-* codes; on success it writes facts.json.
python3 - "$API_RUN" "$API_JOBS" "$DRAFT_JSON" "$RECEIPTS_DIR" "$DRAFT_ASSETS_DIR" \
  "$REPO" "$TAG" "$SEMVER" "$SHA" "$ORIGIN" "$JAR_NAME" "$SBOM_NAME" <<'PYEOF' || exit 1
import json, os, re, subprocess, sys, hashlib

run_path, jobs_path, draft_path, receipts_dir, assets_dir = sys.argv[1:6]
repo, tag, semver, sha, origin, jar_name, sbom_name = sys.argv[6:13]

def fail(code, msg):
    raise SystemExit(f"FAIL: {code}: {msg}")

sha64 = re.compile(r"^[0-9a-f]{64}$")

def file_sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(65536), b""):
            h.update(block)
    return h.hexdigest()

REQUIRED_ASSETS = [jar_name, "SHA256SUMS", "RELEASE-PROVENANCE.txt", sbom_name]

def parse_manifest(path):
    man = {}
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            if k:
                man[k] = v
    return man

# ---- originating run (via API source; never from the finalizer identity)
with open(run_path, "r", encoding="utf-8") as fh:
    run = json.load(fh)
if not isinstance(run, dict):
    fail("EV-API", "run payload is not an object")
run_id = run.get("id")
if int(run_id) != int(origin):
    fail("EV-API", f"run id {run_id} != origin {origin}")
if run.get("name") != "Release":
    fail("EV-API", f"originating workflow name is {run.get('name')!r}, not 'Release'")
if run.get("event") != "push":
    fail("EV-API", f"originating event is {run.get('event')!r}, not push")
if run.get("head_sha") != sha:
    fail("EV-API", f"originating head_sha {run.get('head_sha')!r} != event head sha {sha!r}")
if run.get("head_branch") != tag:
    fail("EV-API", f"originating head_branch {run.get('head_branch')!r} != event tag {tag!r}")
if run.get("status") != "completed" or run.get("conclusion") != "success":
    fail("EV-API", f"originating run must be completed/success, got {run.get('status')!r}/{run.get('conclusion')!r}")
for k in ("run_number", "run_attempt", "html_url"):
    if not run.get(k):
        fail("EV-API", f"run field {k!r} is missing")

with open(jobs_path, "r", encoding="utf-8") as fh:
    jobs_payload = json.load(fh)
jobs_list = jobs_payload.get("jobs") if isinstance(jobs_payload, dict) else jobs_payload
if not isinstance(jobs_list, list):
    fail("EV-API", "jobs payload has no jobs array")
required = ("Gates", "k6 Gate", "Runtime Smoke", "Restore Drill", "Release")
found = {j.get("name"): j for j in jobs_list}
for name in required:
    j = found.get(name)
    if j is None:
        fail("EV-JOB", f"job {name!r} missing from the originating run")
    if j.get("status") != "completed" or j.get("conclusion") != "success":
        fail("EV-JOB", f"job {name!r} did not complete successfully")
    if not j.get("completed_at"):
        fail("EV-JOB", f"job {name!r} has no completed_at")
jobs = [
    {"id": int(found[n]["id"]), "name": n, "conclusion": found[n]["conclusion"],
     "completed_at_utc": found[n]["completed_at"]}
    for n in required
]
completion = max(j["completed_at_utc"] for j in jobs)

# ---- receipts (same originating run id, one per job)
receipt_files = {f"receipt-{name}.json": name for name in ("gates", "k6-gate", "runtime-smoke", "restore-drill", "release")}
for fname, _ in receipt_files.items():
    if not os.path.isfile(os.path.join(receipts_dir, fname)):
        fail("EV-RECEIPT", f"missing receipt {fname}")
receipts = {}
for fname, job in receipt_files.items():
    try:
        with open(os.path.join(receipts_dir, fname), "r", encoding="utf-8") as fh:
            rec = json.load(fh)
    except json.JSONDecodeError as exc:
        fail("EV-RECEIPT", f"{fname} is not valid JSON: {exc}")
    if rec.get("job") != job:
        fail("EV-RECEIPT", f"{fname} declares job {rec.get('job')!r}, expected {job!r}")
    for key in ("repository", "tag", "semver", "source_commit", "run_id", "run_attempt", "filename", "sha256", "produced_at_utc"):
        if rec.get(key) in (None, ""):
            fail("EV-RECEIPT", f"{fname} is missing required field {key!r}")
    receipts[job] = rec
producer = receipts["gates"]
consumers = [receipts[n] for n in ("k6-gate", "runtime-smoke", "restore-drill", "release")]
producer_sha = producer["sha256"]
if not sha64.match(producer_sha):
    fail("EV-RECEIPT", "producer sha256 is not a 64-hex digest")

# ---- draft release
with open(draft_path, "r", encoding="utf-8") as fh:
    draft = json.load(fh)
draft_assets = draft.get("assets") or []
asset_names = [a.get("name") for a in draft_assets]
evidence_assets = [n for n in asset_names if n in ("RELEASE-EVIDENCE.json", "RELEASE-EVIDENCE.md")]
if draft.get("isDraft") is False and evidence_assets:
    open("ALREADY_FINALIZED", "w").close()
    print("SKIP: release is already published and already carries evidence — already finalized")
    sys.exit(0)
if draft.get("isDraft") is not True:
    fail("EV-DRAFT", f"release {tag} is not a draft (isDraft={draft.get('isDraft')!r}); refusing to touch a published release")
if draft.get("tagName") != tag:
    fail("EV-DRAFT", f"release tagName {draft.get('tagName')!r} != {tag!r}")
if evidence_assets:
    fail("EV-DRAFT", f"draft already contains evidence assets {evidence_assets}; refusing partial state")
missing = [a for a in REQUIRED_ASSETS if a not in asset_names]
if missing:
    fail("EV-DRAFT", f"draft release is missing assets: {missing}")

# ---- draft assets: jar, checksums, provenance, sbom
for asset in REQUIRED_ASSETS:
    if not os.path.isfile(os.path.join(assets_dir, asset)):
        fail("EV-ASSET", f"downloaded/fixture asset missing: {asset}")
jar_sha = file_sha(os.path.join(assets_dir, jar_name))
if jar_sha != producer_sha:
    fail("EV-ASSET", f"draft jar sha256 {jar_sha} != producer receipt sha256 {producer_sha}")
proc = subprocess.run(["sha256sum", "-c", "SHA256SUMS"], cwd=assets_dir,
                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
if proc.returncode != 0:
    fail("EV-CHECKSUM", f"sha256sum -c SHA256SUMS failed:\n{proc.stdout}")
sha256sums_sha = file_sha(os.path.join(assets_dir, "SHA256SUMS"))

manifest = parse_manifest(os.path.join(assets_dir, "RELEASE-PROVENANCE.txt"))
prov_required = ("repository", "tag", "semver", "commit", "run_id", "run_attempt", "jar", "sha256")
missing_prov = [k for k in prov_required if manifest.get(k) is None]
if missing_prov:
    fail("EV-PROVENANCE", f"RELEASE-PROVENANCE.txt missing keys: {missing_prov}")
prov_expect = {"repository": repo, "tag": tag, "semver": semver, "commit": sha,
               "run_id": origin, "run_attempt": str(run.get("run_attempt")),
               "jar": jar_name, "sha256": producer_sha}
for k, v in prov_expect.items():
    if manifest.get(k) != v:
        fail("EV-PROVENANCE", f"RELEASE-PROVENANCE.txt {k}={manifest.get(k)!r} != expected {v!r}")
provenance_sha = file_sha(os.path.join(assets_dir, "RELEASE-PROVENANCE.txt"))

sbom_sha = file_sha(os.path.join(assets_dir, sbom_name))
release_rec = receipts["release"]
if release_rec.get("sbom") != sbom_name:
    fail("EV-SBOM", f"release receipt sbom {release_rec.get('sbom')!r} != {sbom_name!r}")
if release_rec.get("sbom_sha256") != sbom_sha:
    fail("EV-SBOM", f"release receipt sbom sha256 {release_rec.get('sbom_sha256')!r} != observed {sbom_sha}")
with open(os.path.join(assets_dir, sbom_name), "r", encoding="utf-8") as fh:
    sbom = json.load(fh)
comp = (sbom.get("metadata") or {}).get("component") or {}
if comp.get("type") != "container" or comp.get("name") != f"url-shortener:{semver}":
    fail("EV-SBOM", f"SBOM subject is {comp.get('type')!r}/{comp.get('name')!r}, expected container/url-shortener:{semver}")
image_id = None
for prop in comp.get("properties") or []:
    if prop.get("name") == "aquasecurity:trivy:ImageID":
        image_id = prop.get("value")
if not image_id:
    fail("EV-SBOM", "SBOM does not expose aquasecurity:trivy:ImageID property")
release_image_id = release_rec.get("image_id")
if release_image_id != image_id:
    fail("EV-SBOM", f"release receipt image_id {release_image_id!r} != SBOM subject image id {image_id!r}")
embedded_sha = release_rec.get("embedded_jar_sha256")
if embedded_sha != jar_sha:
    fail("EV-IMAGE", f"release receipt embedded_jar_sha256 {embedded_sha!r} != draft jar sha256 {jar_sha}")

# ---- assemble the evidence facts (single source for RELEASE-EVIDENCE.json)
facts = {
    "schema_version": "1",
    "repository": repo,
    "tag": tag,
    "tag_object_type": "tag",
    "semver": semver,
    "source_commit": sha,
    "workflow": {
        "name": run.get("name"),
        "path": ".github/workflows/release.yml",
        "run_id": int(origin),
        "run_number": run.get("run_number"),
        "run_attempt": run.get("run_attempt"),
        "event": run.get("event"),
        "url": run.get("html_url"),
        "conclusion": run.get("conclusion"),
        "completion_time_utc": completion,
        "completion_time_source": "actions jobs api max(completed_at)",
    },
    "jobs": jobs,
    "candidate": {"filename": jar_name, "sha256": producer_sha},
    "receipts": {
        "producer": producer,
        "consumers": consumers,
        "sha256sums_verified": True,
    },
    "published_assets": [
        {"name": jar_name, "sha256": jar_sha, "role": "jar"},
        {"name": "SHA256SUMS", "sha256": sha256sums_sha, "role": "checksums"},
        {"name": "RELEASE-PROVENANCE.txt", "sha256": provenance_sha, "role": "provenance"},
        {"name": sbom_name, "sha256": sbom_sha, "role": "sbom"},
    ],
    "image": {
        "id": image_id,
        "name": f"url-shortener:{semver}",
        "embedded_jar_sha256": jar_sha,
    },
    "sbom": {
        "filename": sbom_name,
        "sha256": sbom_sha,
        "format": "cyclonedx",
        "subject_name": f"url-shortener:{semver}",
        "subject_image_id": image_id,
    },
}
with open("facts.json", "w", encoding="utf-8") as fh:
    json.dump(facts, fh, indent=2, sort_keys=True)
    fh.write("\n")
print(f"OK: run {origin} verified (5/5 jobs success, completion {completion}), "
      f"jar {jar_sha}, sbom subject {image_id}")
PYEOF

if [ -f ALREADY_FINALIZED ]; then
  echo "SKIP: release $TAG is already finalized with evidence — no changes made."
  exit 0
fi

# --------------------------------------------------------------------------
# Phase F — generate, render, validate the canonical evidence record
SCHEMA="$SCRIPT_DIR/../schemas/release-evidence.schema.json"
python3 "$SCRIPT_DIR/release_evidence.py" generate \
  --schema "$SCHEMA" \
  --facts facts.json \
  --finalizer-run-id "${GITHUB_RUN_ID:-0}" \
  --finalizer-run-attempt "${GITHUB_RUN_ATTEMPT:-1}" \
  --recorded-at-utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --out RELEASE-EVIDENCE.json || fail "EV-GENERATE"
python3 "$SCRIPT_DIR/release_evidence.py" render \
  --json RELEASE-EVIDENCE.json --out RELEASE-EVIDENCE.md || fail "EV-RENDER"
python3 "$SCRIPT_DIR/release_evidence.py" validate \
  --schema "$SCHEMA" \
  --json RELEASE-EVIDENCE.json || fail "EV-VALIDATE"
echo "OK: RELEASE-EVIDENCE.json + RELEASE-EVIDENCE.md generated and validated"

# --------------------------------------------------------------------------
# Phase G — attach evidence to the draft and publish (LAST action)
if [ "$DRY_RUN" = 1 ]; then
  echo "DRY-RUN: all checks passed; would attach RELEASE-EVIDENCE.json/.md to $TAG and publish. Not publishing."
  exit 0
fi

gh release upload "$TAG" RELEASE-EVIDENCE.json RELEASE-EVIDENCE.md \
  || fail "EV-PUBLISH: failed to attach evidence assets to $TAG"
gh release edit "$TAG" --draft=false --notes-file RELEASE-EVIDENCE.md \
  || fail "EV-PUBLISH: failed to publish release $TAG"
echo "OK: release $TAG published with machine-generated evidence."
echo "    Evidence report: RELEASE-EVIDENCE.json (canonical) / RELEASE-EVIDENCE.md (rendered)"
echo "    Originating run: $ORIGIN"
exit 0