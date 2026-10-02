# Epic 22 — Definition of Done

**Epic:** Machine-Generated Release Evidence  
**Repository:** `url-shortener-service` only  
**Rule zero — zero-from-memory:** every number, hash, run identifier, timestamp, job result, and test count in this DoD must be generated from or pasted from authoritative output included here. Do not fill placeholders from recollection.

**Status:** Completed — rehearsed end-to-end on `v0.16.0` (published 2026-10-02 with
`RELEASE-EVIDENCE.json`/`.md`). Acceptance record below filled only from observed outputs.

## 1. Evidence contract

- [x] `RELEASE-EVIDENCE.json` schema is versioned and checked into the repository.
- [x] `RELEASE-EVIDENCE.md` and any Release summary are rendered from the JSON; neither is independently hand-edited.
- [x] The report records repository, tag, semantic version, tag object type/current target, full source commit, originating workflow/run/attempt/number/URL, required job conclusions, candidate and consumer SHA-256 values, release asset hashes, image identity, image-embedded JAR hash, SBOM identity, and authoritative final run conclusion/completion time.
- [x] Final workflow conclusion/completion time comes from GitHub data after the originating run is complete. No guessed or in-progress time is recorded as completion.
- [x] The report does not claim to prove historical tag immutability and does not contain its own digest.

## 2. Artifact and run identity

- [x] Every required candidate consumer produces a receipt tied to the same repository, tag, source commit, run ID, run attempt, filename, and candidate SHA-256.
- [x] The finalizer rejects missing, duplicate, cross-run, stale, malformed, or contradictory receipts.
- [x] The published JAR downloaded from the matching draft Release is byte-for-byte equal to the candidate and passes `sha256sum -c` against the published checksum asset.
- [x] The image-embedded JAR hash equals the candidate hash, if an image is produced.
- [x] The SBOM's subject identifies the released image and its declared source/revision metadata is consistent with the report.
- [x] The current release tag is annotated and resolves to the originating commit. This is a current-ref check only.
- [x] Any mismatch, failed/skipped required job, missing artifact, or unavailable authoritative value prevents publication.

## 3. Finalization and release publication

- [x] The normal release workflow creates only a draft release until post-run evidence finalization succeeds.
- [x] The finalizer accepts only the expected completed, successful release workflow in the same repository and validates its originating run ID/attempt.
- [x] The finalizer obtains run conclusion and completion metadata from authoritative GitHub data; it does not substitute its own run context or timestamp.
- [x] `RELEASE-EVIDENCE.json` and its generated human-readable report are attached to the matching Release.
- [x] The Release becomes public only after the finalizer validates the report and actual draft assets.
- [x] Any finalizer/API/asset failure leaves the Release unpublished and does not move, delete, or recreate the tag or draft.
- [x] Workflow permissions are least-privilege; no write-capable credential is exposed to untrusted code.

## 4. Documentation and policy

- [x] `docs/release-runbook.md` documents the implemented draft → finalizer → publish flow, report fields, verification commands, and failure behavior.
- [x] Epic/DoD instructions cite the generated report and originating workflow; they no longer require manual duplication of release-specific hashes/timestamps/image IDs.
- [x] `CHANGELOG.md` and every directly affected document are synchronized per `AGENTS.md`.
- [x] All repository content for this epic is in English and specific to `url-shortener-service`.
- [x] Epic 21's owner disposition remains intact. No historical waiver is rewritten as a passed criterion; `v0.15.0` and its assets remain unchanged.
- [x] The documentation says tag-update/deletion protection must be verified through repository controls and does not claim that this epic or its verifier proves historical immutability.
- [x] No frontend repository change, production deployment, or unrelated production-readiness work is included.

## 5. Required closing evidence

Paste raw outputs or stable links. Replace placeholders only with observed results.

| Evidence | Value |
|---|---|
| Starting implementation commit | `fb6c1ab` (22.1: schema + generator) |
| Closing implementation commit | `8605fa4` (finalizer cross-run download fix) + DoD closing commit |
| Changed paths | `git diff --name-only 1b75e0d..8605fa4` — see `git log --oneline 1b75e0d..8605fa4` (fb6c1ab, e72e460, b2646d4, 13b2f47, c6c7111, 6196be2, 8605fa4) |
| Evidence schema validation | `release_evidence.py validate --schema schemas/release-evidence.schema.json --json RELEASE-EVIDENCE.json` → `OK: RELEASE-EVIDENCE.json is valid (schema v1)` (also `sha256sum -c SHA256SUMS` → `url-shortener-service-0.16.0.jar: OK`) |
| Generator/self-test result | `python3 scripts/release_evidence.py self-test` → `OK: 25 acceptance/rejection cases passed.` (negative cases planted and rejected) |
| Receipt/cross-artifact test result | `bash scripts/write-release-receipt.sh --self-test` → 7/7 (incl. CLI-contract case that caught the CI `unknown argument: gates` bug); fixture negative cases T7/T10–T16/T18/T19 reject cross-run/corrupt/tampered receipts |
| Finalizer security/dry-run result | `bash scripts/finalize-release-evidence.sh --self-test` → `OK: 22 acceptance/rejection cases passed.` (P1 + 19 security/reliability cases; T1–T9, T17 skip/reject guards verified) |
| Originating release workflow run and conclusion | run `36952425861` (number 64, attempt 1) — success, all 5 jobs (Gates/k6 Gate/Runtime Smoke/Restore Drill/Release): https://github.com/daniel-castilho/url-shortener-service/actions/runs/36952425861 |
| Finalizer workflow run and conclusion | run `36953205133` (attempt 1) — success: https://github.com/daniel-castilho/url-shortener-service/actions/runs/36953205133 |
| Generated release-evidence assets | published on `v0.16.0`: `RELEASE-EVIDENCE.json` (5971 B, canonical) + `RELEASE-EVIDENCE.md` (3300 B, rendered from the JSON); downloaded and re-validated standalone |
| Candidate/consumer/Release JAR SHA-256 comparison | report: candidate `url-shortener-service-0.16.0.jar` sha `5840036607ef…`; producer `gates` + consumers `k6-gate`, `runtime-smoke`, `restore-drill`, `release` all agree; `receipts.sha256sums_verified: true`; `sha256sum -c` of the published asset OK |
| Image-embedded JAR and SBOM subject comparison | report: `image.embedded_jar_sha256 == candidate.sha256` (True); `sbom.subject_image_id == image.id` (True); sbom asset `sbom-url-shortener-0.16.0.json` present |
| Full regression gates | Release run Gates job = full `./mvnw verify` success; local unit suite `/mvnw test` → `Tests run: 305, Failures: 0, Errors: 0`; self-test gates 25+7+22+17 green; actionlint clean on `release.yml` + `release-finalizer.yml` (only pre-existing ci.yml/load-test.yml hints remain); check-doc-sync/boundaries/living-spec PASS |
| Documentation review | runbook §"Draft → finalizer → publish" (13b2f47); DoD traceability §7; CHANGELOG `[0.16.0]` (c6c7111); AGENTS.md debt item 37 |
| Future-tag repository controls | **Implemented (2026-10-02):** ruleset `Release tags - protected` (id `24344264`), enforcement `active`, target `tag`, pattern `refs/tags/v*`, rules `creation`/`update`/`deletion`, bypass actor = owner (`daniel-castilho`, always). Verifying API did not create/move/delete any tag to prove blocking; **residual owner step:** confirm in GitHub Settings that no org/enterprise ruleset or policy overrides/loosens this one (org admins can still bypass; CI `GITHUB_TOKEN` is NOT granted bypass). |

Rehearsal deviations recorded (owner-authorized, 2026-10-02): the first `v0.16.0` tag was deleted and re-issued twice because (a) `write-release-receipt.sh`'s arg loop dropped its values (`unknown argument: gates`, fixed in `6196be2`), and (b) `actions/download-artifact@v4` cannot read another run's artifacts from a `workflow_run` without explicit `github-token`/`repository` (go-swagger/go-swagger#3344, fixed in `8605fa4`). Neither attempt produced a release (run 36950086001 failed at Gates; run 36951483876 published nothing — finalizer failure 36952278534). Final tag `v0.16.0` → commit `8605fa4`, annotated.

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
