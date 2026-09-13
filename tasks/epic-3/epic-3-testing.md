# Epic 3 – Testing Strategy

## 3.1 Correlation-Id (CorrelationIdIT)
- **Objective:** Prove that every request log contains `request_id` in the MDC and that the header is echoed.
- **Action:**
  - `./mvnw test -Dtest='CorrelationIdIT'` → green (RestAssured against RANDOM_PORT; captured logs contain `request_id`).
  - Validation grep: every log sink within the request cycle references `request_id` from the MDC (not on a fixed line).
- **Acceptance criterion:** green test + output pasted into the handoff-DoD.

## 3.2 Frozen metrics (check-metrics-frozen)
- **Objective:** No new series in the `/actuator/prometheus` playback without a design review.
- **Action:**
  - `scripts/check-metrics-frozen.sh` in `./mvnw verify` → PASS.
  - Frozen list == `MicrometerMetricsAdapter` series (10 business) + expected runtime ones.
- **Acceptance criterion:** green gate + output pasted.

## 3.3 Tiered health checks (ProductionLockdownIT)
- **Objective:** Validate prod behaviour: public liveness/readiness, non-leaked detail, non-public endpoints blocked.
- **Action:**
  - `./mvnw test -Dtest='ProductionLockdownIT'` → green (Testcontainers Mongo/Redis; `prod` profile).
  - Assertions: `/health/liveness` 200; `/health/readiness` 200; `/health` without sensitive `details`; unauthorized access denied.
- **Acceptance criterion:** green test + output pasted.

## 3.4 Alert rules (promtool/amtool in CI)
- **Objective:** Rules in `deploy/monitoring/alerts.yml` syntactically valid and consistent with SLOs.
- **Action:**
  - `promtool test rules <test>` → 0 errors/warnings.
  - `amtool check-config <config>` → green.
  - Every rule with the `runbook-§X` annotation (`docs/slos.md`).
- **Acceptance criterion:** green bins (local + CI) + outputs pasted.

## 3.5 Diagnostic panel (debug-health.sh)
- **Objective:** An operational script that turns `/actuator/prometheus` into "what to do now".
- **Action:**
  - `bash scripts/debug-health.sh` → readable output, prints the recommended action per SLO.
- **Acceptance criterion:** green script + output pasted.

## 3.6 CI integration
- **Objective:** Block merge if any observability check fails.
- **Action:**
  - `observability` job in `.github/workflows/ci.yml`: `check-metrics-frozen.sh` + `promtool test rules` + `amtool check-config`.
  - Failure in any job → PR blocked.
- **Acceptance criterion:** green pipeline; red blocks.

## 3.7 Traceability with EP2
- **Objective:** EP2 series (`security.ssrf.blocked.total`) in the frozen gate and incremented in tests.
- **Action:**
  - `./mvnw verify` combined → green; playback includes EP2 series.
- **Acceptance criterion:** green gate + verify + output pasted.

---

**Epic 3 completion checklist:**

- [ ] `CorrelationIdIT` → 100% logs with `request_id` in the MDC
- [ ] `metrics-frozen-check` PASS
- [ ] `ProductionLockdownIT` → tiered health green
- [ ] rules + `promtool test rules` + `amtool check-config` green
- [ ] `debug-health.sh` green
- [ ] CI job `observability` green
- [ ] Backward-compatible integration with EP2 green
- [ ] `./mvnw verify` fully green (unit + IT + gates)

*Once all are checked, Epic 3 is complete and the next epic (EP4 – Testing) can start.*