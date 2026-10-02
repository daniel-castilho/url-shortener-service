# Epic 25 Technical Tasks: Production Metrics-Gated Blue-Green Canary

**Target repository:** `daniel-castilho/url-shortener-service` only  
**Status:** S0 decisions APPROVED (2026-10-02) → `docs/adr/0012-metrics-gated-blue-green-canary.md`
and `epic-25-s0-decisions.md`. Implementation tasks below are ready to start only with the
authorizations each requires (S1 host access / S2 code / S3 rehearsal).

## S0 — Architecture decision record

- [x] Record the approved Prometheus operating model (Compose/systemd/external owner) and service lifecycle. — ADR 0012 D1
- [x] Record the actual approved runtime host, network boundary, query API address, storage path, retention, resource cap, and backup/restore expectations. — ADR 0012 D2/D3 (host specifics to be evidenced at S1)
- [ ] Confirm authorized access to the real staging metrics environment; do not infer runtime existence from repository files. — **blocked on host access authorization**
- [x] Choose the least-privilege credentials for Prometheus scraping and for deploy-time queries; define secret-file ownership, rotation, and logging rules. — ADR 0012 D4
- [x] Approve the per-stage metric set, thresholds, windows, freshness limit, minimum request count, bounded wait, and failure action. — ADR 0012 D5/D6/D7 (thresholds flagged for real-traffic calibration at S3)
- [x] Decide whether a bypass is permitted; default recommendation is no bypass. — ADR 0012 D9
- [x] Record any decision that remains open as an explicit blocker. — no blocker; pending item: numeric threshold calibration + host evidence at S1/S3

## S1 — Prometheus runtime and scrape configuration

- [ ] Provision the approved Prometheus runtime with restart policy, health check, persistent storage, retention, and disk/resource bounds.
- [ ] Bind the query API only to the approved local/restricted interface.
- [ ] Configure separate scrape jobs for blue and green using the actual service ports and stable `color` labels.
- [ ] Configure `/actuator/prometheus` scrape authentication using the approved secret mechanism; do not commit live credentials.
- [ ] Add or adapt `promtool check config`, `promtool check rules`, and `promtool test rules` CI steps with pinned tool versions.
- [ ] Prove target health semantics for both running and stopped colors (`up`, missing series, and scrape errors).
- [ ] Document operational ownership, storage, backup, retention, port binding, and restart behavior.

## S2 — Canary-gate implementation

- [ ] Implement a small, testable gate component (candidate: `scripts/canary-gate.sh`) with an explicit CLI contract for color, stage, observation window, and configured Prometheus endpoint.
- [ ] Query Prometheus's HTTP API using bounded connect/overall timeouts; validate HTTP status, JSON status, result cardinality, types, timestamps, freshness, and numeric values.
- [ ] Keep credentials out of command-line arguments, shell traces, and logs; use the approved secret-file mechanism.
- [ ] Implement the S0-approved PromQL for availability/error ratio, latency, `up`, and minimum sample count. Handle `NaN`, absent series, and zero denominator explicitly.
- [ ] Ensure the observation window begins after the current weight change. Do not count pre-stage history as current-stage evidence.
- [ ] Implement bounded hold/retry and terminal behavior exactly as approved. Insufficient or stale data must never pass.
- [ ] Call the gate after each stage's dwell and functional smoke, before applying the next weight. Require a passing 100% stage gate before draining the old color.
- [ ] Preserve existing validation-first artifact handling, same-byte installation, readiness, smoke, and fail-closed rollback behavior.
- [ ] Add clear per-stage logs: color, weight, observation window, sample count, freshness, measured values, thresholds, and gate decision. Never log credentials.

## S3 — Runbook and rehearsal

- [ ] Update the deployment and observability documentation to match implemented behavior and approved policy.
- [ ] Document diagnosis for Prometheus unavailable, target down, auth rejected, stale/low-volume data, threshold failure, and manual recovery.
- [ ] Add an operator checklist for a separate authorized UAT/staging rehearsal; include a rollback and evidence-recording step.
- [ ] Record the exact deployment code SHA and release tag used in any rehearsal evidence.
- [ ] Keep production deployment, remote settings, and host changes outside this implementation scope; require separate authorization.
