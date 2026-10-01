# Epic 21: Release Artifact Identity and Promotion

**Project:** `url-shortener-service` only  
**Priority:** P1 — production release integrity  
**Status:** Specification for implementation; no repository code or workflow has been changed by the architect.  
**Assessment baseline:** `main` snapshot `48e2eb9` (2026-09-15), inspected read-only. Reconfirm the target commit before implementation.

## Goal

Make the exact source commit and exact JAR tested by the release pipeline provably identical to the JAR published in the GitHub Release and consumed by `scripts/deploy.sh`.

The release chain must be auditable:

```text
annotated release tag
  → resolved source commit SHA
  → one versioned candidate JAR
  → unit/integration + k6 + runtime-smoke + restore-drill consume that JAR
  → release publishes that same JAR + verified SHA-256 + provenance
  → deploy downloads and verifies that JAR
```

If a release Docker image is retained as a security/package check, it must contain the same candidate JAR. Its SBOM must name the image digest it describes and the embedded JAR hash. The image is not a second, independently compiled application artifact.

## Evidence-based baseline

The inspected `release.yml` is tag-triggered and runs substantial checks, but currently builds the JAR repeatedly:

- `gates` runs `./mvnw verify -Drevision=<semver>` and then runs `clean package -DskipTests`.
- `k6-gate`, `runtime-smoke`, `restore-drill`, and `release` each run another `clean package -DskipTests`.
- The release job builds the Docker image from source through the multi-stage `Dockerfile`, computes `SHA256SUMS` for its local JAR, and publishes the JAR, checksum file, and image SBOM.
- The downstream jobs do not download and verify one common candidate JAR. The checksum proves the downloaded release asset has not changed relative to the release job's checksum; it does not prove the asset is the file used by the earlier test jobs.
- `ci-restore-drill.sh` already accepts an explicit `JAR` environment variable, and `scripts/deploy.sh` deploys the JAR from the GitHub Release after checksum verification.

The operating deployment contract is therefore kept JAR-based. The workflow must promote one candidate JAR rather than silently changing the production deployment model.

## Scope

### In scope

- Bind every release job to the commit resolved from the triggering annotated tag and record that identity.
- Produce one versioned JAR candidate from the tag's full verification build.
- Transfer that exact candidate through k6, runtime smoke, restore drill, image packaging/scanning, and release publication.
- Verify SHA-256 before every downstream use and before publishing/deploying.
- Publish a provenance manifest sufficient to identify the repository, tag, commit SHA, workflow run, artifact filename, and JAR SHA-256.
- Make the Docker image and SBOM relationship to the candidate JAR explicit.
- Document release, documentation, commit, push, PR, merge, and tag policies using Dargent as a reference while preserving the URL Shortener Service's own binding rules.

### Out of scope

- Changes to `url-shortener-web`; it has a different owner and a separate release pipeline.
- Switching production deployment from JAR/systemd to container-image deployment.
- Automatic SSH deployment, host provisioning, or changes to production topology.
- New Maven dependencies, registry publishing, signing infrastructure, or a new CI provider without explicit owner approval.
- General production-readiness work unrelated to artifact identity.
- Imposing a PR-only process or a merge strategy that the service's governing documents do not specify.

## Reference policy and repository authority

Dargent (`AGENTS.md`, `docs/coding-standards.md`, `docs/release-runbook.md`, and the E3.5 repository-hardening specification) is a reference for English-only repository content; documentation/CHANGELOG synchronization; focused Conventional Commits; branch prefixes (`feat/`, `fix/`, `chore/`); PRs that are small, single-concern, and green with no skipped gates; annotated semver tags; and release evidence. Dargent's E3.5 specification explicitly preserves direct pushes, does not require PR-only flow or status checks, and describes branch protection against force-push and deletion. The E3.5 backlog is marked open, so the live GitHub branch settings are not certified by this file inspection. No squash/rebase/merge-commit strategy is prescribed in the repository policy.

For this epic, `url-shortener-service/AGENTS.md` remains authoritative: English-only repository content, focused Conventional Commits, documentation synchronization, no push unless the human explicitly asks, and no tag unless the human asks after the milestone DoD. Dargent's direct-push exception must not weaken the service's rule. The epic must not infer or invent a merge method.

## Recommended implementation choice

Keep the JAR as the canonical deployable artifact because the existing blue/green deploy script downloads and runs a JAR. Build it once at the tagged revision, publish it as an immutable workflow artifact, and have every subsequent job download and hash-check it. If an image is built, package this JAR without recompiling the Java source; verify the JAR extracted from the image has the same hash.

## Success outcome

For a release tag, the recorded tag commit SHA, every candidate-consumer job's JAR SHA-256, the released JAR SHA-256, and the deployed JAR SHA-256 agree. Any mismatch, missing artifact, unresolved tag, failed gate, or absent provenance fails closed before publication or deployment.
