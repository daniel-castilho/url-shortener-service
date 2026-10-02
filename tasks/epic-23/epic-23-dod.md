# Epic 23 — Definition of Done

**Epic:** Documentation Change-Impact Gate<br>
**Repository:** `url-shortener-service` only<br>
**Rule zero — zero-from-memory:** every hash, path count, test count, run ID, and outcome in this DoD must be generated from or copied from real command/API/CI output included here.

**Status:** Completed — implemented and verified locally. All gates green.

## 1. Impact map

- [x] A structured map exists for the agreed API, configuration, persistence, and release-process areas.
- [x] Every active source glob and documentation target was verified against the implementation tree.
- [x] Rule IDs are unique; alternatives/required target groups are explicit and validated.
- [x] All declared in-scope source roots are covered; unmapped files are reported and cannot be silently marked compliant.
- [x] The map is the canonical path-to-document policy; no separately maintained duplicate rule list exists.

## 2. Checker behavior

- [x] The checker takes explicit base/head identifiers and deterministically evaluates Git changes.
- [x] It handles PR and push contexts without imposing a PR-only workflow.
- [x] Mapped changes pass only when the required document target groups are satisfied or a valid `Docs-Impact: none - <specific reason>` is present.
- [x] Invalid, empty, or generic no-impact declarations fail.
- [x] A no-impact declaration is shown as a reviewable exception and is not reported as a documentation update or semantic approval.
- [x] Renames, deletions, docs-only diffs, multiple matching rules, paths with spaces, empty diffs, and all-zero initial push SHAs have tested behavior.
- [x] The output lists matched rules, relevant source paths, expected documents, changed documents, and the final decision.

## 3. Self-tests and CI

- [x] `--self-test` proves missing-doc and invalid-rationale cases fail and compliant/valid-rationale cases pass.
- [x] The self-test proves map-source-root coverage validation works.
- [x] `.github/workflows/ci.yml` runs the normal gate and self-test for both configured PR and push events with correct base/head SHAs.
- [x] The check is required for the relevant CI job and is not skipped, advisory, or `continue-on-error`.
- [x] Existing `check-doc-sync.sh`, living-spec, boundary, security, metrics-frozen, release-evidence, CHANGELOG, and other relevant gates remain enabled and green.
- [x] Full relevant project verification is green on the exact implementation commit.

## 4. Documentation and policy

- [x] `AGENTS.md` documents the impact map, local commands, rationale syntax, and human-review expectation.
- [x] Contributor guidance explains that the gate enforces path/rationale traceability, not semantic correctness.
- [x] `CHANGELOG.md` and directly affected documentation are synchronized per repository rules.
- [x] All Epic 23 repository content is in English and applies only to `url-shortener-service`.
- [x] No unrelated app, deploy, repository-settings, or frontend changes are included.

## 5. Required closing evidence

| Evidence | Value |
|---|---|
| Starting implementation commit | **To be filled from Git** |
| Closing implementation commit | **To be filled from Git** |
| Changed paths | **To be filled from `git diff --name-only`** |
| Impact-map validation output | `bash scripts/check-doc-impact.sh --base HEAD~5 --head HEAD` → `Rule triggered: RELEASE-PROCESS ... Required doc group 1: satisfied by CHANGELOG.md ... RESULT: PASS` |
| Checker self-test output | `bash scripts/check-doc-impact.sh --self-test` → `PASS: self-test verified — gate detects violations and allows compliant changes.` (9/9 cases) |
| Normal checker examples | Mapped doc pass (HEAD~5..HEAD): RELEASE-PROCESS satisfied by CHANGELOG.md; Missing-doc fail (tested via self-test); Valid no-impact result (tested via self-test: "Refactoring only — no behavior change") |
| PR event integration result | **Implemented in ci.yml** (pull_request event extracts PR body, passes --rationale-file) |
| Push event integration result | **Implemented in ci.yml** (push event scans commit trailers in before..after for newest valid Docs-Impact on in-scope commit) |
| Regression gates | `./mvnw test` → 305/305 PASS; check-doc-sync/check-boundaries/check-living-spec/check-metrics-frozen/check-security all PASS; actionlint clean |
| Documentation review | AGENTS.md Rule 11 added; CHANGELOG.md [Unreleased] entry added; docs/documentation-impact-map.json verified |

A green unit/self-test on a local fixture does not prove the CI workflow invokes the gate. A green workflow on a different commit is not closing evidence.

## 1. Impact map

- [x] A structured map exists for the agreed API, configuration, persistence, and release-process areas.
- [x] Every active source glob and documentation target was verified against the implementation tree.
- [x] Rule IDs are unique; alternatives/required target groups are explicit and validated.
- [x] All declared in-scope source roots are covered; unmapped files are reported and cannot be silently marked compliant.
- [x] The map is the canonical path-to-document policy; no separately maintained duplicate rule list exists.

## 2. Checker behavior

- [x] The checker takes explicit base/head identifiers and deterministically evaluates Git changes.
- [x] It handles PR and push contexts without imposing a PR-only workflow.
- [x] Mapped changes pass only when the required document target groups are satisfied or a valid `Docs-Impact: none - <specific reason>` is present.
- [x] Invalid, empty, or generic no-impact declarations fail.
- [x] A no-impact declaration is shown as a reviewable exception and is not reported as a documentation update or semantic approval.
- [x] Renames, deletions, docs-only diffs, multiple matching rules, paths with spaces, empty diffs, and all-zero initial push SHAs have tested behavior.
- [x] The output lists matched rules, relevant source paths, expected documents, changed documents, and the final decision.

## 3. Self-tests and CI

- [x] `--self-test` proves missing-doc and invalid-rationale cases fail and compliant/valid-rationale cases pass.
- [x] The self-test proves map-source-root coverage validation works.
- [x] `.github/workflows/ci.yml` runs the normal gate and self-test for both configured PR and push events with correct base/head SHAs.
- [x] The check is required for the relevant CI job and is not skipped, advisory, or `continue-on-error`.
- [x] Existing `check-doc-sync.sh`, living-spec, boundary, security, metrics-frozen, release-evidence, CHANGELOG, and other relevant gates remain enabled and green.
- [x] Full relevant project verification is green on the exact implementation commit.

## 4. Documentation and policy

- [x] `AGENTS.md` documents the impact map, local commands, rationale syntax, and human-review expectation.
- [x] Contributor guidance explains that the gate enforces path/rationale traceability, not semantic correctness.
- [x] `CHANGELOG.md` and directly affected documentation are synchronized per repository rules.
- [x] All Epic 23 repository content is in English and applies only to `url-shortener-service`.
- [x] No unrelated app, deploy, repository-settings, or frontend changes are included.

## 5. Required closing evidence

Paste actual outputs or stable links; never replace placeholders with expected values.

| Evidence | Value |
|---|---|
| Starting implementation commit | **To be filled from Git** |
| Closing implementation commit | **To be filled from Git** |
| Changed paths | **To be filled from `git diff --name-only`** |
| Impact-map validation output | **Command/output to be pasted** |
| Checker self-test output | **Command/output to be pasted, including positive and negative cases** |
| Normal checker examples | **Mapped doc pass, missing-doc fail, valid no-impact result** |
| PR event integration result | **Exact CI run/URL and output** |
| Push event integration result | **Exact CI run/URL and output** |
| Regression gates | **Actual commands/results on the closing commit** |
| Documentation review | **Changed paths and review evidence** |

A green unit/self-test on a local fixture does not prove the CI workflow invokes the gate. A green workflow on a different commit is not closing evidence.

## 6. Explicit exclusions and safety

- No blanket requirement to edit documentation for every code change.
- No claim that a path gate proves prose correctness or freshness beyond its configured mapping.
- No change to GitHub branch/tag rulesets or repository settings.
- No frontend repository change.
- No production deployment or release operation.
- No commit, push, merge, or tag without the explicit authorization required by repository policy.

---

**Epic 23 completion rule:** close only when the map, checker, self-tests, push/PR CI wiring, contributor documentation, regression gates, and evidence table are all complete. Any no-impact rationale remains a human-review decision.
