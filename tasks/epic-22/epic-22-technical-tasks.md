# Epic 22 — Technical Tasks

Checkboxes are marked only during implementation. Paste real command/API outputs and stable links in `epic-22-dod.md`; never pre-fill results.

## 22.1 Evidence schema and generator

- [ ] At implementation kickoff, inspect the current target branch and reconcile it with the owner-reported Epic 21 closure. Confirm the annotated-tag verifier, same-run candidate flow, provenance format, checksum behavior, and current workflow names before designing against them. Stop and report if the branch is stale or contradicts the approved Epic 21 contract.
- [ ] Define a versioned schema for `RELEASE-EVIDENCE.json`. Include at minimum:
  - repository, tag, semantic version, tag object type, tag target/source commit;
  - originating workflow name/path, run ID, run number, attempt, event, URL, final conclusion, and authoritative completion time (UTC);
  - each required job name and conclusion;
  - candidate JAR filename and SHA-256;
  - receipts from each consumer, including its observed JAR SHA-256;
  - published JAR/checksum/provenance asset names and hashes;
  - image ID/digest, image-embedded JAR SHA-256, and SBOM filename/hash/subject identity;
  - schema version and evidence-generation workflow identity.
- [ ] Mark fields as required or optional explicitly. Never manufacture unavailable fields. Do not treat the report's generation timestamp as the originating workflow's completion timestamp.
- [ ] Implement a generator/validator that consumes structured workflow/API/artifact inputs. Avoid extracting authoritative fields from release-note prose.
- [ ] Produce `RELEASE-EVIDENCE.md` from the canonical JSON with a deterministic renderer. State in the generated report that it is derived from the JSON.
- [ ] Validate the schema and use a stable JSON serialization; do not include a self-hash in the JSON.
- [ ] Keep parsing and validation local/testable without requiring repository secrets or live GitHub writes.

**Done when:** a valid fixture generates schema-valid JSON and matching Markdown, while missing/invalid fields fail with a named error.

## 22.2 Receipts and cross-artifact validation

- [ ] In the release workflow, make the candidate producer emit a machine-readable receipt from the actual candidate bytes and verified provenance/checksum.
- [ ] In each required candidate consumer, emit a receipt only after downloading and validating the exact candidate. Record the hash actually checked immediately before the test/packaging action.
- [ ] Bind every receipt to `GITHUB_REPOSITORY`, tag/ref, full source commit, `GITHUB_RUN_ID`, `GITHUB_RUN_ATTEMPT`, and candidate filename. Do not accept receipts from a different run or attempt.
- [ ] Upload receipts with unambiguous artifact names and retention appropriate to release evidence. The finalizer must select artifacts by the originating run ID, never by "latest".
- [ ] Validate that the candidate hash agrees across producer, k6, runtime-smoke, restore-drill, and release receipts.
- [ ] Download the actual draft Release JAR and checksum/provenance assets via the GitHub API and verify their bytes. Require `SHA256SUMS` to pass `sha256sum -c` and its JAR entry to match the candidate receipt.
- [ ] Validate the image-embedded JAR hash against the candidate. Parse the SBOM structurally and verify its subject/image identity and reported revision according to the actual CycloneDX schema used by the repository.
- [ ] Verify that the current tag is annotated and resolves to the originating full source commit. Label this as a current-ref check; do not claim it proves historical immutability.
- [ ] Fail closed on missing, ambiguous, malformed, stale, duplicate, cross-run, or contradictory evidence. There is no rebuild, hash substitution, or soft-warning fallback.

**Done when:** a complete set of same-run receipts and actual Release assets agrees byte-for-byte and structurally; a planted mismatch blocks finalization.

## 22.3 Post-run finalizer and publication boundary

- [ ] Implement a `workflow_run` finalizer (or equivalent post-completion mechanism) restricted to the expected release workflow, same repository, approved tag-push event, and completed successful run.
- [ ] Validate the triggering payload and query GitHub for the exact originating run, attempt, jobs, artifacts, tag ref, and draft Release. Never identify the source by the finalizer workflow's `GITHUB_SHA`.
- [ ] Obtain conclusion and completion timestamp from the completed originating run's authoritative GitHub API data. Record the exact source field and UTC representation. If GitHub does not provide a trustworthy completion value, fail or omit it rather than infer from `updated_at`, a log line, or the finalizer start time without a documented API contract.
- [ ] Ensure the release job creates a draft Release with the verified candidate JAR, `SHA256SUMS`, provenance, and SBOM; no public Release is visible before finalization succeeds.
- [ ] Have the finalizer re-download and check draft assets, generate the final JSON/Markdown report, upload both as Release assets, and publish the draft as the last action.
- [ ] If any step fails, leave the Release in draft state, emit an actionable failure, and do not delete, recreate, or retag it automatically.
- [ ] Grant only `actions: read` and the minimum `contents: write` needed by the finalizer. Keep the build/test workflow read-only where possible. Do not execute untrusted PR code in this workflow.
- [ ] Add a dry-run mode or fixture-backed test path that exercises finalizer validation without publishing or mutating a real Release.
- [ ] Document the separate GitHub ruleset requirement. Do not implement remote repository settings or claim they are verified as part of this code-only work.

**Done when:** a successful originating run can be finalized and published only with its validated report; a failed run or invalid finalizer input cannot publish the draft.

## 22.4 Documentation and closure

- [ ] Update `docs/release-runbook.md` to explain the report, its authoritative sources, how to verify it, draft publication, finalizer failures, and how to locate the originating run.
- [ ] Update the DoD/evidence template so release-specific facts are cited by the generated evidence asset and workflow URL instead of manually transcribed.
- [ ] Preserve Epic 21's owner disposition and historical record exactly; do not revise its waived tag criteria as passed.
- [ ] Update `CHANGELOG.md` and directly affected project documentation per `AGENTS.md`.
- [ ] Keep all Epic 22 repository content in English and backend-repository-specific.
- [ ] Record a traceability matrix from each Epic 22 criterion to a script/workflow gate, negative test, and generated evidence field.

## 22.5 Final gates

- [ ] Shell/Python syntax and schema validation pass.
- [ ] Generator, receipt validator, and finalizer self-tests pass, including all negative cases in `epic-22-testing.md`.
- [ ] Existing CI gates remain enabled and green; no `continue-on-error`, skipped gate, or weakened threshold is introduced.
- [ ] Full relevant project verification is green on the exact implementation commit.
- [ ] Release-workflow validation proves draft-first behavior and that the report attaches to the matching tag/run only.
- [ ] No real future tag or public release is created solely for this epic without explicit owner authorization.
- [ ] Paste final commit, test outputs, workflow links, and changed paths into the DoD. Leave unknown evidence as pending; do not infer it.
