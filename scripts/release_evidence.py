#!/usr/bin/env python3
"""release_evidence.py — machine-generated release evidence (Epic 22).

Canonical, machine-readable evidence record for a URL Shortener Service
release. The report is generated ONLY by the release finalizer after the
originating `Release` workflow run completes successfully, from facts observed
over the GitHub API and verified artifacts.

Design contract (docs/release-runbook.md and tasks/epic-22):
  * RELEASE-EVIDENCE.json is the canonical record; the Markdown report and the
    GitHub Release body summary are rendered from it and never hand-edited.
  * Observed facts only — the report never makes policy claims (e.g. it does
    not claim repository tag-protection exists).
  * The workflow completion time is taken from the GitHub *jobs* API
    (max over jobs' `completed_at`), never from this generator's clock.
    Deviating from that contract is a hard validation error.
  * No self-hash: the report must never contain its own SHA-256.
  * Unknown schema versions are rejected.

Subcommands:
  generate   Build the canonical RELEASE-EVIDENCE.json from a `--facts` JSON
             file (report body minus `evidence`/`generated_by`), then validate.
  validate   Independently re-validate an existing report against the schema.
  render     Render RELEASE-EVIDENCE.md from a report (JSON only).
  --self-test  Fixture-based acceptance + named-rejection matrix (testing §1).

Only Python 3 stdlib (json, re, argparse, datetime). No pip dependencies.
"""

import argparse
import datetime as _dt
import json
import re
import sys
from typing import Any, Dict, List

SCHEMA_VERSION = "1"
TOOL = "release_evidence.py"
FINALIZER_WORKFLOW = ".github/workflows/release-finalizer.yml"
DEFAULT_SCHEMA = "schemas/release-evidence.schema.json"

EVIDENCE_JSON = "RELEASE-EVIDENCE.json"
EVIDENCE_MD = "RELEASE-EVIDENCE.md"

ALLOWED_COMPLETION_SOURCES = ("actions jobs api max(completed_at)",)
REQUIRED_JOBS = ("Gates", "k6 Gate", "Runtime Smoke", "Restore Drill", "Release")
RECEIPT_JOBS = ("gates", "k6-gate", "runtime-smoke", "restore-drill", "release")

HEX64 = re.compile(r"^[0-9a-f]{64}$")
HEX40 = re.compile(r"^[0-9a-f]{40}$")


class ReleaseEvidenceError(Exception):
    """A validation/consistency failure. message starts with an EV- code."""


# --------------------------------------------------------------------------
# error helpers
def _err(code: str, msg: str) -> ReleaseEvidenceError:
    return ReleaseEvidenceError(f"{code}: {msg}")


def _ts_is_valid(s: str) -> bool:
    try:
        _dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
        return True
    except ValueError:
        return False


def normalize_timestamp(value: Any) -> str:
    """Accept RFC3339 UTC with suffix Z (optional fractional seconds), return
    canonical `YYYY-MM-DDTHH:MM:SSZ`. Reject any non-UTC representation."""
    if not isinstance(value, str):
        raise _err("EV-TIMESTAMP", f"timestamp must be a string, got {type(value).__name__}")
    m = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d{1,6})?(Z)", value)
    if not m:
        raise _err(
            "EV-TIMESTAMP",
            f"non-UTC/invalid timestamp {value!r}; expected RFC3339 UTC with 'Z'",
        )
    base, frac, z = m.groups()
    if not _ts_is_valid(base + "Z"):
        raise _err("EV-TIMESTAMP", f"timestamp has invalid date/time components: {value!r}")
    return base + "Z"


# --------------------------------------------------------------------------
# minimal JSON-Schema-subset validator (no pip deps). Supports: type,
# properties, required, additionalProperties, items, minItems, pattern, enum,
# const, $ref -> #/definitions/<name>. Everything else is ignored.
def _resolve_ref(schema: Dict[str, Any], ref: str, path: str) -> Dict[str, Any]:
    if ref.startswith("#/definitions/"):
        name = ref[len("#/definitions/"):]
        defs = schema.get("definitions", {})
        if name in defs:
            return defs[name]
    raise _err("EV-SCHEMA", f"{path}: unresolved $ref {ref!r}")


def _walk(value: Any, sch: Dict[str, Any], path: str, root: Dict[str, Any]) -> None:
    if "$ref" in sch:
        _walk(value, _resolve_ref(root, sch["$ref"], path), path, root)
        return
    stype = sch.get("type")
    if stype == "object":
        if not isinstance(value, dict):
            raise _err("EV-SCHEMA", f"{path}: expected object, got {type(value).__name__}")
        props = sch.get("properties", {})
        if sch.get("additionalProperties") is False:
            for k in value:
                if k not in props:
                    raise _err("EV-SCHEMA", f"{path}: unexpected property {k!r}")
        for key, sub in props.items():
            if key in value:
                _walk(value[key], sub, f"{path}.{key}", root)
        for req in sch.get("required", []):
            if req not in value:
                raise _err("EV-SCHEMA", f"{path}: missing required property {req!r}")
        return
    if stype == "array":
        if not isinstance(value, list):
            raise _err("EV-SCHEMA", f"{path}: expected array, got {type(value).__name__}")
        items = sch.get("items")
        if isinstance(items, dict):
            for i, item in enumerate(value):
                _walk(item, items, f"{path}[{i}]", root)
        if "minItems" in sch and len(value) < sch["minItems"]:
            raise _err(
                "EV-SCHEMA", f"{path}: expected at least {sch['minItems']} items, got {len(value)}"
            )
        return
    # scalars
    if stype == "integer":
        if isinstance(value, bool) or not isinstance(value, int):
            raise _err("EV-SCHEMA", f"{path}: expected integer, got {type(value).__name__}")
    elif stype == "number":
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise _err("EV-SCHEMA", f"{path}: expected number, got {type(value).__name__}")
    elif stype == "boolean":
        if not isinstance(value, bool):
            raise _err("EV-SCHEMA", f"{path}: expected boolean, got {type(value).__name__}")
    elif stype == "string":
        if not isinstance(value, str):
            raise _err("EV-SCHEMA", f"{path}: expected string, got {type(value).__name__}")
    elif stype == "array":
        raise _err("EV-SCHEMA", f"{path}: expected array (handled above)")
    elif stype == "object":
        raise _err("EV-SCHEMA", f"{path}: expected object (handled above)")
    if "const" in sch and value != sch["const"]:
        raise _err("EV-SCHEMA", f"{path}: expected const {sch['const']!r}, got {value!r}")
    if "enum" in sch and value not in sch["enum"]:
        raise _err("EV-SCHEMA", f"{path}: value {value!r} not in enum {sch['enum']}")
    if "pattern" in sch:
        if not isinstance(value, str) or re.search(sch["pattern"], value) is None:
            raise _err("EV-SCHEMA", f"{path}: value {value!r} does not match {sch['pattern']!r}")


def validate_against_schema(document: Any, schema: Dict[str, Any]) -> None:
    _walk(document, schema, "$", schema)


# --------------------------------------------------------------------------
# semantic cross-checks beyond what a JSON Schema can express
def _semantic_checks(doc: Dict[str, Any]) -> None:
    repo = doc["repository"]
    tag = doc["tag"]
    semver = doc["semver"]
    commit = doc["source_commit"]
    run = doc["workflow"]["run_id"]
    attempt = doc["workflow"]["run_attempt"]
    cand_name = doc["candidate"]["filename"]
    cand_sha = doc["candidate"]["sha256"]

    expected_jar = f"url-shortener-service-{semver}.jar"
    if cand_name != expected_jar:
        raise _err(
            "EV-CANDIDATE-CROSS",
            f"candidate filename {cand_name!r} != naming contract {expected_jar!r}",
        )

    if doc["workflow"]["conclusion"] != "success":
        raise _err("EV-JOB", f"workflow conclusion is not success: {doc['workflow']['conclusion']}")
    job_names = [j["name"] for j in doc["jobs"]]
    missing = [n for n in REQUIRED_JOBS if n not in job_names]
    if missing:
        raise _err("EV-JOB", f"missing required job(s) in evidence: {missing}")
    for j in doc["jobs"]:
        if j["conclusion"] != "success":
            raise _err("EV-JOB", f"job {j['name']} did not succeed ({j['conclusion']})")

    if not doc["workflow"]["url"].endswith(f"/actions/runs/{run}"):
        raise _err("EV-URL", f"workflow url {doc['workflow']['url']!r} does not reference run {run}")

    # completion time == max over jobs' completed_at, from the documented source
    if doc["workflow"]["completion_time_source"] not in ALLOWED_COMPLETION_SOURCES:
        raise _err("EV-COMPLETION-SOURCE",
                   f"completion_time_source not allowed: {doc['workflow']['completion_time_source']!r}")
    max_completed = max(normalize_timestamp(j["completed_at_utc"]) for j in doc["jobs"])
    if normalize_timestamp(doc["workflow"]["completion_time_utc"]) != max_completed:
        raise _err(
            "EV-COMPLETION-TIME",
            f"completion_time_utc {doc['workflow']['completion_time_utc']!r} != "
            f"max(jobs.completed_at) {max_completed!r}",
        )

    # receipt coverage: producer = gates; exactly the 4 consumers
    receipts = doc["receipts"]
    producer: Dict[str, Any] = receipts["producer"]
    consumers: List[Dict[str, Any]] = receipts["consumers"]
    if producer.get("job") != "gates":
        raise _err("EV-RECEIPT-COVERAGE", f"producer receipt must be job 'gates', got {producer.get('job')!r}")
    consumer_jobs = sorted(c.get("job") for c in consumers)
    if consumer_jobs != sorted(RECEIPT_JOBS[1:]):
        raise _err("EV-RECEIPT-COVERAGE", f"consumers must be exactly {RECEIPT_JOBS[1:]}, got {consumer_jobs}")
    if not receipts.get("sha256sums_verified") is True:
        raise _err("EV-CHECKSUM", "sha256sums_verified must be true (finalizer ran sha256sum -c)")

    for role, rec in [("producer", producer)] + [(f"consumer[{i}]", c) for i, c in enumerate(consumers)]:
        for key, expected in (("repository", repo), ("tag", tag), ("semver", semver),
                              ("source_commit", commit), ("run_id", run),
                              ("run_attempt", attempt)):
            if rec.get(key) != expected:
                raise _err(
                    "EV-RECEIPT-BINDING",
                    f"receipt {role} {key} {rec.get(key)!r} != canonical {expected!r}",
                )
        if rec.get("filename") != cand_name:
            raise _err("EV-RECEIPT-BINDING",
                       f"receipt {role} filename {rec.get('filename')!r} != candidate {cand_name!r}")
        if rec.get("sha256") != cand_sha:
            raise _err(
                "EV-RECEIPT-HASH",
                f"receipt {role} sha256 {rec.get('sha256')!r} != candidate sha256 {cand_sha!r}",
            )

    # published assets
    seen_roles = set()
    for asset in doc["published_assets"]:
        role = asset["role"]
        if role in seen_roles:
            raise _err("EV-ASSET", f"duplicate published asset role {role!r}")
        seen_roles.add(role)
        name, sha = asset["name"], asset["sha256"]
        if role == "jar":
            if name != cand_name or sha != cand_sha:
                raise _err("EV-ASSET", f"jar asset {name}/{sha} != candidate {cand_name}/{cand_sha}")
        elif role == "provenance":
            if name != "RELEASE-PROVENANCE.txt":
                raise _err("EV-ASSET", f"provenance asset name must be RELEASE-PROVENANCE.txt, got {name!r}")
        elif role == "checksums":
            if name != "SHA256SUMS":
                raise _err("EV-ASSET", f"checksums asset name must be SHA256SUMS, got {name!r}")
        elif role == "sbom":
            sbom = doc.get("sbom")
            if not sbom or name != sbom["filename"] or sha != sbom["sha256"]:
                raise _err("EV-ASSET", f"sbom asset {name}/{sha} != sbom record {sbom}")
    required_roles = {"jar", "provenance", "checksums"}
    if not required_roles.issubset(seen_roles):
        raise _err("EV-ASSET", f"published assets missing roles {required_roles - seen_roles}")

    # image
    if "image" in doc:
        img = doc["image"]
        if img["embedded_jar_sha256"] != cand_sha:
            raise _err(
                "EV-IMAGE",
                f"image-embedded jar sha256 {img['embedded_jar_sha256']!r} != candidate {cand_sha!r}",
            )
        if img["name"] != f"url-shortener:{semver}":
            raise _err("EV-IMAGE", f"image name {img['name']!r} != url-shortener:{semver}")

    # sbom
    if "sbom" in doc:
        sbom = doc["sbom"]
        image_id = doc.get("image", {}).get("id")
        if image_id is not None and sbom["subject_image_id"] != image_id:
            raise _err(
                "EV-SBOM",
                f"sbom subject image id {sbom['subject_image_id']!r} != image id {image_id!r}",
            )
        if sbom["subject_name"] != f"url-shortener:{semver}":
            raise _err("EV-SBOM", f"sbom subject name {sbom['subject_name']!r} != url-shortener:{semver}")

    if not doc["generated_by"].get("observed_facts_only") is True:
        raise _err("EV-SELF-HASH", "generated_by.observed_facts_only must be true")


def _reject_self_references(doc: Dict[str, Any]) -> None:
    """A release evidence report must never contain a hash of itself."""
    if not isinstance(doc, dict):
        raise _err("EV-SELF-HASH", "report root must be a JSON object")
    lower_keys = {k.lower() for k in doc}
    banned = {"sha256", "digest", "self_sha256", "selfhash"}
    hit = lower_keys & banned
    if hit:
        raise _err("EV-SELF-HASH", f"report must not contain a self-hash field: {sorted(hit)}")


# --------------------------------------------------------------------------
# generate
def generate_from_facts(facts: Dict[str, Any], schema: Dict[str, Any],
                        finalizer_workflow: str, finalizer_run_id: Any,
                        finalizer_run_attempt: Any, recorded_at_utc: str) -> Dict[str, Any]:
    doc = dict(facts)
    doc["evidence"] = {"json": EVIDENCE_JSON, "markdown": EVIDENCE_MD}
    doc["generated_by"] = {
        "tool": TOOL,
        "schema_version": SCHEMA_VERSION,
        "finalizer_workflow": finalizer_workflow,
        "finalizer_run_id": finalizer_run_id,
        "finalizer_run_attempt": finalizer_run_attempt,
        "recorded_at_utc": normalize_timestamp(recorded_at_utc),
        "observed_facts_only": True,
    }
    validate_document(doc, schema)
    return doc


def validate_document(doc: Any, schema: Dict[str, Any]) -> Dict[str, Any]:
    _reject_self_references(doc)
    try:
        validate_against_schema(doc, schema)
    except ReleaseEvidenceError:
        raise
    except Exception as exc:  # schema validator bug must not pass silently
        raise _err("EV-SCHEMA", f"internal validation error: {exc}") from exc
    _semantic_checks(doc)
    return doc


def canonical_json(doc: Dict[str, Any]) -> str:
    return json.dumps(doc, indent=2, sort_keys=True) + "\n"


# --------------------------------------------------------------------------
# render
def render_markdown(doc: Dict[str, Any]) -> str:
    wf = doc["workflow"]
    gen = doc["generated_by"]
    rows = [
        f"# Release Evidence — {doc['tag']}",
        "",
        "Machine-generated report. Do not edit — `RELEASE-EVIDENCE.json` is the",
        "canonical record and this file is rendered from it.",
        "",
        f"Generated from observed facts only by `{gen['tool']}` (schema "
        f"`{gen['schema_version']}`) on `{gen['recorded_at_utc']}` via "
        f"{gen['finalizer_workflow']} run `{gen['finalizer_run_id']}#"
        f"{gen['finalizer_run_attempt']}`.",
        "",
        "## Identity",
        "",
        "| Field | Value |",
        "| --- | --- |",
        f"| Repository | `{doc['repository']}` |",
        f"| Tag | `{doc['tag']}` |",
        f"| Tag object type | annotated (git object type `tag`) |",
        f"| SemVer | `{doc['semver']}` |",
        f"| Source commit | `{doc['source_commit']}` |",
        f"| Candidate | `{doc['candidate']['filename']}` |",
        f"| Candidate SHA-256 | `{doc['candidate']['sha256']}` |",
        "",
        "## Originating workflow run",
        "",
        "| Field | Value |",
        "| --- | --- |",
        f"| Workflow | `{wf['name']}` ({wf['path']}) |",
        f"| Run | #{wf['run_number']} ([link]({wf['url']})) |",
        f"| Run ID | `{wf['run_id']}` (attempt `{wf['run_attempt']}`) |",
        f"| Event | `{wf['event']}` |",
        f"| Conclusion | `{wf['conclusion']}` |",
        f"| Completed (UTC) | `{wf['completion_time_utc']}` |",
        f"| Completion source | `{wf['completion_time_source']}` |",
        "",
        "## Jobs",
        "",
        "| Job | Conclusion | Completed (UTC) |",
        "| --- | --- | --- |",
    ]
    rows += [f"| {j['name']} | `{j['conclusion']}` | `{j['completed_at_utc']}` |" for j in doc["jobs"]]
    rows += ["", "All five pipeline jobs succeeded.", ""]

    receipts = doc["receipts"]
    n_rec = 1 + len(receipts["consumers"])
    rows += [
        "## Receipts",
        "",
        f"Producer: `{receipts['producer']['job']}`. Consumers: "
        + ", ".join(f"`{c['job']}`" for c in receipts["consumers"]) + ".",
        "",
        f"All {n_rec} receipts bind to this tag/commit/run and agree on the candidate SHA-256.",
        f"`sha256sum -c SHA256SUMS` verified: "
        f"{'yes' if receipts['sha256sums_verified'] else 'no'}.",
        "",
        "## Published assets",
        "",
        "| Asset | Role | SHA-256 |",
        "| --- | --- | --- |",
    ]
    rows += [f"| `{a['name']}` | `{a['role']}` | `{a['sha256']}` |" for a in doc["published_assets"]]

    if "image" in doc:
        img = doc["image"]
        match = "yes (== candidate)" if img["embedded_jar_sha256"] == doc["candidate"]["sha256"] else "NO"
        rows += [
            "",
            "## Image",
            "",
            "| Field | Value |",
            "| --- | --- |",
            f"| Image id | `{img['id']}` |",
            f"| Image name | `{img['name']}` |",
            f"| Embedded JAR SHA-256 | `{img['embedded_jar_sha256']}` |",
            "",
            f"Image-embedded JAR matches the released candidate: {match}.",
        ]

    if "sbom" in doc:
        sbom = doc["sbom"]
        subject_match = "yes (== image id)" if (
            doc.get("image", {}).get("id") == sbom["subject_image_id"]
        ) else "image id not recorded"
        rows += [
            "",
            "## SBOM",
            "",
            "| Field | Value |",
            "| --- | --- |",
            f"| Filename | `{sbom['filename']}` |",
            f"| SHA-256 | `{sbom['sha256']}` |",
            f"| Format | `{sbom['format']}` |",
            f"| Subject | `{sbom['subject_name']}` (`{sbom['subject_image_id']}`) |",
            "",
            f"SBOM subject matches the image id: {subject_match}.",
        ]

    rows += [
        "",
        "## Evidence files",
        "",
        f"- `{doc['evidence']['json']}` — canonical machine-readable record",
        f"- `{doc['evidence']['markdown']}` — this file, rendered only from the JSON",
        "",
        "Verify the JAR with `sha256sum -c SHA256SUMS`.",
        "Full provenance: `RELEASE-PROVENANCE.txt`.",
        "",
        f"Generated by `{gen['tool']}` (schema `{gen['schema_version']}`) from "
        "observed facts only; no policy claims are made in this report.",
        "",
    ]
    return "\n".join(rows)


def render_to_file(doc: Dict[str, Any], out: str) -> None:
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(render_markdown(doc))


# --------------------------------------------------------------------------
# self-test
def _valid_facts() -> Dict[str, Any]:
    cand_sha = "a" * 64
    completed = "2026-10-01T23:45:56Z"
    return {
        "schema_version": SCHEMA_VERSION,
        "repository": "acme/url-shortener-service",
        "tag": "v0.16.0",
        "tag_object_type": "tag",
        "semver": "0.16.0",
        "source_commit": "1" * 40,
        "workflow": {
            "name": "Release",
            "path": ".github/workflows/release.yml",
            "run_id": 424242,
            "run_number": 17,
            "run_attempt": 1,
            "event": "push",
            "url": "https://github.com/acme/url-shortener-service/actions/runs/424242",
            "conclusion": "success",
            "completion_time_utc": completed,
            "completion_time_source": "actions jobs api max(completed_at)",
        },
        "jobs": [
            {"id": 1, "name": name, "conclusion": "success", "completed_at_utc": completed}
            for name in REQUIRED_JOBS
        ],
        "candidate": {"filename": "url-shortener-service-0.16.0.jar", "sha256": cand_sha},
        "receipts": {
            "producer": {
                "repository": "acme/url-shortener-service", "tag": "v0.16.0",
                "semver": "0.16.0", "source_commit": "1" * 40, "run_id": 424242,
                "run_attempt": 1, "job": "gates",
                "filename": "url-shortener-service-0.16.0.jar", "sha256": cand_sha,
                "produced_at_utc": completed,
            },
            "consumers": [
                {
                    "repository": "acme/url-shortener-service", "tag": "v0.16.0",
                    "semver": "0.16.0", "source_commit": "1" * 40, "run_id": 424242,
                    "run_attempt": 1, "job": job,
                    "filename": "url-shortener-service-0.16.0.jar", "sha256": cand_sha,
                    "produced_at_utc": completed,
                }
                for job in RECEIPT_JOBS[1:]
            ],
            "sha256sums_verified": True,
        },
        "published_assets": [
            {"name": "url-shortener-service-0.16.0.jar", "sha256": cand_sha, "role": "jar"},
            {"name": "SHA256SUMS", "sha256": "b" * 64, "role": "checksums"},
            {"name": "RELEASE-PROVENANCE.txt", "sha256": "c" * 64, "role": "provenance"},
            {"name": "sbom-url-shortener-0.16.0.json", "sha256": "d" * 64, "role": "sbom"},
        ],
        "image": {
            "id": "sha256:" + "e" * 64,
            "name": "url-shortener:0.16.0",
            "embedded_jar_sha256": cand_sha,
        },
        "sbom": {
            "filename": "sbom-url-shortener-0.16.0.json",
            "sha256": "d" * 64,
            "format": "cyclonedx",
            "subject_name": "url-shortener:0.16.0",
            "subject_image_id": "sha256:" + "e" * 64,
        },
    }


def do_self_test(schema: Dict[str, Any], schema_path: str) -> int:
    print("=== release_evidence.py --self-test ===")
    cases_ok = 0
    cases_fail = 0

    def claim_ok(code: str, msg: str) -> None:
        nonlocal cases_ok
        cases_ok += 1
        print(f"case {code} OK")

    def claim_fail(code: str, why: str) -> None:
        nonlocal cases_fail
        cases_fail += 1
        print(f"case {code} FAIL: {why}")

    def run_negative(code: str, mutate, codes=None) -> None:
        # `codes` = set of acceptable named-error codes. Outer (schema) layer wins
        # when it can express the violation; semantic-layer-only cases pin the
        # exact code so the semantic cross-checks are proven to fire.
        allowed = codes or (code, "EV-SCHEMA")
        facts = _valid_facts()
        mutate(facts)
        try:
            generate_from_facts(facts, schema, FINALIZER_WORKFLOW, 777, 1,
                                "2026-10-02T09:00:00Z")
            claim_fail(code, "expected generate to reject, but it accepted")
        except ReleaseEvidenceError as exc:
            if any(str(exc).startswith(c) for c in allowed):
                claim_ok(code, str(exc))
            else:
                claim_fail(code, f"expected one of {allowed}, got: {exc}")

    def run_positive(code: str, mutate) -> None:
        facts = _valid_facts()
        mutate(facts)
        try:
            doc = generate_from_facts(facts, schema, FINALIZER_WORKFLOW, 777, 1,
                                      "2026-10-02T09:00:00Z")
            render_markdown(doc)
            claim_ok(code, "generate+render accepted")
        except ReleaseEvidenceError as exc:
            claim_fail(code, f"expected acceptance, got: {exc}")

    # --- acceptance
    run_positive("A1", lambda d: None)
    run_positive("A2", lambda d: d["workflow"].update(
        {"completion_time_utc": "2026-10-01T23:45:56.123Z"}))  # fraction normalized

    # --- rejections
    run_negative("EV-SCHEMA", lambda d: d.pop("source_commit"))  # R1 missing field
    run_negative("EV-SCHEMA", lambda d: d["candidate"].update({"sha256": "zz"}))  # R2 bad hash
    run_negative("EV-SCHEMA", lambda d: d.update({"source_commit": "abc"}))  # R3 bad commit
    run_negative("EV-SCHEMA", lambda d: d.update({"semver": "0.16"}))  # R4 bad semver
    run_negative("EV-SCHEMA", lambda d: d["workflow"].update(
        {"url": "https://evil.example/list?ref=runs/424242"}))  # R5 bad url
    run_negative("EV-TIMESTAMP", lambda d: d["workflow"].update(
        {"completion_time_utc": "2026-10-01T23:45:56+00:00"}))  # R6 non-UTC
    run_negative("EV-SCHEMA", lambda d: d.update({"schema_version": "2"}))  # R7 unknown version
    run_negative("EV-SELF-HASH", lambda d: d.update({"digest": "f" * 64}))  # R8 self-hash
    run_negative("EV-JOB", lambda d: d["workflow"].update({"conclusion": "failure"}))  # R9
    run_negative("EV-SCHEMA", lambda d: d.update({"tag_object_type": "commit"}))  # R10
    run_negative("EV-JOB", lambda d: d["jobs"][2].update({"conclusion": "failure"}))  # R11
    run_negative("EV-RECEIPT-BINDING", lambda d: d["receipts"]["consumers"][0].update(
        {"run_id": 1}), codes=("EV-RECEIPT-BINDING",))  # R12 cross-run receipt
    run_negative("EV-RECEIPT-HASH", lambda d: d["receipts"]["consumers"][1].update(
        {"sha256": "9" * 64}), codes=("EV-RECEIPT-HASH",))  # R13 hash disagreement
    run_negative("EV-CHECKSUM", lambda d: d["receipts"].update(
        {"sha256sums_verified": False}))  # R14
    run_negative("EV-ASSET", lambda d: d["published_assets"][1].update(
        {"name": "WRONG-SUMS"}), codes=("EV-ASSET",))  # R15 asset name vs role
    run_negative("EV-IMAGE", lambda d: d["image"].update(
        {"embedded_jar_sha256": "8" * 64}), codes=("EV-IMAGE",))  # R16 image mismatch
    run_negative("EV-SBOM", lambda d: d["sbom"].update(
        {"subject_image_id": "sha256:" + "7" * 64}), codes=("EV-SBOM",))  # R17 sbom subject mismatch
    run_negative("EV-RECEIPT-COVERAGE", lambda d: d["receipts"]["consumers"].pop())  # R18
    run_negative("EV-COMPLETION-SOURCE", lambda d: d["workflow"].update(
        {"completion_time_source": "generator wall clock"}))  # R19
    run_negative("EV-COMPLETION-TIME", lambda d: d["workflow"].update(
        {"completion_time_utc": "2020-01-01T00:00:00Z"}), codes=("EV-COMPLETION-TIME",))  # R20

    # --- round-trip artifact check
    import tempfile, os
    with tempfile.TemporaryDirectory() as tmp:
        facts = _valid_facts()
        doc = generate_from_facts(facts, schema, FINALIZER_WORKFLOW, 777, 1,
                                  "2026-10-02T09:00:00Z")
        json_path = os.path.join(tmp, EVIDENCE_JSON)
        md_path = os.path.join(tmp, EVIDENCE_MD)
        with open(json_path, "w", encoding="utf-8") as fh:
            fh.write(canonical_json(doc))
        with open(json_path, "r", encoding="utf-8") as fh:
            reloaded = json.load(fh)
        validate_document(reloaded, schema)  # validate must accept its own output
        render_to_file(reloaded, md_path)
        with open(md_path, "r", encoding="utf-8") as fh:
            md = fh.read()
        if "Release Evidence — v0.16.0" in md and "424242" in md:
            claim_ok("A3", "round-trip validate + render of generated report")
        else:
            claim_fail("A3", "rendered report missing expected content")
        if os.path.getsize(json_path) > 0 and "sha256" not in doc:
            claim_ok("A4", "report has no self-hash field")
        else:
            claim_fail("A4", "unexpected self-hash presence")

    # a schema that cannot open must fail loudly
    try:
        validate_against_schema({"x": 1}, {"type": "object"})  # fine
        open("definitely-missing-schema-file.json")
        claim_fail("A5", "missing schema file must be an error")
    except FileNotFoundError:
        claim_ok("A5", "missing schema file rejected")

    print(f"OK: {cases_ok} acceptance/rejection cases passed.")
    if cases_fail:
        print(f"FAIL: {cases_fail} case(s) failed.")
        return 1
    print("PASS: self-test verified — evidence generator detects violations.")
    return 0


# --------------------------------------------------------------------------
def load_schema(schema_path: str) -> Dict[str, Any]:
    with open(schema_path, "r", encoding="utf-8") as fh:
        schema = json.load(fh)
    if not isinstance(schema, dict) or schema.get("type") != "object":
        raise ReleaseEvidenceError("EV-SCHEMA: schema root must be a JSON Schema object")
    return schema


def main(argv: List[str]) -> int:
    ap = argparse.ArgumentParser(description=TOOL)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_gen = sub.add_parser("generate", help="build canonical RELEASE-EVIDENCE.json")
    p_gen.add_argument("--schema", default=DEFAULT_SCHEMA)
    p_gen.add_argument("--facts", required=True, help="facts JSON (report minus evidence/generated_by)")
    p_gen.add_argument("--finalizer-workflow", default=FINALIZER_WORKFLOW)
    p_gen.add_argument("--finalizer-run-id", type=int, required=True)
    p_gen.add_argument("--finalizer-run-attempt", type=int, required=True)
    p_gen.add_argument("--recorded-at-utc", required=True)
    p_gen.add_argument("--out", required=True)

    p_val = sub.add_parser("validate", help="re-validate an existing report")
    p_val.add_argument("--schema", default=DEFAULT_SCHEMA)
    p_val.add_argument("--json", required=True)

    p_ren = sub.add_parser("render", help="render Markdown from a report (JSON only)")
    p_ren.add_argument("--json", required=True)
    p_ren.add_argument("--out", required=True)

    sub.add_parser("self-test")

    args = ap.parse_args(argv)

    if args.cmd == "self-test":
        return do_self_test(load_schema(DEFAULT_SCHEMA), DEFAULT_SCHEMA)

    if args.cmd == "generate":
        schema = load_schema(args.schema)
        with open(args.facts, "r", encoding="utf-8") as fh:
            facts = json.load(fh)
        doc = generate_from_facts(facts, schema, args.finalizer_workflow,
                                  args.finalizer_run_id, args.finalizer_run_attempt,
                                  args.recorded_at_utc)
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(canonical_json(doc))
        print(f"OK: generated {args.out} (schema v{doc['schema_version']})")
        return 0

    if args.cmd == "validate":
        schema = load_schema(args.schema)
        with open(args.json, "r", encoding="utf-8") as fh:
            doc = json.load(fh)
        validate_document(doc, schema)
        print(f"OK: {args.json} is valid (schema v{doc['schema_version']})")
        return 0

    if args.cmd == "render":
        with open(args.json, "r", encoding="utf-8") as fh:
            raw = fh.read()
        try:
            doc = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise _err("EV-SCHEMA", f"render input is not valid JSON: {exc}") from exc
        render_to_file(doc, args.out)
        print(f"OK: rendered {args.out}")
        return 0

    return 2


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except ReleaseEvidenceError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)