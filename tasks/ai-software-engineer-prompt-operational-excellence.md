# AI Software Engineer Prompt — Operation & Observability
## Tracing, structured logs, SLOs, load baselines, TLS, deploy & backups

**Status:** ready for implementation from current `main` (`0579f5d` / `v0.6.0`).
**Priority:** P1 — operational maturity / production readiness. Fifth epic.
**Target:** make the service observable, measurable and recoverable — distributed tracing, structured
(JSON) logs, defined + monitored SLOs, a load/performance baseline, documented TLS termination +
deploy/rollback, and scheduled MongoDB backup/restore + `click_events` retention — without degrading
the redirect hot path.

You implement the complete **Operation & Observability** epic. Rate-limit (v0.4.0), SSRF + actuator
hardening (v0.5.0) and the existing Micrometer metrics are already shipped; this epic adds the missing
operational layer.

---

## Sources of truth — read in this order

1. `AGENTS.md` (rules 5, 8, 10; Known Technical Debt item 12)
2. `docs/twelve-factor.md` (factors 5, 11, 12)
3. `docs/release-runbook.md`
4. `docs/coding-standards.md`
5. `docs/testing-playbook.md`
6. `pom.xml`, `src/main/resources/application.yaml`, `src/main/resources/logback-spring.xml`
7. `tasks/operational-excellence-spec.md`
8. `tasks/operational-excellence-backlog.md`
9. `tasks/operational-excellence-implementation-sequence.md`
10. `infra/observability/MetricsService.java`, `MicrometerMetricsAdapter.java`, `InfraConfig`/`config`,
    `docker-compose.yaml`, `Dockerfile`, `.github/workflows/ci.yml`, `scripts/check-boundaries.sh`

If documentation disagrees with executable configuration, stop, report and resolve in the same change.

---

## Goal

The service has Micrometer counters/timers and a Prometheus endpoint, but no distributed tracing, no
structured (JSON) logs, no measured/latency SLOs, no load baseline, no documented TLS termination /
deploy / rollback, and no scheduled backup or data retention. This epic closes those operational gaps.

---

## Locked technical decisions

1. **Distributed tracing via OpenTelemetry** (Micrometer Tracing + OTel bridge + exporter). Auto-
   instrument Spring Web + Data (Mongo/Redis). Keep `core/` import-free.
2. **Structured (JSON) logging** via a `json` log profile (logstash-logback-encoder), keeping the default
   plain console/file for local dev. Never log secrets/PII.
3. **SLOs** for the redirect path (p95 latency, availability) + shorten; backing metrics and a
   low-cardinality error/5xx + status-breakdown counter. No high-cardinality tags (no user/IP/raw code).
4. **TLS via a reverse proxy** (NGINX/Caddy) in front of the app for on-prem; the app stays on an
   internal port and the rate limiter trusts only a configured proxy CIDR.
5. **Deploy formalization** — documented systemd unit (or compose service) + rollback + a fail-fast
   startup validation of the env contract, plus graceful-shutdown verification.
6. **Backup/restore** for MongoDB (mongodump/mongorestore to a durable off-host location) and a
   **retention purge** of `click_events` (bounded, idempotent, scheduled).
7. **No new Maven coordinate without explicit approval** — OpenTelemetry and logstash-logback-encoder are
   the only candidates; confirm both.
8. **Never degrade the redirect hot path** — tracing/logging are async/best-effort; no blocking external
   call in the redirect.

---

## Non-negotiable engineering rules

- Keep `core/` free of Spring, Mongo, Redis, JWT, OTel/Micrometer-tracing; all observability lives in
  `infra/`.
- The redirect path must not be made slower or blocking by observability.
- Never log or metric-tag secrets/PII (passwords, JWTs, bearer tokens, IP as a high-cardinality tag).
- Structured logs remain machine-parseable; the default local profile stays plain.
- Migrations/retention/backup are background/scheduled, not per-request.
- English only in code, comments, logs, tests and docs.
- Do not push unless the human explicitly asks.
- Do not expand into: link expiry (epic 4, distinct), rate-limit, SSRF, actuator hardening, dashboards
  provisioning (optional), or API contract changes.

---

## Required behaviour summary

### Observability
- Tracing: OTLP (prod) / in-memory (dev), spans across controller → service → Mongo/Redis.
- Logging: `json` profile emits structured JSON; no secret/PII.
- Metrics: `redirect.latency`/`shorten.latency` recorded at runtime; add a low-cardinality 5xx + status
  breakdown; document SLOs.

### Performance & reliability
- k6 load baseline on `GET /{code}` with thresholds-as-code; `scripts/performance-baseline.sh`;
  `continue-on-error` CI job first.
- Graceful-shutdown verification.

### Deployment & TLS
- Reverse proxy terminates TLS; app on internal port; rate limiter trusts proxy CIDR.
- systemd/compose service + rollback + fail-fast startup validation.

### Data & retention
- Scheduled `mongodump` (off-host) + `mongorestore` drill.
- Bounded, idempotent `click_events` retention purge by `timestamp`.

---

## Scope exclusions

Do not implement: link expiry / versioned migration (the separate documented epic 4), redirect
rate-limiter, SSRF, actuator/Swagger hardening, full dashboard provisioning, or any public API contract
change.

---

## Definition of Done

### Observability
- [ ] Tracing (OTel) spans a redirect across controller → service → Mongo/Redis; `core/` import-free.
- [ ] Structured JSON log profile; default plain log preserved; no secret/PII.
- [ ] `redirect.latency`/`shorten.latency` emitted at runtime (no dead metrics); SLOs documented with
      backing metrics; low-cardinality 5xx/status breakdown present.

### Performance & reliability
- [ ] k6 load baseline with thresholds; reproducible; promoted-to-gate-ready.
- [ ] Graceful-shutdown verified and documented.

### Deployment & TLS
- [ ] HTTPS via proxy; rate limiter trusts only the proxy CIDR.
- [ ] Documented deploy + rollback works; fail-fast startup on bad env; graceful shutdown drains.

### Data & retention
- [ ] MongoDB backup/restore scheduled and verified drill.
- [ ] `click_events` retention purge bounded, idempotent, scheduled.

### Verification & delivery
- [ ] `mvn test`, `mvn verify` and `check-boundaries.sh` pass.
- [ ] Docs (twelve-factor, release-runbook, coding-standards, testing-playbook, README) and `AGENTS.md`
      (item 12) are synced; no stale "no tracing / plain logs only" claim.
- [ ] No secret/PII in logs/metrics; no high-cardinality tag; the redirect hot path is not degraded.

Start at **Step 0** of `operational-excellence-implementation-sequence.md`. Stop immediately if: the
baseline is red, a dependency (OTel/encoder) is not approved, a locked decision conflicts with the
approved dependency graph, or repository state contradicts the specification.
