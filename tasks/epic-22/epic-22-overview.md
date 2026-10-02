# Epic 22: Machine-Generated Release Evidence

**Project:** `url-shortener-service` only  
**Priority:** P1 — release evidence integrity  
**Status:** Specification for implementation; no source code, workflow, repository setting, tag, or release has been changed by this document.  
**Dependency:** Epic 21 is reported formally closed with an explicit exception for the historical `v0.15.0` tag. Re-read the actual target branch before implementation and confirm that the Epic 21 verifier, workflow, and documentation are present.

## Goal

Generate a machine-readable evidence record for each future release from the exact workflow run and the artifacts that run validated. Validate the record against the published release assets before the release becomes public. Humans should be able to inspect the evidence without retyping hashes, commit IDs, run IDs, image identities, or timestamps into a Definition of Done.

The release evidence chain becomes:

```text
annotated immutable release tag
  → source commit and release workflow run
  → one candidate JAR and its provenance/checksum
  → per-job receipts proving the candidate consumed by each required gate
  → verified release JAR, image-embedded JAR, and SBOM subject
  → completed workflow metadata from GitHub
  → generated RELEASE-EVIDENCE.json + rendered RELEASE-EVIDENCE.md
  → evidence attached to the matching GitHub Release before publication
```

The evidence report is a release record, not a replacement for the candidate provenance manifest, checksum file, SBOM, workflow logs, or repository tag controls. It links and cross-checks them.

## Why this epic now?

Epic 21 established the intended single-candidate release chain and corrected its closure evidence. The audit also exposed a separate weakness: release facts copied into human-authored documentation can drift from the source commit, published assets, SBOM, or GitHub run. A successful workflow does not make an inaccurately transcribed DoD accurate.

Dargent provides two useful process patterns to adapt:

- **Zero-from-memory evidence:** verifiable numbers and identifiers come from captured command/API output, not recollection or manual reconstruction.
- **Requirement-to-test-to-evidence traceability:** each acceptance criterion names the gate and the evidence that proves it.

The Dargent `evidence-lint.sh` is a reference only. Its current run-ID checks do not validate JAR hashes, image identities, SBOM subjects, or release-asset bytes; Epic 22 must test those relationships directly.

## Scope

### In scope

- Define a versioned schema for release evidence and a generator for canonical `RELEASE-EVIDENCE.json`.
- Render a human-readable `RELEASE-EVIDENCE.md` only from the canonical JSON; do not maintain two independently edited records.
- Collect source, workflow, candidate, consumer, release-asset, image, and SBOM evidence from the same release run.
- Give every candidate-consuming gate a machine-readable receipt identifying the run/attempt, source commit, candidate filename, and observed candidate SHA-256.
- Validate cross-artifact identity, including the downloaded Release JAR, `SHA256SUMS`, image-embedded JAR, and SBOM subject.
- Obtain final run conclusion and completion metadata from GitHub after the release workflow has completed; do not guess or record an in-progress timestamp as a completion time.
- Keep the Release in draft state until the finalizer has validated and attached the evidence report, then publish it.
- Add self-tests and CI checks that prove both valid evidence acceptance and planted mismatch rejection.
- Update the release runbook and Epic/DoD evidence instructions so release facts link to the generated report and originating run.

### Out of scope

- Changing, recreating, deleting, or republishing `v0.15.0` or any already-published release asset.
- Production deployment, host operations, or changing the JAR-based deployment contract.
- Rebuilding the application, changing Epic 21's candidate artifact design, or changing application behavior.
- Signing, SLSA attestations, new registries, third-party evidence services, or new Maven dependencies without separate owner approval.
- Configuring GitHub repository rulesets or asserting that remote tag-protection settings are enabled. The owner disposition requires verified repository controls to block future tag updates/deletions; that remains a separate prerequisite and must not be claimed from a workflow script alone.
- Changes to `url-shortener-web` or any other repository.

## Design decisions

1. **One canonical machine-readable record.** `RELEASE-EVIDENCE.json` is the source of truth. `RELEASE-EVIDENCE.md` and the Release body summary, if retained, are generated from it.
2. **Finalize after the originating workflow completes.** A workflow cannot truthfully record its own final conclusion or completion time while it is still running. Use a narrowly scoped `workflow_run` finalizer (or equivalent post-run mechanism) to read the completed originating run from GitHub. If authoritative completion data is unavailable, fail closed or omit the field; never infer it.
3. **Draft-first publication.** The release workflow creates a draft with the candidate assets. The finalizer verifies the completed run and actual draft assets, attaches the evidence report, and publishes the draft only after all checks pass. A finalizer failure leaves the release unpublished.
4. **No self-hash claim.** The report must not contain a digest of itself. GitHub's asset metadata or an external checksum may identify the report after upload; do not create a circular self-reference.
5. **Observed facts versus policy claims.** Record the current tag object's type and target commit. A successful check of the current ref does not prove that the ref was never moved historically. Historical immutability requires repository-level controls and independent verification.

## Core acceptance outcome

For every future release processed by the new flow:

- the exact completed run, tag, source commit, required job conclusions, candidate JAR, published JAR, image-embedded JAR, image identity, and SBOM subject are represented consistently in generated evidence;
- any missing, stale, cross-run, tampered, or contradictory input prevents publication;
- the evidence asset is attached to the same GitHub Release it describes;
- the human-authored DoD cites the report and exact run rather than manually duplicating mutable release facts.

No evidence is retroactively generated for, or used to mutate, `v0.15.0`.

## Quick traceability

| Story | Primary reference | Key outcome |
|---|---|---|
| 22.1 | Evidence schema + generator | One canonical, versioned report contract |
| 22.2 | `release.yml` + gate receipts | Same-run evidence from every candidate consumer |
| 22.3 | Post-run finalizer + GitHub Release | Completed-run and published-asset verification before publication |
| 22.4 | Runbook + DoD + CI | Human closure links to generated evidence; no manual retyping |

---

*Execute the stories in `epic-22-stories.md`, follow `epic-22-technical-tasks.md`, and record only observed results in `epic-22-dod.md`.*
