# Epic 23: Documentation Change-Impact Gate

**Project:** `url-shortener-service` only<br>
**Priority:** P1 — prevent documentation drift on behavior/configuration changes<br>
**Status:** Specification for implementation; this document changes no code or CI configuration.<br>
**Dependency:** Epic 22 is reported complete. Re-read the current target branch and existing documentation gates before implementation; do not assume this planning workspace is the latest checkout.

## Goal

Make documentation impact visible when changes touch high-value behavior, configuration, data-model, or release-process paths. CI will compare changed paths against a small, explicit impact map. For each mapped source change, contributors must either update the linked documentation or provide a specific, reviewable no-documentation-impact rationale.

The gate complements—not replaces—human review, executable tests, living specifications, release evidence, or the existing `check-doc-sync.sh` structural checks. It cannot decide semantic truth by itself.

```text
changed source/config paths
  → documentation-impact map
  → required documentation paths OR explicit no-impact rationale
  → CI report with matched rules and evidence
  → reviewer can see and adjudicate the claimed impact
```

## Why this epic now?

The repository already makes documentation synchronization part of its contributor rules and has CI checks for documentation consistency. Those checks are useful but narrow: a structural documentation gate cannot infer that a changed API, environment variable, migration, or release workflow has made an operator-facing document stale.

Epic 22 addresses release-specific evidence through machine-generated reports. Epic 23 addresses the broader change path: when source behavior or configuration changes, the expected documentation review should not depend only on contributor memory.

The design adapts Dargent's documentation-as-part-of-DoD and traceability practices. It does not copy Dargent's repository-specific rules or treat an automated path match as proof that prose is correct.

## Scope

### In scope

- Add a versioned, reviewable documentation-impact map for four initial areas:
  1. HTTP API routes, request/response DTOs, and API-facing behavior;
  2. runtime configuration, environment variables, and configuration properties;
  3. MongoDB entities, indexes, collections, and migrations;
  4. release workflow, artifact verification, Docker release packaging, and release runbooks.
- Add a deterministic checker that receives an explicit base and head commit and evaluates changed paths against the map.
- Require mapped documentation changes or a well-formed `Docs-Impact: none - <reason>` declaration when the author believes no documentation change is warranted.
- Support both pull-request and push CI contexts; do not make the gate depend on a PR-only development process.
- Add self-tests proving that missing documentation is detected, matching documentation is accepted, and a valid no-impact rationale is reported distinctly.
- Integrate the checker into CI without weakening existing gates.
- Update `AGENTS.md`/documentation guidance and `CHANGELOG.md` as required by current repository policy.

### Out of scope

- Automatically deciding whether a behavior change is semantically documented correctly; human review and tests remain necessary.
- Requiring every code change to edit documentation, or touching documents only to satisfy a mechanical rule.
- Replacing `scripts/check-doc-sync.sh`, the living-spec gate, or Epic 22's release evidence generator.
- Automatically generating API, architecture, migration, or runbook prose in this epic.
- Changing application behavior, database schema, release workflow behavior, deployment, or GitHub repository settings.
- Changes to `url-shortener-web` or any other repository.

## Design decisions

1. **Explicit map, not hard-coded prose scattered through the script.** Store source globs, rule IDs, and acceptable documentation targets in a small configuration file such as `docs/documentation-impact-map.yml`; validate its structure and coverage.
2. **Changed-file gate, not a stale-date gate.** Do not add generic `Last reviewed` dates that decay without proving accuracy. Evaluate documentation when related source paths change.
3. **No-impact declarations are visible, not magical approval.** A declaration explains why no doc changed; it remains subject to normal human review and does not mark documentation as updated.
4. **No PR-only assumption.** For PR runs, the checker may read the PR body; for push runs, it must support a commit-message trailer. A documented direct-push policy must not be silently replaced by a mandatory PR rule.
5. **Narrow initial ratchet.** Start with the four high-impact areas above. Report unmatched files in those areas and fail if an in-scope source root has no map rule. Expand coverage deliberately rather than claiming repository-wide semantic detection.
6. **Preserve the existing sync gate.** Keep `check-doc-sync.sh` for its existing contracts; add the change-impact check as a separate, testable gate unless inspection shows a safe and clear integration point.

## Initial impact-map intent

The final globs and document paths must be verified against the current tree during implementation. Initial candidates include:

| Change area | Candidate source paths | Candidate documentation targets |
|---|---|---|
| API contract | REST controllers, REST DTOs, OpenAPI annotations/config | `README.md`, `docs/design.md`, or a dedicated API reference where appropriate |
| Configuration | `application*.yaml`, `.env.example`, configuration/property classes | `README.md`, `docs/release-runbook.md`, or the relevant operations/configuration guide |
| Persistence | Mongo entities, collection/index definitions, schema migrations | `MONGODB_ARCHITECTURE.md`, `docs/data-model-decisions.md`, or migration documentation |
| Release | `.github/workflows/release.yml`, release verifier/scripts, `Dockerfile.release` | `docs/release-runbook.md`, `docs/release-engineering.md`, and release-evidence documentation where affected |

A map rule may define multiple documentation groups. The checker must make its requirement precise (for example, at least one acceptable target in a group), explain the matching rule in its output, and never silently interpret an unmapped source change as compliant.

## Success outcome

For the four mapped areas, CI reports which rules were triggered, which documentation paths changed, and whether a no-impact rationale was used. Missing expected documentation without a rationale fails the gate. A reviewer can audit the mapping and rationale without inferring the behavior from a generic green status.

---

*Execute the stories in `epic-23-stories.md`, follow `epic-23-technical-tasks.md`, and record only observed implementation/test results in `epic-23-dod.md`.*
