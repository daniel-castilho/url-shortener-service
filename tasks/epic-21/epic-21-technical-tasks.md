# Epic 21 — Technical Tasks

**Project:** `url-shortener-service`  
**Implementation owner:** Software Engineer assigned by the project owner  
**Constraints:** Do not commit, push, merge, or create a tag without the human authorization required by `AGENTS.md`.

## T0 — Reconfirm the implementation baseline

- [ ] Read `AGENTS.md`, `docs/release-runbook.md`, `docs/release-engineering.md`, ADR 0008, and the current `release.yml`, `Dockerfile`, `scripts/deploy.sh`, and `scripts/ci-restore-drill.sh`.
- [ ] Record the implementation starting commit SHA in the handoff.
- [ ] Reconfirm whether the service deploys the JAR through `deploy.sh`; preserve that contract unless the owner explicitly changes it.
- [ ] Inspect current GitHub Actions artifact actions and retention conventions; do not add a dependency or unapproved release mechanism.

**Done when:** the baseline and canonical artifact decision are recorded from the current repository state, not assumed from this assessment snapshot.

## T1 — Establish and verify tag-to-commit identity

- [ ] In the tag-triggered release workflow, derive the release version from `GITHUB_REF_NAME` using the repository's existing version convention.
- [ ] Resolve the tag's peeled commit and compare it to the checked-out `GITHUB_SHA`/`HEAD`; fail closed on mismatch.
- [ ] Ensure every release job checks out the event commit or consumes the artifact produced by that event's workflow run. Do not resolve source from `main` or a moving tag in a downstream job.
- [ ] Emit a non-secret provenance manifest with at least:
  - repository identifier;
  - release tag and semantic version;
  - full source commit SHA;
  - workflow run ID and attempt;
  - candidate JAR filename and SHA-256.
- [ ] Include the full source commit and artifact identity in the published release record.

**Done when:** a mismatched tag/checkout is rejected, and the same source identity is available to every later step and to a release consumer.

## T2 — Produce the versioned candidate exactly once

- [ ] Make the existing full Maven `verify` run with the tag-derived `revision` the producer of the release candidate.
- [ ] Capture the expected versioned JAR from that successful `verify` output. Fail if no JAR or more than one candidate matches the release naming contract.
- [ ] Remove downstream `clean package -DskipTests` rebuilds from `k6-gate`, `runtime-smoke`, `restore-drill`, and `release`.
- [ ] Avoid cleaning and repackaging after `verify` in a way that replaces the JAR before it is uploaded.
- [ ] Generate a SHA-256 checksum and provenance manifest for the candidate.
- [ ] Upload the candidate JAR and manifest once as a named workflow artifact, using the repository's existing approved GitHub Actions conventions and an explicit retention period.

**Done when:** the artifact producer is unambiguous, all later jobs can retrieve that exact file from the same run, and no fallback build can mask an artifact-transfer failure.

## T3 — Consume the same artifact in every release gate

- [ ] In `k6-gate`, download the candidate artifact from the current workflow run, verify the manifest and SHA-256, and launch that JAR.
- [ ] In `runtime-smoke`, do the same before running `scripts/smoke.sh` and `scripts/verify-graceful-shutdown.sh`.
- [ ] In `restore-drill`, download and verify the candidate, then pass its absolute path through the existing `JAR` environment variable to `scripts/ci-restore-drill.sh`.
- [ ] Make each job fail before application startup if the artifact is absent, its hash differs, its version is wrong, or its source provenance does not match the triggering tag/run.
- [ ] Record the candidate filename and hash in each job's logs or summary. Do not log secrets.
- [ ] Preserve existing test thresholds and semantics; this story changes artifact flow, not application behavior or rate-limit policy.

**Done when:** k6, runtime smoke, and restore drill all report the same candidate SHA-256 as T2.

## T4 — Package, scan, and publish without rebuilding the application

- [ ] Change release packaging so the release job downloads the candidate from T2 and verifies it before use.
- [ ] Publish that exact JAR as the release asset; create `SHA256SUMS` from the exact candidate and run `sha256sum -c` before creating the release.
- [ ] Keep `scripts/deploy.sh`'s download-and-verify behavior; confirm it recognizes the resulting asset names and checksum format.
- [ ] If the release job builds a Docker image, change the release image path to consume the candidate JAR as an input rather than re-running Maven. The ordinary developer multi-stage image build may remain available, but it must not become a second release candidate.
- [ ] Extract the JAR from the release image and compare its SHA-256 to the candidate before accepting the image.
- [ ] Generate the existing Trivy scan/SBOM against the image that contains the verified candidate. Record the image digest/identity and distinguish the image SBOM from the JAR checksum in release metadata.
- [ ] Do not publish or deploy a moving `latest` reference as release identity. Do not introduce a registry push or switch production deployment to OCI images without owner approval.

**Done when:** the GitHub Release JAR, deployment input, and image-embedded JAR (if an image is produced) all match the tested candidate hash; the SBOM subject is explicit.

## T5 — Document governance and release operation

- [ ] Update `docs/release-runbook.md` and any other directly affected release docs with the implemented artifact-promotion steps and provenance fields.
- [ ] Record the Dargent reference policy accurately: English-only content; doc/CHANGELOG synchronization; focused Conventional Commits; `feat/`, `fix/`, and `chore/` branch prefixes; small, one-concern PRs with green, unskipped CI when a PR is used; annotated semver tags after DoD; and evidence tied to the tagged commit.
- [ ] Also record the Dargent E3.5 exception: its documented process preserves direct pushes and does not require PR-only flow or required status checks; it describes blocking force-pushes/deletions. The E3.5 backlog is marked open, so do not claim the live GitHub branch setting was verified. The repository does not prescribe squash, rebase, or merge-commit strategy.
- [ ] Reconcile the reference with URL Shortener Service rules: `AGENTS.md` is binding, pushes and tags require explicit human authorization, and no Dargent direct-push exception, PR-only rule, branch-prefix rule, or merge strategy may be imposed on the service by this epic.
- [ ] Update the `CHANGELOG.md` Unreleased section and relevant project status/debt documentation if required by the existing doc-sync rules.
- [ ] Do not update the frontend repository from this backend epic.

**Done when:** runbooks state the actual artifact chain and governance rules without claiming a gate, artifact identity, or merge policy that was not proven.

## Required file scope (anticipated)

Expected changes are limited to the backend release path and its documentation, likely including:

- `.github/workflows/release.yml`;
- `Dockerfile` or an explicitly scoped release packaging definition if required to package the candidate JAR;
- `scripts/ci-restore-drill.sh` only if its existing `JAR` input needs a reliability improvement;
- `docs/release-runbook.md`, `docs/release-engineering.md`, and ADR 0008 if its implementation/evidence wording needs reconciliation;
- `README.md`, `AGENTS.md`, and `CHANGELOG.md` where required by the existing doc-sync rules;
- a release evidence record if the existing process requires one.

Do not modify application domain code, frontend files, production hosts, branch settings, or repository governance settings as part of this epic.
