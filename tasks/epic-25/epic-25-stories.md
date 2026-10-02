# Epic 25 Stories: Production Metrics-Gated Blue-Green Canary

**Target repository:** `daniel-castilho/url-shortener-service` only  
**Status:** Draft. S0 is required before implementation; S1–S3 remain blocked by its decisions.

## S0 — Approve Prometheus operating model and gate policy (architecture prerequisite)

**Owner:** System Architect / environment owner  
**Implementation:** None. This is a decision record, not permission to access or modify a host.

### Decisions to record

Resolve D1–D9 in `epic-25-overview.md`:

- Prometheus packaging, host/service lifecycle, and whether it is managed by this repository or another owner.
- Storage location, retention, resource bounds, health monitoring, restart, and backup expectations.
- Query API network boundary and how the deployment script authenticates to it.
- Scrape authentication for blue and green application endpoints and secret delivery/rotation.
- Gate metrics, thresholds, sample floor, observation window, freshness rule, and how low traffic is handled.
- Behavior on missing/stale/error/NaN data, Prometheus outage, and timeout.
- Whether a failed step holds for bounded retries or aborts immediately; when the old color may be drained.
- Whether any emergency bypass exists and its approval/audit controls.

### Acceptance criteria

1. Each decision has an approved value or an explicit “deferred / blocks implementation” disposition.
2. The selected Prometheus host/service and network assumptions are evidenced by an authorized owner; repository config alone is not accepted as proof of a running service.
3. Gate thresholds and windows are tied to the approved SLOs and actual traffic evidence, not copied from Dargent or selected solely from synthetic k6 results.
4. The policy explicitly states that missing, stale, malformed, zero-volume, or query-error data cannot promote a canary.
5. No source code, CI, Prometheus runtime, or remote environment is changed as part of S0.

## S1 — Provision and secure Prometheus with per-color scraping

**Blocked by:** S0 decisions D1–D4 and D7.  
**Goal:** provide an approved, bounded Prometheus runtime and collect the two application colors independently.

### Acceptance criteria

1. The chosen runtime starts reliably under the approved host/service manager and survives the defined restart behavior.
2. Prometheus storage has the approved retention and disk/resource cap; health and failure behavior are documented.
3. Scrape configuration defines distinct blue and green jobs/labels for the actual bare-metal ports, not Dargent's Compose DNS names.
4. Both targets scrape `/actuator/prometheus` using approved least-privilege credentials; credentials are not committed, printed in logs, or placed in process arguments.
5. The metrics/query endpoint is bound to the approved local/restricted interface and is not publicly exposed.
6. `promtool` validates configuration and any rule/test files in CI. CI must not require or contact the production Prometheus instance.
7. Stopped, unreachable, unauthorized, and stale targets are distinguishable from a healthy target and are documented for the gate.

## S2 — Add the per-stage Prometheus canary gate

**Blocked by:** S0 decisions D3–D9 and S1.  
**Goal:** prevent a weight increase or old-color drain until fresh, sufficient, color-specific metrics pass the approved policy.

### Acceptance criteria

1. The gate evaluates only the candidate color and samples attributable to the current canary stage after its weight change.
2. The gate validates Prometheus API success, response shape, query result type, sample timestamp/freshness, numeric values, and traffic/sample floor.
3. It evaluates only the approved metrics, thresholds, and windows. Candidate series are existing HTTP/Micrometer histograms and reviewed metrics; do not add meters without a documented need and approval.
4. `NaN`, division by zero, no series, no traffic, stale data, insufficient volume, authentication failure, timeout, or query/API error never count as a passing stage.
5. The approved hold/retry deadline and terminal action are implemented exactly; any abort restores the previous color to 100%, stops the idle color as specified, exits non-zero, and names the failed gate.
6. The metrics gate runs after the stage's configured observation window and existing smoke check, before advancing to the next weight. The final-stage gate runs before stopping/draining the previous color.
7. Any bypass is absent unless S0 explicitly approves it. If approved, it is explicit, authenticated where applicable, logged, and covered by a negative/positive test.
8. The deployment script retains the current artifact identity, validation-first, same-JAR, readiness, smoke, and rollback contracts.

## S3 — Runbook, CI evidence, and authorized non-production rehearsal

**Blocked by:** S1 and S2.  
**Goal:** ensure operators can understand, diagnose, and rehearse the gate before production use.

### Acceptance criteria

1. `docs/release-runbook.md` documents the gate sequence, expected wait, threshold result, no-data/error behavior, and manual recovery path.
2. `docs/observability.md` and `docs/slos.md` are updated only where the new runtime/query contract changes their current truth.
3. The runbook identifies the Prometheus service owner, target credentials owner, storage/retention policy, and approved query endpoint without exposing secrets.
4. A UAT/staging rehearsal is separately authorized and recorded with the exact deploy-script SHA, release tag, per-stage metrics evidence, final health/smoke results, and rollback result.
5. The runbook's rollback-rehearsal table is updated from observed results; no production deployment is implied by a successful rehearsal.

## Sequencing and stop rules

```text
S0: owner approves architecture decisions
  → S1: provision + secure + per-color scrape
  → S2: implement/test stage gate
  → S3: documentation + authorized non-production rehearsal
  → separate production go/no-go and authorization
```

Do not begin S1 or S2 while the required decisions are open. If the approved operating model is an externally managed Prometheus service, S1 must instead document and test the exact external ownership, endpoint, target discovery, credentials, and availability contract; do not silently assume the service exists.
