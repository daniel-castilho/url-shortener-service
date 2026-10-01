# Epic 21 — Testing and Verification Plan

**Objective:** prove that the artifact SHA-256 is invariant from the tag-triggered candidate build through every release gate and the published JAR.

## 1. Static workflow checks

- [ ] Inspect `release.yml` and confirm every downstream job consumes the named artifact from the same workflow run.
- [ ] Confirm no release consumer runs `mvn package`, `mvn clean package`, or another build command to create a replacement candidate.
- [ ] Confirm the release job verifies tag-to-commit identity and candidate provenance before publishing.
- [ ] Confirm the restore-drill step passes the downloaded JAR via `JAR` rather than relying on `target/*.jar` discovery.
- [ ] Confirm release checksum creation and verification operate on the downloaded candidate file.
- [ ] Confirm any image packaging step uses the candidate JAR, not a source-compiling path, for the release image.

Static inspection is supporting evidence only; it does not replace a green workflow execution.

## 2. Artifact manifest/checksum tests

Test the candidate-manifest/checksum handling with positive and negative cases, using the repo's existing test conventions and no production secrets:

| Case | Expected result |
|---|---|
| Valid candidate + matching manifest | Accepted |
| One-byte candidate mutation | Rejected before application startup |
| Missing candidate or missing manifest | Rejected; no rebuild fallback |
| Wrong release tag, commit SHA, run ID, or version | Rejected |
| Ambiguous/multiple candidate JARs | Rejected unless exactly one is explicitly selected and verified |
| Tag's peeled commit differs from workflow checkout SHA | Release fails before publishing |
| `SHA256SUMS` does not verify | Release creation is not reached |

If a small verification script is introduced, provide a self-test that proves both acceptance and rejection paths, following the service's existing `--self-test` gate pattern.

## 3. Release workflow execution

Run the complete release workflow on an owner-approved annotated test/rehearsal tag that points to the intended candidate commit. Respect the existing tag-authorization and immutability rules; do not move or delete a tag to repeat a run.

Required evidence from the run:

- tag name and full peeled commit SHA;
- workflow run ID and successful conclusion for every required job;
- candidate artifact name and SHA-256;
- the same SHA-256 reported by k6, runtime smoke, and restore drill;
- release asset filename and SHA-256;
- successful `sha256sum -c SHA256SUMS` against the downloaded release assets;
- if an image is built: image identity/digest, SBOM subject, and equality of the image-extracted JAR SHA-256 to the candidate JAR SHA-256.

The run must fail closed if any release-gate job fails or any candidate hash/provenance comparison differs. Release creation must depend on all required gates.

## 4. Deploy consumer verification

- [ ] Run the existing release download/checksum verification path against the rehearsal release, or test its verification function in an isolated temporary directory if deployment cannot be exercised.
- [ ] Prove the deploy script rejects a missing release asset, missing checksum, and tampered JAR.
- [ ] Confirm deployment still consumes the published JAR and does not build from source.

Do not perform a production deployment as part of this epic without separate human authorization.

## 5. Regression gates

- [ ] `./mvnw verify` passes with the release revision on the exact tag commit.
- [ ] Existing boundary, documentation-sync, security, metrics-frozen, living-spec, promtool/amtool, CHANGELOG, k6, runtime-smoke, and restore-drill gates remain enabled and green as applicable.
- [ ] No gate is marked `continue-on-error`, skipped, or made advisory to accommodate artifact transfer.
- [ ] The frontend release pipeline remains unchanged and out of scope.

## 6. Evidence quality

- Every recorded hash, SHA, run ID, version, and result must be copied from command/workflow output and cite the commit where it was produced.
- Do not fill evidence cells with expected values or infer success from workflow YAML.
- A green run on a different commit, tag, or workflow attempt is not evidence for the release candidate under review.
