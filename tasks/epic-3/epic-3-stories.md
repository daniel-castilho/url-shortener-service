# Epic 3 – Stories (Acceptance)

Baseline = already exists (verifiable via grep/read); Gap = new work. Metrics prefix: `service=url-shortener`; real names in `MicrometerMetricsAdapter` (10 business series: 8 counters + 2 timers).

| # | Story | Baseline (already exists) | Gap (new) | Acceptance Criteria | Reference |
|---|-------|--------------------------|------------|--------------------|------------|
| **3.1** | **Correlation-Id across the whole pipeline** | — (no `MDC`/`request_id`/`X-Request-Id` in `src/main/java`, verified 2026-09-11) | `OncePerRequestFilter` that echoes/generates `X-Request-Id` (safe charset, ≤ 64 chars) and injects `request_id` into the MDC; propagation to async consumers | • `CorrelationIdIT` green: 100% of request logs contain `request_id` in the MDC <br>• validation script/grep green <br>• header echoed in the response | `docs/observability.md` (new section) |
| **3.2** | **"Frozen" metrics** | `MetricsPort` + `MicrometerMetricsAdapter` with 10 series; `/actuator/prometheus` exposed (dev) | `metrics-frozen-check` gate (script or Micrometer) that compares registered series against the frozen list; zero new series without review | • frozen list == adapter series (10 business + expected JVM/runtime ones) <br>• gate PASS on `verify` <br>• no new `Counter`/`Timer` in the commit without review | `docs/slos.md` §2 + `docs/observability.md` §Metrics |
| **3.3** | **Tiered health checks + prod lockdown** | Lockdown already implemented (debt 9): public liveness/readiness/info; health detail `when-authorized`; prod `health-detail-enabled: false`; `/actuator` via `app.security.actuator` | `ProductionLockdownIT` test booting `prod` (or RestAssured end-to-end test) | • `health/liveness` 200 <br>• `health/readiness` 200 (Mongo+Redis up) <br>• prod: health detail does not leak <br>• `ProductionLockdownIT` PASS | `src/main/resources/application.yaml` (wires `management.health.*` + `app.security.actuator`) |
| **3.4** | **Validated alert rules** | `deploy/monitoring/alerts.yml` with 3 burn-rate rules from the SLOs; `docs/slos.md` §Burn-rate + §Response runbook | `runbook-§X` coverage/annotations on all rules; `promtool test rules` + `amtool check-config` in CI (promtool/amtool **not installed** locally — download the binary or a container in the job) | • `promtool test rules` → 0 errors <br>• `amtool check-config` green on every push <br>• each rule has the `runbook-§X` annotation pointing to `docs/slos.md` | `docs/slos.md` §Burn-rate + `deploy/monitoring/alerts.yml` |
| **3.5** | **Quick diagnostic panel** | — (no `scripts/debug-health.sh`; `docs/observability.md` has no symptom→check→action table) | Table with ≥5 symptom→check→action rows in `docs/observability.md`; `scripts/debug-health.sh` queries `/actuator/prometheus` and prints the action | • table ≥5 rows <br>• `bash scripts/debug-health.sh` runs without errors and prints the recommended action | `docs/observability.md` (new section §Diagnostics) |
| **3.6** | **Observability in CI** | Existing CI: `unit`, `integration-tests`, `security-check`, `build`, `doc-sync` | `observability` job (or steps in the current CI) with: `metrics-frozen-check`, `promtool test rules`, `amtool check-config` | • job green on push <br>• any failure blocks merge | `.github/workflows/ci.yml` |
| **3.7** | **Backward compatibility with EP2** | `security.ssrf.blocked.total` incremented and tested (SsrfProtectionIT); headers tested (SecurityHeadersIT) | Ensure it is in the frozen list + playback in `/actuator/prometheus`; no name collisions | • EP2 series present in the frozen list and in the playback <br>• `./mvnw verify` combined green | `Tasks EP2` + `docs/observability.md` |

---

**Quick traceability:**

| Story | Reference doc | Key aspect |
|-------|---------------|------------|
| 3.1 | `observability.md` (new §Correlation) | Correlation-Id in the MDC |
| 3.2 | `slos.md` §2 + `observability.md` §Metrics | Frozen metrics |
| 3.3 | `application.yaml` + debt 9 | Tiered health / lockdown |
| 3.4 | `slos.md` §Burn-rate + `deploy/monitoring/alerts.yml` | Alert rules |
| 3.5 | `observability.md` (new §Diagnostics) | Diagnostic panel |
| 3.6 | `.github/workflows/ci.yml` | Gates in CI |
| 3.7 | EP2 + frozen list | Backward compatibility |

---

*Next step: technical tasks (3.1–3.7) in `epic-3-technical-tasks.md`.*