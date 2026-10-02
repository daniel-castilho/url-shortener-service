#!/usr/bin/env python3
"""finalize-release-evidence-self-test.py — fixture harness for the finalizer.

Builds an internally-consistent release fixture (originating run, jobs,
receipts, draft release + assets: jar, provenance, sha256sums, CycloneDX SBOM),
then drives `finalize-release-evidence.sh --fixture --dry-run` across the
acceptance path and the Epic 22 testing matrix section 3 security/reliability
cases. Every case is isolated in its own temp dir; nothing touches GitHub or
git in fixture mode.

Run via: bash scripts/finalize-release-evidence.sh --self-test
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
FINALIZER = os.path.join(SCRIPT_DIR, "finalize-release-evidence.sh")
ORIGIN = "31337"
TAG = "v0.16.0"
SEMVER = "0.16.0"
COMMIT = "c" * 40
IMAGE_ID = "sha256:" + "d" * 64
JAR_NAME = "url-shortener-service-0.16.0.jar"
SBOM_NAME = "sbom-url-shortener-0.16.0.json"
REPO = "acme/url-shortener-service"

HEX64 = "0123456789abcdef" * 4
JAR_BYTES = bytes(range(256)) * 16


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write(path: str, content: str) -> None:
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(content)


def build_receipt(job: str, run_id: str = ORIGIN) -> dict:
    rec = {
        "repository": REPO,
        "tag": TAG,
        "semver": SEMVER,
        "source_commit": COMMIT,
        "run_id": int(run_id),
        "run_attempt": 1,
        "job": job,
        "filename": JAR_NAME,
        "sha256": sha256_bytes(JAR_BYTES),
        "produced_at_utc": "2026-10-01T23:45:56Z",
    }
    if job == "release":
        rec.update({
            "image_id": IMAGE_ID,
            "embedded_jar_sha256": sha256_bytes(JAR_BYTES),
            "sbom": SBOM_NAME,
            "sbom_sha256": "e" * 64,
        })
    return rec


def build_fixture(root: str) -> dict:
    """Populate `root` with the valid fixture; return info for mutations."""
    events = {
        "CURRENT_REPO": REPO,
        "EVT_WORKFLOW_NAME": "Release",
        "EVT_WORKFLOW_RUN_ID": ORIGIN,
        "EVT_ORIGIN_REPO": REPO,
        "EVT_ORIGIN_EVENT": "push",
        "EVT_ORIGIN_BRANCH": TAG,
        "EVT_ORIGIN_STATUS": "completed",
        "EVT_ORIGIN_CONCLUSION": "success",
        "EVT_ORIGIN_SHA": COMMIT,
    }
    write(os.path.join(root, "events.env"),
          "".join(f"{k}={v}\n" for k, v in events.items()))

    run = {
        "id": int(ORIGIN),
        "name": "Release",
        "event": "push",
        "head_sha": COMMIT,
        "head_branch": TAG,
        "status": "completed",
        "conclusion": "success",
        "run_number": 30,
        "run_attempt": 1,
        "workflow_id": 75,
        "html_url": f"https://github.com/{REPO}/actions/runs/{ORIGIN}",
        "repository": {"full_name": REPO},
    }
    write(os.path.join(root, "run.json"), json.dumps(run, indent=2))

    completed = "2026-10-01T23:45:56Z"
    jobs = {"total_count": 5, "jobs": [
        {"id": 1000 + i, "name": name, "status": "completed",
         "conclusion": "success", "completed_at": completed}
        for i, name in enumerate(("Gates", "k6 Gate", "Runtime Smoke",
                                  "Restore Drill", "Release"))
    ]}
    write(os.path.join(root, "jobs.json"), json.dumps(jobs, indent=2))

    draft = {
        "tagName": TAG,
        "isDraft": True,
        "assets": [{"name": JAR_NAME}, {"name": "SHA256SUMS"},
                   {"name": "RELEASE-PROVENANCE.txt"}, {"name": SBOM_NAME}],
    }
    write(os.path.join(root, "draft.json"), json.dumps(draft, indent=2))

    recs = os.path.join(root, "receipts")
    os.makedirs(recs)
    for job in ("gates", "k6-gate", "runtime-smoke", "restore-drill", "release"):
        write(os.path.join(recs, f"receipt-{job}.json"),
              json.dumps(build_receipt(job), indent=2, sort_keys=True))

    assets = os.path.join(root, "draft-assets")
    os.makedirs(assets)
    with open(os.path.join(assets, JAR_NAME), "wb") as fh:
        fh.write(JAR_BYTES)
    cand = sha256_bytes(JAR_BYTES)
    write(os.path.join(assets, "SHA256SUMS"), f"{cand}  {JAR_NAME}\n")
    provenance = (
        f"repository={REPO}\n"
        f"tag={TAG}\n"
        f"semver={SEMVER}\n"
        f"commit={COMMIT}\n"
        f"run_id={ORIGIN}\n"
        f"run_attempt=1\n"
        f"jar={JAR_NAME}\n"
        f"sha256={cand}\n"
    )
    write(os.path.join(assets, "RELEASE-PROVENANCE.txt"), provenance)
    sbom = {
        "bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1,
        "serialNumber": "urn:uuid:11111111-1111-4111-8111-111111111111",
        "metadata": {"component": {
            "type": "container",
            "name": f"url-shortener:{SEMVER}",
            "properties": [{"name": "aquasecurity:trivy:ImageID", "value": IMAGE_ID}],
        }},
    }
    sbom_text = json.dumps(sbom, indent=2)
    sbom_sha = sha256_bytes(sbom_text.encode())
    write(os.path.join(assets, SBOM_NAME), sbom_text)
    # release receipt must carry the observed sbom hash
    rel = build_receipt("release")
    rel["sbom_sha256"] = sbom_sha
    rel["image_id"] = IMAGE_ID
    rel["embedded_jar_sha256"] = cand
    write(os.path.join(recs, "receipt-release.json"),
          json.dumps(rel, indent=2, sort_keys=True))


def run_finalizer(fixture_dir: str) -> tuple:
    cwd = tempfile.mkdtemp(prefix="finalize-evidence-")
    cmd = ["bash", FINALIZER, "--fixture", fixture_dir, "--origin-run-id", ORIGIN]
    env = dict(PATH=os.environ.get("PATH", "/usr/bin:/bin"),
               HOME=tempfile.gettempdir())
    proc = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)
    return proc, cwd


def main() -> int:
    print("=== finalize-release-evidence.sh --self-test (fixture harness) ===")
    cases = 0
    failures = []

    def check(name, cond, detail):
        nonlocal cases
        cases += 1
        if cond:
            print(f"case {name} OK")
        else:
            print(f"case {name} FAIL: {detail}")
            failures.append(name)

    def expect(name, pair, rc, text_in, not_in=None):
        proc, _cwd = pair
        out = (proc.stdout or "") + (proc.stderr or "")
        check(name, proc.returncode == rc and text_in in out,
              f"rc={proc.returncode} (want {rc}); text={text_in!r} in output; "
              f"output={out[:2000]}")
        if not_in:
            check(f"{name}-n", not_in not in out, f"{not_in!r} must not appear in output")

    # P1 — valid fixture finalizes (dry-run): generates + validates, no publish
    root = tempfile.mkdtemp(prefix="fx-valid-")
    build_fixture(root)
    pair = run_finalizer(root)
    expect("P1", pair, 0, "DRY-RUN")
    proc, cwd = pair
    evidence = os.path.join(cwd, "RELEASE-EVIDENCE.json")
    check("P1b", os.path.isfile(evidence), f"{evidence} not generated")
    if os.path.isfile(evidence):
        val = subprocess.run(
            ["python3", os.path.join(SCRIPT_DIR, "release_evidence.py"), "validate",
             "--json", evidence],
            capture_output=True, text=True)
        check("P1c", val.returncode == 0, f"standalone validate failed: {val.stderr[:500]}")

    # T1 — unrelated workflow -> SKIP (rc 0)
    root = tempfile.mkdtemp(prefix="fx-t1-"); build_fixture(root)
    ev = os.path.join(root, "events.env")
    data = open(ev).read().replace("EVT_WORKFLOW_NAME=Release", "EVT_WORKFLOW_NAME=CI")
    write(ev, data)
    expect("T1", run_finalizer(root), 0, "SKIP")

    # T2 — non-push event rejected
    root = tempfile.mkdtemp(prefix="fx-t2-"); build_fixture(root)
    ev = os.path.join(root, "events.env")
    write(ev, open(ev).read().replace("EVT_ORIGIN_EVENT=push", "EVT_ORIGIN_EVENT=merge_group"))
    expect("T2", run_finalizer(root), 1, "EV-TRIGGER")

    # T3 — in-progress run rejected
    root = tempfile.mkdtemp(prefix="fx-t3-"); build_fixture(root)
    ev = os.path.join(root, "events.env")
    write(ev, open(ev).read().replace("EVT_ORIGIN_STATUS=completed", "EVT_ORIGIN_STATUS=in_progress"))
    expect("T3", run_finalizer(root), 1, "EV-TRIGGER")

    # T4 — failed run rejected
    root = tempfile.mkdtemp(prefix="fx-t4-"); build_fixture(root)
    ev = os.path.join(root, "events.env")
    write(ev, open(ev).read().replace("EVT_ORIGIN_CONCLUSION=success", "EVT_ORIGIN_CONCLUSION=failure"))
    expect("T4", run_finalizer(root), 1, "EV-TRIGGER")

    # T5 — non-semver branch/tag rejected
    root = tempfile.mkdtemp(prefix="fx-t5-"); build_fixture(root)
    ev = os.path.join(root, "events.env")
    write(ev, open(ev).read().replace("EVT_ORIGIN_BRANCH=v0.16.0", "EVT_ORIGIN_BRANCH=v16.0"))
    expect("T5", run_finalizer(root), 1, "EV-TRIGGER")

    # T6 — origin id mismatch between CLI and event rejected
    root = tempfile.mkdtemp(prefix="fx-t6-"); build_fixture(root)
    ev = os.path.join(root, "events.env")
    write(ev, open(ev).read().replace("EVT_WORKFLOW_RUN_ID=31337", "EVT_WORKFLOW_RUN_ID=424242"))
    expect("T6", run_finalizer(root), 1, "EV-TRIGGER")

    # T7 — cross-run receipt rejected (named binding failure)
    root = tempfile.mkdtemp(prefix="fx-t7-"); build_fixture(root)
    rec = os.path.join(root, "receipts", "receipt-k6-gate.json")
    r = json.load(open(rec)); r["run_id"] = 1
    write(rec, json.dumps(r))
    expect("T7", run_finalizer(root), 1, "EV-RECEIPT-BINDING")

    # T8 — non-draft release (no evidence) rejected
    root = tempfile.mkdtemp(prefix="fx-t8-"); build_fixture(root)
    draft = os.path.join(root, "draft.json")
    d = json.load(open(draft)); d["isDraft"] = False
    write(draft, json.dumps(d))
    expect("T8", run_finalizer(root), 1, "EV-DRAFT")

    # T9 — already finalized (published with evidence) -> idempotent skip
    root = tempfile.mkdtemp(prefix="fx-t9-"); build_fixture(root)
    draft = os.path.join(root, "draft.json")
    d = json.load(open(draft)); d["isDraft"] = False
    d["assets"].append({"name": "RELEASE-EVIDENCE.json"})
    write(draft, json.dumps(d))
    expect("T9", run_finalizer(root), 0, "already finalized")

    # T10 — corrupted receipt JSON rejected
    root = tempfile.mkdtemp(prefix="fx-t10-"); build_fixture(root)
    write(os.path.join(root, "receipts", "receipt-release.json"), "{ not json")
    expect("T10", run_finalizer(root), 1, "EV-RECEIPT")

    # T11 — missing receipt rejected
    root = tempfile.mkdtemp(prefix="fx-t11-"); build_fixture(root)
    os.remove(os.path.join(root, "receipts", "receipt-restore-drill.json"))
    expect("T11", run_finalizer(root), 1, "EV-RECEIPT")

    # T12 — tampered jar rejected (draft jar != producer hash)
    root = tempfile.mkdtemp(prefix="fx-t12-"); build_fixture(root)
    jar = os.path.join(root, "draft-assets", JAR_NAME)
    with open(jar, "r+b") as fh:
        fh.seek(100); fh.write(b"X")
    proc = run_finalizer(root)
    expect("T12", proc, 1, "EV-ASSET")

    # T13 — tampered SHA256SUMS rejected
    root = tempfile.mkdtemp(prefix="fx-t13-"); build_fixture(root)
    write(os.path.join(root, "draft-assets", "SHA256SUMS"),
          f"{'f' * 64}  {JAR_NAME}\n")
    expect("T13", run_finalizer(root), 1, "EV-CHECKSUM")

    # T14 — provenance mismatch rejected
    root = tempfile.mkdtemp(prefix="fx-t14-"); build_fixture(root)
    prov = os.path.join(root, "draft-assets", "RELEASE-PROVENANCE.txt")
    write(prov, open(prov).read().replace("run_id=31337", "run_id=1"))
    expect("T14", run_finalizer(root), 1, "EV-PROVENANCE")

    # T15 — SBOM subject name mismatch rejected
    root = tempfile.mkdtemp(prefix="fx-t15-"); build_fixture(root)
    sbom = os.path.join(root, "draft-assets", SBOM_NAME)
    s = json.load(open(sbom)); s["metadata"]["component"]["name"] = "url-shortener:9.9.9"
    write(sbom, json.dumps(s))
    expect("T15", run_finalizer(root), 1, "EV-SBOM")

    # T16 — SBOM without ImageID property rejected
    root = tempfile.mkdtemp(prefix="fx-t16-"); build_fixture(root)
    sbom = os.path.join(root, "draft-assets", SBOM_NAME)
    s = json.load(open(sbom)); s["metadata"]["component"]["properties"] = []
    write(sbom, json.dumps(s))
    expect("T16", run_finalizer(root), 1, "EV-SBOM")

    # T17 — run head_sha mismatch with event sha rejected (API re-verification)
    root = tempfile.mkdtemp(prefix="fx-t17-"); build_fixture(root)
    run = os.path.join(root, "run.json")
    r = json.load(open(run)); r["head_sha"] = "e" * 40
    write(run, json.dumps(r))
    expect("T17", run_finalizer(root), 1, "EV-API")

    # T18 — draft missing an asset in its asset list rejected
    root = tempfile.mkdtemp(prefix="fx-t18-"); build_fixture(root)
    draft = os.path.join(root, "draft.json")
    d = json.load(open(draft)); d["assets"] = [a for a in d["assets"] if a["name"] != SBOM_NAME]
    write(draft, json.dumps(d))
    expect("T18", run_finalizer(root), 1, "EV-DRAFT")

    # T19 — release receipt declares wrong sbom sha -> EV-SBOM
    root = tempfile.mkdtemp(prefix="fx-t19-"); build_fixture(root)
    rec = os.path.join(root, "receipts", "receipt-release.json")
    r = json.load(open(rec)); r["sbom_sha256"] = "f" * 64
    write(rec, json.dumps(r))
    expect("T19", run_finalizer(root), 1, "EV-SBOM")

    # T20 — lightweight tag protection is the verify-release-artifact gate
    # (covered by verify-release-artifact.sh --self-test cases 12-14); the
    # finalizer calls it in live mode before touching the API.

    if failures:
        print(f"FAIL: {len(failures)} case(s) failed: {failures}")
        return 1
    # Validate the generated report with the standalone gate for P1 too
    print(f"OK: {cases} acceptance/rejection cases passed.")
    print("PASS: finalizer self-test verified — evidence flow rejects all planted violations.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.CalledProcessError as exc:
        print(f"FAIL: harness error: {exc}", file=sys.stderr)
        sys.exit(1)