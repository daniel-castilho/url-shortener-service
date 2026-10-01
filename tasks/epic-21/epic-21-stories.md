# Epic 21 — Stories and Acceptance Criteria

**Project:** `url-shortener-service`  
**Epic outcome:** release evidence proves that the JAR deployed is exactly the JAR tested from the tagged commit.

## Story 21.1 — Bind a release run to an immutable source identity

**As the system owner,** I want every release run to identify the exact commit behind its annotated version tag, so that a release cannot be mistaken for a different `main` revision or a moving reference.

### Acceptance criteria

- The release workflow resolves the triggering tag to a full commit SHA and verifies that the checked-out `HEAD` is that commit.
- The workflow fails before building or publishing if the tag cannot be resolved, `HEAD` differs from the tag's peeled commit, or the expected release version cannot be derived from the tag.
- The full tag and commit SHA are recorded in release provenance and release notes (or another published release record).
- Every job consuming the candidate artifact proves it belongs to the same workflow run and release tag; it must not download an artifact from a different run or tag.
- No job treats the moving `main`, `latest`, or a short SHA as the source of release identity.

## Story 21.2 — Build the candidate JAR once and test that candidate

**As the release pipeline,** I want to build one versioned JAR candidate and pass it to all runtime gates, so that successful gates refer to the same bytes that are eligible for release.

### Acceptance criteria

- The full Maven verification run on the tag produces the candidate JAR with the tag-derived version.
- The release workflow does not run a second `clean package` to create a replacement JAR after verification.
- The candidate JAR and a provenance/checksum manifest are uploaded once as a workflow artifact.
- `k6-gate`, `runtime-smoke`, and `restore-drill` download the candidate from that same workflow run and verify its SHA-256 before starting it.
- The restore drill passes the downloaded candidate through its existing `JAR` input; no job silently falls back to a locally rebuilt or stale `target/*.jar` file.
- Logs or job summaries record the same candidate filename and SHA-256 for each consuming job.
- A missing, ambiguous, tampered, or wrong-version candidate fails closed; the job does not rebuild a substitute.

## Story 21.3 — Publish and deploy the tested JAR with verifiable provenance

**As an operator,** I want the GitHub Release and deployment script to consume the tested candidate, so that the checksum used at deployment closes the same chain that passed release gates.

### Acceptance criteria

- The release job downloads the candidate from its own workflow run, verifies its provenance and SHA-256, and attaches those exact bytes as the versioned JAR asset.
- The published `SHA256SUMS` contains the candidate JAR entry and is verified with `sha256sum -c` before release creation.
- The release record includes the annotated tag, full source commit SHA, workflow run identity, JAR filename, and JAR SHA-256.
- `scripts/deploy.sh` continues to fail closed when the Release, JAR asset, or matching checksum is missing or invalid; no rebuild is introduced into deployment.
- If the release builds a Docker image, the image packages the candidate JAR rather than compiling the source again. Extracting the JAR from the image and hashing it yields exactly the candidate JAR hash.
- The Trivy result and CycloneDX SBOM identify their image subject/digest; the release notes or provenance clearly state that subject and its embedded candidate-JAR hash. The SBOM must not be presented as describing a different, independently rebuilt application JAR.
- The image remains a packaging/security-check derivative unless the owner separately approves changing the deployment contract. No container image is treated as the production deploy artifact by implication.

## Story 21.4 — Document release governance and evidence policy

**As a contributor and release owner,** I want the release runbook to state the applicable repository policies and evidence requirements, so that future releases follow a documented, repository-specific process.

### Acceptance criteria

- The documentation is written entirely in English and synchronized with the implemented workflow.
- The release docs describe the Dargent reference accurately: documentation/CHANGELOG synchronization; Conventional Commit examples; branch prefixes (`feat/`, `fix/`, `chore/`); small, single-concern PRs with green CI and no skipped gates; the documented direct-push/no-PR-only exception; annotated `vX.Y.Z` tags; and DoD/gates on the tagged commit. They also state that Dargent does not prescribe a merge strategy and its live branch-protection settings were not verified by this source inspection.
- The docs preserve the URL Shortener Service rules: focused Conventional Commits, no push unless the human explicitly asks, and no tag unless the human asks after DoD. They do not adopt Dargent's direct-push exception, invent a service branch naming rule, or invent a merge strategy.
- The Dargent policy is identified as a reference, not copied as an overriding rule. In particular, Dargent's direct-push exception is not used to weaken the service's `AGENTS.md` rule.
- Release evidence is recorded without inventing run IDs, hashes, test counts, or successful outcomes. Evidence records cite the exact commit and workflow run that produced them.
- The documentation does not require changes to the frontend repository or its owner workflow.

## Story ordering and dependencies

```text
21.1 source identity
  → 21.2 one candidate JAR + runtime consumers
    → 21.3 release/deploy promotion and image/SBOM linkage
      → 21.4 documentation and evidence closure
```

Stories 21.1–21.3 are the artifact-integrity path. Story 21.4 documents the final implemented contract and must not describe aspirational behavior as current behavior.
