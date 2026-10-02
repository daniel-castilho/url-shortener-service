# Epic 22 — Definition of Done

**Epic:** Machine-Generated Release Evidence  
**Repository:** `url-shortener-service` only  
**Rule zero — zero-from-memory:** every number, hash, run identifier, timestamp, job result, and test count in this DoD must be generated from or pasted from authoritative output included here. Do not fill placeholders from recollection.

**Status:** Not started. This is an acceptance record, not evidence that implementation or a release has completed.

## 1. Evidence contract

- [ ] `RELEASE-EVIDENCE.json` schema is versioned and checked into the repository.
- [ ] `RELEASE-EVIDENCE.md` and any Release summary are rendered from the JSON; neither is independently hand-edited.
- [ ] The report records repository, tag, semantic version, tag object type/current target, full source commit, originating workflow/run/attempt/number/URL, required job conclusions, candidate and consumer SHA-256 values, release asset hashes, image identity, image-embedded JAR hash, SBOM identity, and authoritative final run conclusion/completion time.
- [ ] Final workflow conclusion/completion time comes from GitHub data after the originating run is complete. No guessed or in-progress time is recorded as completion.
- [ ] The report does not claim to prove historical tag immutability and does not contain its own digest.

## 2. Artifact and run identity

- [ ] Every required candidate consumer produces a receipt tied to the same repository, tag, source commit, run ID, run attempt, filename, and candidate SHA-256.
- [ ] The finalizer rejects missing, duplicate, cross-run, stale, malformed, or contradictory receipts.
- [ ] The published JAR downloaded from the matching draft Release is byte-for-byte equal to the candidate and passes `sha256sum -c` against the published checksum asset.
- [ ] The image-embedded JAR hash equals the candidate hash, if an image is produced.
- [ ] The SBOM's subject identifies the released image and its declared source/revision metadata is consistent with the report.
- [ ] The current release tag is annotated and resolves to the originating commit. This is a current-ref check only.
- [ ] Any mismatch, failed/skipped required job, missing artifact, or unavailable authoritative value prevents publication.

## 3. Finalization and release publication

- [ ] The normal release workflow creates only a draft release until post-run evidence finalization succeeds.
- [ ] The finalizer accepts only the expected completed, successful release workflow in the same repository and validates its originating run ID/attempt.
- [ ] The finalizer obtains run conclusion and completion metadata from authoritative GitHub data; it does not substitute its own run context or timestamp.
- [ ] `RELEASE-EVIDENCE.json` and its generated human-readable report are attached to the matching Release.
- [ ] The Release becomes public only after the finalizer validates the report and actual draft assets.
- [ ] Any finalizer/API/asset failure leaves the Release unpublished and does not move, delete, or recreate the tag or draft.
- [ ] Workflow permissions are least-privilege; no write-capable credential is exposed to untrusted code.

## 4. Documentation and policy

- [ ] `docs/release-runbook.md` documents the implemented draft → finalizer → publish flow, report fields, verification commands, and failure behavior.
- [ ] Epic/DoD instructions cite the generated report and originating workflow; they no longer require manual duplication of release-specific hashes/timestamps/image IDs.
- [ ] `CHANGELOG.md` and every directly affected document are synchronized per `AGENTS.md`.
- [ ] All repository content for this epic is in English and specific to `url-shortener-service`.
- [ ] Epic 21's owner disposition remains intact. No historical waiver is rewritten as a passed criterion; `v0.15.0` and its assets remain unchanged.
- [ ] The documentation says tag-update/deletion protection must be verified through repository controls and does not claim that this epic or its verifier proves historical immutability.
- [ ] No frontend repository change, production deployment, or unrelated production-readiness work is included.

## 5. Required closing evidence

Paste raw outputs or stable links. Replace placeholders only with observed results.

| Evidence | Value |
|---|---|
| Starting implementation commit | **To be filled from Git** |
| Closing implementation commit | **To be filled from Git** |
| Changed paths | **To be filled from `git diff --name-only`** |
| Evidence schema validation | **Command/output to be pasted** |
| Generator/self-test result | **Command/output to be pasted, including negative cases** |
| Receipt/cross-artifact test result | **Command/output to be pasted, including planted mismatch cases** |
| Finalizer security/dry-run result | **Command/output to be pasted** |
| Originating release workflow run and conclusion | **Exact GitHub run URL/ID/attempt to be filled from API** |
| Finalizer workflow run and conclusion | **Exact GitHub run URL/ID/attempt to be filled from API** |
| Generated release-evidence assets | **Names and downloaded verification output** |
| Candidate/consumer/Release JAR SHA-256 comparison | **Generated report and raw verification output** |
| Image-embedded JAR and SBOM subject comparison | **Generated report and raw verification output, if applicable** |
| Full regression gates | **Actual commands and outputs to be pasted** |
| Documentation review | **Changed paths and review commit** |
| Future-tag repository controls | **Separate owner-verified evidence or explicitly pending; never infer from workflow code** |

A placeholder, expected value, or static workflow inspection is not completion evidence.

## 7. Traceability (requirement → implementation/gate → test → generated evidence)

| Requirement | Implementation / gate | Test | Generated evidence produced |
|---|---|---|---|
| Canonical evidence record & versioned schema (rel. 22.1) | `schemas/release-evidence.schema.json`; generator `scripts/release_evidence.py` (generate/validate/render) | `python3 scripts/release_evidence.py self-test` (25 cases incl. negative) | `RELEASE-EVIDENCE.json` validates against the schema at generate time and standalone (`validate --json`) |
| Observed-facts, UTC `Z`, no self-hash, authoritative completion (rel. 22.1) | `release_evidence.py` + finalizer facts assembly (`completion_time_source = actions jobs api max(completed_at)`, finalizer-run id never reused as run identity) | `python3 scripts/release_evidence.py self-test`; finalizer `--self-test` P1/P1c | `workflow.completion_time_utc`, `generated_by.finalizer_run_id/attempt` in `RELEASE-EVIDENCE.json` |
| Same-run candidate receipts, no latest-run lookup (rel. 22.2) | `scripts/write-release-receipt.sh`; `release.yml` emit/upload in all 5 jobs (90-day retention) | `bash scripts/write-release-receipt.sh --self-test` (5 cases) | `receipts.producer`/`receipts.consumers` bound to one `run_id` in the report |
| Cross-artifact identity: jar, sha256sums, provenance, SBOM subject, embedded JAR (rel. 22.2) | finalizer python pipeline (EV-ASSET/EV-CHECKSUM/EV-PROVENANCE/EV-SBOM/EV-IMAGE) | finalizer `--self-test` T12–T16, T19 | `published_assets` hashes, `image.embedded_jar_sha256`, `sbom.subject_*` in the report |
| Annotated tag peels to originating commit (rel. 22.2/22.3) | `scripts/verify-release-artifact.sh --tag-resolves` | `verify-release-artifact.sh --self-test` cases 12–14 | `tag_object_type: "tag"` + resolved `source_commit` in the report |
| Draft-first; publish only after validation; fail-closed on any EV-* (rel. 22.3) | `release.yml` `draft: true`; `.github/workflows/release-finalizer.yml`; `scripts/finalize-release-evidence.sh` Phase G (publish last) | finalizer `--self-test` P1 (dry-run), T1–T11, T17–T18; actionlint on both workflows | published `RELEASE-EVIDENCE.json`/`.md` assets on the release; released body = rendered report |
| Rehearsal on a real tag (rel. 22.3, §5) | annotated tag `v0.16.0` on this branch set (owner-authorized) | originating `Release` run + finalizer run | DoD §5 rows: run URLs, generated assets, sha comparisons |
| Auditable record without manual duplication (rel. 22.4) | `docs/release-runbook.md` §Draft → finalizer → publish; DoD §1–§7; `CHANGELOG.md` | `bash scripts/check-doc-sync.sh` (+ `--self-test`); actionlint | release `RELEASE-EVIDENCE.md` (rendered summary) that epics/reviews cite |
| Does not claim historical immutability; tag protection = owner-verified (rel. 22.4) | runbook tag-immutability note; DoD §5 "Future-tag repository controls" | review of this matrix | DoD §5 row is filled only from separate owner-verified evidence or left explicitly pending |

## 6. Explicit exclusions and safety

- No changes to, recreation, deletion, or republishing of `v0.15.0` or its assets.
- No production deployment or host operation.
- No production deployment-contract change from JAR to container image.
- No GitHub ruleset/settings change under this epic without separate explicit owner authorization.
- No new tag or public Release created solely to exercise tests without explicit owner authorization.
- No commit, push, merge, or tag without the authorization required by repository policy.

---

**Epic 22 completion rule:** close only after the generated report, negative tests, finalizer publication boundary, regression gates, and evidence table are complete. If a required value cannot be obtained authoritatively, leave the item pending and report the gap.
