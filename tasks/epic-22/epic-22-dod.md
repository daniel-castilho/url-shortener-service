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

## 6. Explicit exclusions and safety

- No changes to, recreation, deletion, or republishing of `v0.15.0` or its assets.
- No production deployment or host operation.
- No production deployment-contract change from JAR to container image.
- No GitHub ruleset/settings change under this epic without separate explicit owner authorization.
- No new tag or public Release created solely to exercise tests without explicit owner authorization.
- No commit, push, merge, or tag without the authorization required by repository policy.

---

**Epic 22 completion rule:** close only after the generated report, negative tests, finalizer publication boundary, regression gates, and evidence table are complete. If a required value cannot be obtained authoritatively, leave the item pending and report the gap.
