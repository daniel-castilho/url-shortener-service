# Epic 23 — Technical Tasks

Checkboxes are marked only during implementation. Do not pre-mark tasks or claim a gate is active before the workflow proves it.

## 23.1 Inspect and define the impact map

- [ ] At kickoff, inspect the current target branch, `AGENTS.md`, `scripts/check-doc-sync.sh`, `.github/workflows/ci.yml`, and the actual source/document tree. Reconcile with the owner's current Epic 22 completion state; do not rely on a stale local snapshot.
- [ ] Confirm the four initial areas against real paths:
  - REST controllers, request/response DTOs, and OpenAPI-facing behavior;
  - `application*.yaml`, environment examples, and configuration/property classes;
  - Mongo entities, collection/index configuration, and migration sources;
  - release workflow, release verifier/scripts, and release Docker packaging.
- [ ] Create a structured map (for example, `docs/documentation-impact-map.yml`) with rule ID, source globs, required target groups, and rationale where needed.
- [ ] Use exact existing documentation paths where appropriate. Create a new documentation target only if it is independently justified; do not require documents to be touched merely to satisfy the gate.
- [ ] Define whether each rule requires all target groups or at least one alternative document in a group. Keep alternatives explicit and test them.
- [ ] Add a map validator for duplicate IDs, malformed/empty patterns, missing required fields, target-path validity, and source-root coverage.
- [ ] Ensure deleted and renamed source files trigger their former path's rule. Define deterministic behavior for files matched by more than one rule.

**Done when:** the map is complete for the four agreed areas, self-validating, and its output can explain each match.

## 23.2 Implement the changed-path checker

- [ ] Add a script such as `scripts/check-doc-impact.sh` with explicit base/head arguments and a local usage example.
- [ ] Compute changed paths using Git safely (including renames/deletions and filenames containing spaces); do not parse a newline-delimited path list unsafely.
- [ ] Evaluate the map and report: source files matched, rule IDs, required documentation groups, changed target documents, rationale source, and final decision.
- [ ] Require at least one satisfying document target for each required target group, unless a well-formed no-impact rationale is supplied.
- [ ] Define a stable no-impact declaration contract: `Docs-Impact: none - <specific reason>`. Support PR-body input for pull-request events and a commit-message trailer for push events, without requiring PR-only development.
- [ ] Validate the reason has meaningful content; do not accept generic values such as `none`, `N/A`, `not needed`, or whitespace alone.
- [ ] Treat the no-impact declaration as a reviewer-visible exception. The script must not output that documentation was updated or that semantic review passed.
- [ ] Handle no relevant path changes and docs-only changes with a clear PASS/no-op result.
- [ ] Fail or explicitly classify source paths under the four in-scope roots that do not match any map rule. Do not report them as silently covered.
- [ ] Keep the checker offline and deterministic; it must not require GitHub credentials.

**Done when:** local invocations against synthetic base/head trees produce stable and explainable outcomes.

## 23.3 Add self-tests and CI integration

- [ ] Add `--self-test` using temporary Git repositories/files and temporary map fixtures; never mutate the developer's real working tree.
- [ ] Include tests for missing documentation, valid target documentation, valid no-impact rationale, invalid/no-reason rationale, docs-only changes, deletion/rename, multiple mapped rules, unknown in-scope source path, and uncovered required map root.
- [ ] Test both PR-body and commit-trailer rationale extraction using representative GitHub event payload fixtures.
- [ ] Determine PR base/head and push before/after SHAs from the event payload; fetch the required base commit if checkout depth makes it unavailable.
- [ ] Define behavior for a push event with an all-zero `before` SHA and for an empty diff. Do not substitute an arbitrary commit range.
- [ ] Add normal and self-test steps to `.github/workflows/ci.yml` for both current PR and push triggers.
- [ ] Preserve least privilege and existing job dependencies. Do not use a PR-only check as a substitute for push coverage.
- [ ] Confirm a failing impact check blocks the relevant CI job and is visible in the Actions summary.

**Done when:** CI exercises the normal gate and its negative/positive self-tests, with no bypass or continue-on-error.

## 23.4 Update policy documentation

- [ ] Update `AGENTS.md` to make the impact map and checker part of the documentation DoD.
- [ ] Document how to run `bash scripts/check-doc-impact.sh --base <sha> --head <sha>` and `--self-test`.
- [ ] Document the `Docs-Impact: none - <reason>` syntax for PR-body and commit-trailer contexts, and state that a human reviewer adjudicates the reason.
- [ ] State that path coverage does not establish semantic correctness; reviewers still verify the updated document against code/config/tests.
- [ ] Update `CHANGELOG.md` and directly affected contributor/runbook documentation as required by existing repository policy.
- [ ] Do not duplicate the path-to-document rules outside the map unless the duplicate is generated from it.
- [ ] Do not alter Dargent-specific rules or apply them to another repository.

## 23.5 Final gates

- [ ] `bash -n scripts/check-doc-impact.sh` passes.
- [ ] The checker and `--self-test` pass locally and in CI.
- [ ] Existing `check-doc-sync.sh` and its self-test remain green.
- [ ] Existing living-spec, boundaries, security, metrics-frozen, release-evidence, and CHANGELOG gates remain enabled and green as applicable.
- [ ] Full relevant project verification passes on the exact implementation commit.
- [ ] No docs are modified only to make a path check green; each change is reviewed for factual accuracy.
- [ ] Paste actual changed paths, commands, outputs, and CI run references into `epic-23-dod.md`.
