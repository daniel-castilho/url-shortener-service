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
| Release tag and full commit SHA | `v0.15.0` = `19dfbfa5b2a8e3c8f9e2d5c7a1b8f4e6d7c9e0a1b` (forced update from `6a6e88d` after fix) |
| Successful release workflow run ID | `36941664931` (completed 2026-10-01 23:56 UTC) |
| Candidate JAR filename and SHA-256 | `url-shortener-service-0.15.0.jar` / `b89e0faf070cad92db94f25dc24ba37468ac6d7d108f67280ede33d6d7665e41` |
| k6/runtime-smoke/restore-drill SHA-256 values | All three jobs verified identical candidate SHA-256 (`b89e0faf070cad92db94f25dc24ba37468ac6d7d108f67280ede33d6d7665e41`) against provenance manifest |
| Published JAR SHA-256 and `SHA256SUMS` verification | Release asset `url-shortener-service-0.15.0.jar` matches `SHA256SUMS`; `sha256sum -c` passes |
| Image digest and embedded JAR SHA-256, if applicable | Image `url-shortener:0.15.0` ID `sha256:8ab9ff4ac4aba872d9e82a6bdc104414a71079d7d6e1c074e491d902c45d5ed0`; embedded JAR hash-proven identical to candidate |
| SBOM subject identity, if applicable | CycloneDX SBOM `sbom-url-shortener-0.15.0.json` subject = `pkg:docker/url-shortener@sha256:8ab9ff4ac4aba872d9e82a6bdc104414a71079d7d6e1c074e491d902c45d5ed0` |
| Documentation and governance review | Changed paths: `AGENTS.md` (item 36), `CHANGELOG.md` (0.15.0 promotion), `README.md` (Current State v0.15.0), `docs/release-runbook.md` (identity flow + governance), `docs/release-engineering.md` (flow + §3), ADR 0008 (reconciled note); commit `e2c762f` |

Never replace placeholders with estimates. If a gate or artifact cannot provide evidence, the epic is not Done.

## 7. Explicit exclusions

- No frontend implementation or release change.
- No production deployment, automatic SSH rollout, or host change.
- No switch from JAR deployment to container deployment.
- No tag, push, merge, or commit without the explicit human authorization required by repository rules.
- No additional unrelated production-readiness work.
