# Epic 23 — Testing and Verification Plan

**Objective:** prove that the change-impact gate detects missing documentation for mapped source changes, accepts valid documentation or explicit no-impact rationale, and reports its limits accurately.

## 1. Impact-map validation tests

| Case | Expected result |
|---|---|
| Valid map with four in-scope areas and existing target groups | Accepted; all required source roots reported covered |
| Duplicate rule ID | Rejected with the duplicate ID identified |
| Missing source pattern, empty document group, or malformed rule | Rejected with a precise schema/configuration error |
| Required documentation target is misspelled or disallowed | Rejected before evaluating changed files |
| A required API/config/data/release source root has no map rule | Rejected; no silent coverage gap |
| Alternative document group includes an allowed path | Accepted and printed as an alternative |

## 2. Changed-path behavior tests

| Changed files / declaration | Expected result |
|---|---|
| API controller or DTO changes with a matching API document changed | PASS; report the matching API rule and document |
| API source changes without a matching document or rationale | FAIL; name the source rule and missing document group |
| Configuration source changes with a matching configuration guide/example updated | PASS |
| Mongo migration/entity changes without data-model/migration documentation or rationale | FAIL |
| Release workflow/verifier changes without a release document or rationale | FAIL |
| Mapped source changes with valid `Docs-Impact: none - <specific reason>` | PASS as an explicit no-impact exception; print the reason and do not call it documentation updated |
| Mapped source changes with empty/generic/malformed rationale | FAIL |
| Documentation-only change | PASS/no-op with clear output |
| Unrelated source outside initial map areas | Behaves according to the explicitly documented unmapped-path policy; never claims semantic coverage |
| File rename or deletion from a mapped source area | Old and new paths are accounted for and the mapping rule remains visible |
| One change triggers multiple map rules | Every triggered rule is evaluated; satisfying one rule cannot hide a missing target for another |
| Path contains spaces or unusual characters | Correctly handled without splitting or dropping the path |

## 3. Rationale-source tests

- [ ] Pull-request fixture with a valid rationale in the PR body is recognized.
- [ ] Pull-request fixture with no rationale and missing docs fails.
- [ ] Push fixture with a valid commit-message trailer is recognized.
- [ ] Push fixture with no trailer and missing docs fails.
- [ ] A rationale in an unrelated comment/commit is not accidentally accepted if the contract requires a specific field or trailer.
- [ ] A no-impact reason is printed exactly and remains reviewable; the script does not assert that the reason is true.
- [ ] Initial push/all-zero `before` SHA and empty-diff cases produce the documented safe result rather than an arbitrary comparison.

## 4. Self-test integrity

- [ ] `bash scripts/check-doc-impact.sh --self-test` plants violations in a temporary fixture and proves the checker rejects them.
- [ ] The self-test proves compliant documentation and valid exception paths pass.
- [ ] The self-test proves the map validator catches uncovered in-scope source roots.
- [ ] Self-tests do not modify tracked files, the user's real branch, or existing `tasks/` evidence.
- [ ] The normal gate and self-test both exit non-zero when their corresponding failure is planted.

## 5. CI integration verification

- [ ] Pull-request CI supplies the correct base/head range and checks PR-body rationale where needed.
- [ ] Push CI supplies the event's before/after range and checks commit-trailer rationale where needed.
- [ ] A synthetic failing fixture makes the CI job fail; a clean fixture passes.
- [ ] CI output names affected rule IDs, changed source paths, expected documentation targets, changed targets, and any no-impact declaration.
- [ ] The gate is not skipped, advisory, or `continue-on-error` on either event type.
- [ ] Existing documentation-sync and other release/readiness gates remain unchanged and green.

## 6. Evidence quality and limitations

- Every cited command, path, test output, and CI result must come from the implementation commit/run being closed.
- A passing gate proves only that the configured path/documentation condition or explicit rationale was met. It does not prove that the prose is semantically complete or correct.
- A no-impact declaration is a human-review item, not an automatic waiver of the repository's documentation policy.
- Static inspection of the map alone is not proof the CI workflow invokes it.
