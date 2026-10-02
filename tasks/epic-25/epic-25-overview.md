# Epic 25: Production Metrics-Gated Blue-Green Canary

**Target repository:** `daniel-castilho/url-shortener-service` only  
**Status:** Draft for architecture review; implementation is not authorized by this draft.  
**Priority:** Proposed P1 — avoid promoting a canary based only on readiness and functional smoke checks.  
**Date:** 2026-10-02

## Goal

Provide an on-premises Prometheus runtime and use authenticated, per-color metrics to make a bounded promotion decision at each blue-green canary stage. The gate must fail safely when the metrics evidence is missing, stale, or insufficient; it must not mistake “no data” for a healthy result.

```text
new color receives a canary weight
  → readiness + functional smoke
  → wait for a defined, post-change observation window
  → query color-specific Prometheus metrics
  → promote / hold within a bounded deadline / restore the old color
```

The current deployment script already performs artifact validation, readiness checks, smoke checks, canary weight changes, and fail-closed rollback. This epic adds the missing metrics evidence and an operationally managed Prometheus service; it does not replace those existing controls.

## Evidence and current state

The read-only discovery on 2026-10-02 found:

- `scripts/deploy.sh` uses blue `127.0.0.1:8080`, green `127.0.0.1:8081`, default canary weights `10,30,100`, and `DWELL_SECONDS=30`. Automated decisions are currently readiness and smoke only.
- The service exports Micrometer metrics at `/actuator/prometheus`. The endpoint is protected by the existing Operator BasicAuth policy; anonymous access is rejected and an authorized operator scrape is tested.
- `deploy/monitoring/prometheus.yml` currently has one scrape job and one target (`URL_SHORTENER_HOST`, default `localhost:8080`). It does not distinguish blue from green. `docker-compose.yaml` does not provision Prometheus.
- `docs/slos.md`, `deploy/monitoring/recording-rules.yml`, and `deploy/monitoring/alerts.yml` provide SLO and alerting material, primarily for longer evaluation windows. They do not currently implement a per-stage canary gate.
- No Prometheus runtime, query API availability, retention, or production traffic volume has been confirmed for the deployment environment.
- Dargent is a read-only reference: its repository has an optional Compose `metrics` profile, separate Prometheus scrape jobs for blue and green, 15-second scraping, and `promtool` rule tests in CI. This is repository/configuration evidence, not evidence of a production Dargent Prometheus instance. Do not copy its Compose DNS or runtime assumptions into this bare-metal service.

## Scope

### In scope

1. Resolve and document the Prometheus operating model for this on-premises service.
2. Provision and operate a bounded Prometheus runtime on the approved deployment footprint, including service lifecycle, local-only/restricted query access, storage/retention limits, and health checks.
3. Configure authenticated scraping of both application colors with stable `color` labels; keep the metrics endpoint private and credentials out of command-line arguments and logs.
4. Add a tested metrics-gate component to the deployment flow. Evaluate only data attributable to the current canary stage, enforce freshness and minimum-volume requirements, and prevent promotion on absent/invalid evidence.
5. Preserve and reuse the service's existing Micrometer/Spring HTTP metrics and reviewed SLO contract where suitable. No new application meter is assumed necessary; confirm during implementation.
6. Update the service-specific runbook, tests, CI validation, and operational evidence requirements.

### Out of scope unless separately approved

- Changes to Dargent or any other repository.
- Frontend work.
- Publicly exposing Prometheus, the Prometheus query API, or application metrics.
- Grafana dashboards, external paging, SaaS telemetry, or a general-purpose cross-service monitoring platform.
- Changing application business behavior or adding meters without a demonstrated need and approval.
- Production/UAT/staging operation by the Software Engineer as part of code implementation. Environment access and deployment require separate authorization.

## Story plan

- **S0 — Architecture and operating-model decision (blocking):** choose how Prometheus is installed and managed, where it stores data, how its query API is protected, how scrape credentials are supplied, and how missing monitoring affects deployment. No implementation before S0 is approved.
- **S1 — Prometheus runtime and per-color scrape:** provision the selected runtime and configure authenticated blue/green targets with bounded storage and health checks.
- **S2 — Canary metrics gate:** add per-stage queries, observation/freshness/minimum-volume rules, bounded hold/abort behavior, and wire the gate into `deploy.sh` before advancing or draining the old color.
- **S3 — Operational documentation and verification:** test rules and gate behavior, update the runbook, and complete an authorized non-production rehearsal with evidence.

## Architecture decisions required before implementation

The choices below are intentionally open. The architect must approve them in `epic-25-stories.md` or a linked ADR before implementation stories are marked ready.

| ID | Decision | Current evidence / constraint |
|---|---|---|
| D1 | Prometheus packaging and lifecycle: Compose container, systemd-managed binary, or an already-managed external service | No runtime is confirmed; this service is bare-metal/systemd for the app and uses Docker Compose for backing services. |
| D2 | Storage path, retention period, size cap, restart behavior, and backup expectations | Must be bounded and appropriate for the actual host; currently unverified. |
| D3 | Query API binding/network boundary and credentials | Prefer loopback/restricted access; never expose the query API publicly. |
| D4 | Scrape authentication and secret delivery for `/actuator/prometheus` | Existing Operator BasicAuth works in tests; production secret location and rotation are unverified. |
| D5 | Gate signals and thresholds | Candidate signals: `up`, HTTP 5xx ratio, HTTP latency bucket ratio at `le="0.2"`, optional redirect p95/p99, and traffic volume. Thresholds are not approved. |
| D6 | Observation window, scrape freshness, and minimum request volume per stage | Scrape interval is 15 seconds and current dwell is 30 seconds (~2 scrape opportunities). This is not sufficient evidence by itself for a reliable statistical decision. |
| D7 | Missing, stale, NaN, zero-traffic, or query-error behavior | Must never count as healthy. Decide bounded hold behavior and final fail/rollback behavior. |
| D8 | Per-stage failure semantics and old-color drain timing | Decide whether a failed gate immediately aborts to old color and how long the old color remains available after the 100% step. |
| D9 | Emergency override policy | Decide whether a bypass is permitted. Default recommendation: no silent bypass; any override must be explicit, authenticated, logged, and reviewed. |

## Recommended decision principles (not yet approved policy)

- A query error, missing series, stale sample, `NaN`, insufficient request volume, or Prometheus outage is **not a pass**.
- The observation period must begin after the weight change; do not let pre-canary traffic satisfy the current stage's sample floor.
- Use short-window queries appropriate for a rollout stage, not the existing 30-day recording rules as the sole promotion gate.
- Keep the previous color available until the final-stage gate passes.
- A bounded “hold and retry” may gather evidence; the maximum hold and terminal action must be explicit and tested.
- Keep the runtime and credentials private to the host/monitoring network.

## Definition of epic success

The epic is complete only when the selected Prometheus runtime is documented and operationally managed, both colors are scraped with authentication and explicit labels, the deploy gate can prove its decisions from fresh per-color samples, all no-data/error/low-volume paths fail safely, CI tests the contracts, and an authorized non-production rehearsal is recorded. A green unit test alone is not evidence that Prometheus is running in a deployment environment.

---

*Draft prepared for architecture review. Do not implement, deploy, or infer approval of unresolved decisions from this document.*
