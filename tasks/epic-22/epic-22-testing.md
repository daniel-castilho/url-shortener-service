# Epic 22 — Testing and Verification Plan

**Objective:** prove that every published release-evidence field is generated from the exact completed workflow run and verified release artifacts, and that any mismatch prevents publication.

## 1. Schema and generator tests

| Case | Expected result |
|---|---|
| Complete valid fixture with matching run, receipts, provenance, sums, image, and SBOM | JSON validates; Markdown renders from the JSON |
| Required source field missing (tag, commit, run ID/attempt, candidate hash, required job result) | Generation/finalization fails with the missing field named |
| Invalid full commit SHA, SHA-256, URL, semantic version, or timestamp format | Schema validation rejects the record |
| Non-UTC or ambiguous timestamp | Rejected or normalized from an authoritative UTC source; never guessed |
| Unknown schema version | Rejected with an actionable compatibility error |
| Renderer input differs from canonical JSON | Not possible by design; renderer accepts JSON only |
| Report attempts to include its own digest | Rejected or omitted; no circular self-hash |

## 2. Same-run receipt and artifact identity tests

| Planted condition | Expected result |
|---|---|
| Producer and all consumer receipts match the candidate JAR SHA-256 and run/attempt | Accepted |
| One consumer reports a different JAR SHA-256 | Finalization fails; no Release publication |
| Receipt comes from another run ID, run attempt, repository, tag, or source commit | Rejected as stale/cross-run evidence |
| Candidate receipt missing, duplicated, malformed, or ambiguous | Rejected; no fallback build or latest-run lookup |
| `SHA256SUMS` digest differs from the candidate bytes | Rejected before publication |
| Downloaded draft Release JAR differs from the candidate | Rejected before publication |
| Provenance commit/tag/run identity differs from the completed workflow | Rejected |
| Image-embedded JAR hash differs from the candidate | Rejected |
| SBOM subject does not identify the image being released, or its revision contradicts the source | Rejected |
| Required gate is failed, skipped, cancelled, absent, or not part of the expected run | Rejected; no release publication |
| Current tag is lightweight or resolves to a different commit | Rejected |

Tests must use temporary files and fixture data. Do not mutate, delete, or retag `v0.15.0` or any published release.

## 3. Finalizer security and publication tests

- [ ] Valid completed run from the expected same-repository release workflow is accepted in dry-run mode.
- [ ] A pull-request workflow, unrelated workflow, other repository, unexpected event, unexpected tag format, or untrusted head repository is rejected.
- [ ] A run that is still in progress or has a non-success conclusion cannot be finalized.
- [ ] The finalizer uses the originating run ID/attempt and not its own run context as the artifact identity.
- [ ] In a test/dry-run harness, invalid evidence leaves the draft unpublished and does not delete or recreate the draft/tag.
- [ ] A complete valid fixture attaches the JSON and its rendered Markdown to the matching draft, verifies the asset list, and only then publishes.
- [ ] API failure, unavailable completion metadata, artifact-download failure, asset-upload failure, or permission denial fails closed; no inferred completion timestamp is written.
- [ ] A current-tag movement detected between the release run and finalization blocks publication. The report does not claim to detect historical movements that occurred before the originating run.
- [ ] Workflow permissions are least-privilege and no release write credential is exposed to pull-request code.

If exercising GitHub draft/publish behavior requires a real release tag, obtain explicit owner authorization first. Do not create a disposable tag that would violate the repository's tag immutability policy.

## 4. End-to-end release verification

On an owner-authorized future release only:

1. Confirm the annotated tag resolves to the expected full source commit.
2. Confirm one candidate is produced and all required consumers emit receipts for that candidate from the same workflow run/attempt.
3. Confirm all required jobs have successful conclusions in the originating run.
4. Confirm the release job creates a draft and uploads the candidate JAR, checksum, provenance, and SBOM.
5. Confirm the post-run finalizer reads the completed originating run from GitHub, validates the actual draft assets, and generates the evidence report.
6. Download the draft assets and independently verify the JAR with `sha256sum -c`; compare the candidate, Release JAR, image-embedded JAR, SBOM subject, and report values.
7. Confirm the report is attached to the correct Release and the Release becomes public only after finalization succeeds.
8. Save the exact report, originating run URL, finalizer run URL, and command/API outputs in the Epic DoD.

This epic does not authorize production deployment.

## 5. Regression gates

- [ ] Release-artifact verifier and its self-test remain green, including the annotated-tag negative case delivered by Epic 21.
- [ ] Documentation-sync gate and its self-test pass.
- [ ] Living-spec gate and self-test pass where affected.
- [ ] Boundaries, metrics-frozen, security, CHANGELOG, promtool/amtool, and relevant test/build gates remain enabled and green as applicable.
- [ ] `./mvnw verify` passes on the exact implementation commit where required by the repository's DoD.
- [ ] No gate is made advisory, skipped, or hidden behind `continue-on-error`.
- [ ] No GitHub tag-protection setting is claimed as enabled based solely on workflow YAML or documentation.

## 6. Evidence quality

- Every recorded hash, commit, run ID, run attempt, timestamp, image identity, job conclusion, and test result must be generated from or copied verbatim from an authoritative command/API/workflow output.
- The report's final conclusion and completion time must refer to the originating release workflow, not the finalizer run.
- A green run on a different tag, commit, attempt, or repository is not evidence for the release under review.
- Static YAML inspection is supporting evidence only; it does not prove a successful release or correct published bytes.
