# Epic 25 Definition of Done: Production Metrics-Gated Blue-Green Canary

**Target repository:** `daniel-castilho/url-shortener-service` only  
**Status:** Draft checklist; do not mark complete until every applicable item has evidence.

## Architecture and operations

- [ ] S0 decisions D1–D9 are approved and recorded; no implementation policy was inferred from Dargent.
- [ ] The Prometheus runtime owner, installation model, service lifecycle, storage/retention limits, health check, and recovery procedure are documented.
- [ ] Evidence confirms the approved Prometheus runtime and query API are available in the authorized non-production environment. Repository configuration alone is not runtime evidence.
- [ ] The query API and metrics endpoint are not publicly exposed; scrape and query credentials use the approved least-privilege secret mechanism.
- [ ] Blue and green are independently scraped and identified; a stopped or failing color cannot be mistaken for a healthy one.

## Gate correctness and safety

- [ ] Every stage uses fresh, post-weight-change, color-specific samples and meets the approved minimum-volume policy.
- [ ] No-data, stale, low-volume, malformed, `NaN`, timeout, authentication, query, or Prometheus-availability failures cannot produce a passing decision.
- [ ] Thresholds, windows, hold deadline, retry behavior, bypass policy, and final old-color drain timing match the approved decision record.
- [ ] A passing stage alone permits promotion. A failed/indeterminate stage follows the approved safe rollback behavior.
- [ ] The final 100% stage gate passes before the prior color is drained.
- [ ] Artifact validation, same-validated-JAR installation, readiness, smoke checks, and existing rollback behavior remain intact.

## Tests, docs, and evidence

- [ ] Unit/self-tests cover every row in `epic-25-testing.md` that applies to the selected policy.
- [ ] Pinned `promtool` checks and rule tests pass in CI; gate and deployment tests are wired into the appropriate CI workflow.
- [ ] Integration tests prove color isolation and query behavior without contacting a deployment host.
- [ ] `docs/release-runbook.md`, `docs/observability.md`, and `docs/slos.md` accurately describe the implemented runtime and gate; no stale or aspirational operational claims remain.
- [ ] Relevant documentation-impact, documentation-sync, metric-freeze, shell, Maven, and workflow checks pass; exact commands and evidence are recorded.
- [ ] An authorized UAT/staging rehearsal records the exact script SHA, release tag, per-stage metrics evidence, health/smoke results, rollback outcome, operator, and timestamp.
- [ ] Production readiness is reviewed separately. Completion of this epic does not itself authorize a production deployment.
