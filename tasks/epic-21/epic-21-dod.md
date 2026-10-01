# Epic 21 — Definition of Done

**Epic:** Release Artifact Identity and Promotion  
**Repository:** `url-shortener-service` only  
**Rule:** no release or implementation claim is complete without evidence tied to the exact source commit and workflow run.

## 1. Source and tag identity

- [ ] Release is triggered from an annotated `vX.Y.Z` tag under the service's existing owner-authorization policy.
- [ ] Workflow proves that the tag's peeled full commit SHA equals the checked-out `HEAD`/event SHA.
- [ ] Release metadata records repository, tag, semantic version, full commit SHA, workflow run ID/attempt, candidate JAR filename, and candidate SHA-256.
- [ ] Any identity mismatch fails before release publication.

## 2. Build-once candidate

- [ ] The full Maven verification build on the tagged commit produces the versioned candidate JAR.
- [ ] No later release job rebuilds a substitute JAR.
- [ ] The candidate and provenance/checksum manifest are uploaded once from the producing job and retrieved from the same workflow run by all consumers.
- [ ] Missing, duplicate, stale, altered, or incorrectly versioned candidate artifacts fail closed without fallback builds.

## 3. Exact artifact consumers

- [ ] k6, runtime smoke, and restore drill each verify and use the candidate JAR.
- [ ] Each consumer records the identical candidate SHA-256.
- [ ] The restore drill receives the candidate through its explicit `JAR` input.
- [ ] All required release gates pass on the release tag; no gate is skipped or weakened.

## 4. Exact release and deployment artifact

- [ ] The GitHub Release attaches the same candidate JAR, verified again immediately before publication.
- [ ] `SHA256SUMS` is generated from and successfully checked against the actual release assets.
- [ ] The release manifest/notes identify the tag, full source commit, workflow run, JAR filename, and JAR SHA-256.
- [ ] Existing deployment downloads that JAR and verifies its checksum; it does not rebuild.
- [ ] If an image is produced, it contains the same JAR (verified by extracting and hashing it); its SBOM identifies the actual image subject/digest. No second source build is treated as the tested release candidate.
- [ ] The production deployment contract remains JAR-based unless separately approved by the owner.

## 5. Governance and documentation

- [ ] All committed documentation and code for this epic are in English.
- [ ] `docs/release-runbook.md`, `docs/release-engineering.md`, ADR 0008 (if affected), `README.md`, `AGENTS.md`, and `CHANGELOG.md` are synchronized with the implemented flow as required by repository rules.
- [ ] Release runbooks accurately describe the implemented candidate-artifact flow and the evidence to inspect.
- [ ] Dargent's documented policy is captured as a reference, while `url-shortener-service/AGENTS.md` remains authoritative.
- [ ] The docs state the service's existing commit, push, PR, tag, and evidence rules without inventing a merge strategy or mandatory PR-only flow.
- [ ] `CHANGELOG.md` and every directly affected document are synchronized per repository rules.
- [ ] No frontend repository change is included.

## 6. Required closing evidence

Paste real output or stable workflow links/identifiers for:

| Evidence | Value |
|---|---|
| Starting implementation commit | **To be filled from Git** |
| Release tag and full commit SHA | **To be filled from the verified tag** |
| Successful release workflow run ID | **To be filled from GitHub Actions** |
| Candidate JAR filename and SHA-256 | **To be filled from producer output** |
| k6/runtime-smoke/restore-drill SHA-256 values | **To be filled from each job** |
| Published JAR SHA-256 and `SHA256SUMS` verification | **To be filled from downloaded release assets** |
| Image digest and embedded JAR SHA-256, if applicable | **To be filled from the image check** |
| SBOM subject identity, if applicable | **To be filled from the SBOM/image output** |
| Documentation and governance review | **To be filled with changed paths and commit** |

Never replace placeholders with estimates. If a gate or artifact cannot provide evidence, the epic is not Done.

## 7. Explicit exclusions

- No frontend implementation or release change.
- No production deployment, automatic SSH rollout, or host change.
- No switch from JAR deployment to container deployment.
- No tag, push, merge, or commit without the explicit human authorization required by repository rules.
- No additional unrelated production-readiness work.
