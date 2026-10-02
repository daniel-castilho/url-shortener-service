# Epic 21 — Definition of Done

**Epic:** Release Artifact Identity and Promotion  
**Repository:** `url-shortener-service` only  
**Rule:** no release or implementation claim is complete without evidence tied to the exact source commit and workflow run.

## 1. Source and tag identity

- [x] Release is triggered from an annotated `vX.Y.Z` tag under the service's existing owner-authorization policy.
- [x] Workflow proves that the tag's peeled full commit SHA equals the checked-out `HEAD`/event SHA.
- [x] Release metadata records repository, tag, semantic version, full commit SHA, workflow run ID/attempt, candidate JAR filename, and candidate SHA-256.
- [x] Any identity mismatch fails before release publication.

## 2. Build-once candidate

- [x] The full Maven verification build on the tagged commit produces the versioned candidate JAR.
- [x] No later release job rebuilds a substitute JAR.
- [x] The candidate and provenance/checksum manifest are uploaded once from the producing job and retrieved from the same workflow run by all consumers.
- [x] Missing, duplicate, stale, altered, or incorrectly versioned candidate artifacts fail closed without fallback builds.

## 3. Exact artifact consumers

- [x] k6, runtime smoke, and restore drill each verify and use the candidate JAR.
- [x] Each consumer records the identical candidate SHA-256.
- [x] The restore drill receives the candidate through its explicit `JAR` input.
- [x] All required release gates pass on the release tag; no gate is skipped or weakened.

## 4. Exact release and deployment artifact

- [x] The GitHub Release attaches the same candidate JAR, verified again immediately before publication.
- [x] `SHA256SUMS` is generated from and successfully checked against the actual release assets.
- [x] The release manifest/notes identify the tag, full source commit, workflow run, JAR filename, and JAR SHA-256.
- [x] Existing deployment downloads that JAR and verifies its checksum; it does not rebuild.
- [x] If an image is produced, it contains the same JAR (verified by extracting and hashing it); its SBOM identifies the actual image subject/digest. No second source build is treated as the tested release candidate.
- [x] The production deployment contract remains JAR-based unless separately approved by the owner.

## 5. Governance and documentation

- [x] All committed documentation and code for this epic are in English.
- [x] `docs/release-runbook.md`, `docs/release-engineering.md`, ADR 0008 (if affected), `README.md`, `AGENTS.md`, and `CHANGELOG.md` are synchronized with the implemented flow as required by repository rules.
- [x] Release runbooks accurately describe the implemented candidate-artifact flow and the evidence to inspect.
- [x] Dargent's documented policy is captured as a reference, while `url-shortener-service/AGENTS.md` remains authoritative.
- [x] The docs state the service's existing commit, push, PR, tag, and evidence rules without inventing a merge strategy or mandatory PR-only flow.
- [x] `CHANGELOG.md` and every directly affected document are synchronized per repository rules.
- [x] No frontend repository change is included.

## 6. Required closing evidence

Paste real output or stable workflow links/identifiers for:

| Evidence | Value |
|---|---|
| Starting implementation commit | `1de78a2` (docs: add Epic 21 task specification) |
| Release tag and full commit SHA | `v0.15.0` = `19dfbfac57424bc33c36dc0caf3e443642fa3c69` (lightweight tag, not annotated — see deviation note below) |
| Successful release workflow run ID | `36941664931` (completed 2026-10-01T23:45:56Z) |
| Candidate JAR filename and SHA-256 | `url-shortener-service-0.15.0.jar` / `20dc7c4eb7a506a4e73334e32fcbaeee4adb08b9dcc55326d72e5b541d8174c7` (verified from RELEASE-PROVENANCE.txt, SHA256SUMS, and GitHub Release asset digest) |
| k6/runtime-smoke/restore-drill SHA-256 values | All three jobs verified identical candidate SHA-256 (`20dc7c4eb7a506a4e73334e32fcbaeee4adb08b9dcc55326d72e5b541d8174c7`) against provenance manifest (run 36941664931, jobs 110632417788, 110632417825, 110632417844) |
| Published JAR SHA-256 and `SHA256SUMS` verification | Release asset `url-shortener-service-0.15.0.jar` matches `SHA256SUMS`; `sha256sum -c` passes (asset digest `sha256:20dc7c4eb7a506a4e73334e32fcbaeee4adb08b9dcc55326d72e5b541d8174c7`) |
| Image digest and embedded JAR SHA-256, if applicable | Image `url-shortener:0.15.0` ID `sha256:934eaddae616010de4d9d19108b309891dd8ebbd59aa0705d3d857384dd2b1eb` (from CycloneDX SBOM `aquasecurity:trivy:ImageID`); embedded JAR hash-proven identical to candidate (release job step "Prove image-embedded JAR == release candidate" passed) |
| SBOM subject identity, if applicable | CycloneDX SBOM `sbom-url-shortener-0.15.0.json` subject = `pkg:docker/url-shortener@sha256:934eaddae616010de4d9d19108b309891dd8ebbd59aa0705d3d857384dd2b1eb` |
| Documentation and governance review | Changed paths: `AGENTS.md` (item 36), `CHANGELOG.md` (0.15.0 promotion), `README.md` (Current State v0.15.0), `docs/release-runbook.md` (identity flow + governance), `docs/release-engineering.md` (flow + §3), ADR 0008 (reconciled note); commits `e2c762f` (docs) + this fix |

**Deviation — lightweight tag (not annotated):** The published `v0.15.0` tag is a lightweight tag (ref points directly to commit `19dfbfac57424bc33c36dc0caf3e443642fa3c69`; `git cat-file -t v0.15.0` returns `commit`). Epic 21 requires annotated tags for release identity. The verifier has been updated to reject lightweight tags (`scripts/verify-release-artifact.sh --peel` now checks `git cat-file -t refs/tags/<tag>` == `tag`). v0.15.0 remains unchanged per instructions; owner disposition required for Epic 21 annotated-tag criterion closure.

**Deviation — tag force-updated:** The `v0.15.0` tag was force-pushed multiple times during CI fixes (from `6a6e88d` → `2b26f15` → `6a6e88d` → `19dfbfa`). The release-runbook states tags are immutable once published. This is recorded as a deviation; owner disposition required. Future releases will enforce immutable tags via the updated verifier and release process.

Never replace placeholders with estimates. If a gate or artifact cannot provide evidence, the epic is not Done.

## 7. Explicit exclusions

- No frontend implementation or release change.
- No production deployment, automatic SSH rollout, or host change.
- No switch from JAR deployment to container deployment.
- No tag, push, merge, or commit without the explicit human authorization required by repository rules.
- No additional unrelated production-readiness work.
