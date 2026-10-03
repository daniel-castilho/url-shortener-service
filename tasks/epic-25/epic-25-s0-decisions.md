# Epic 25 S0 — Decision Record (D1–D9): Production Metrics-Gated Blue-Green Canary

- **Status:** APPROVED by owner (2026-10-02). Promoted to `docs/adr/0012-metrics-gated-blue-green-canary.md`.
  No implementation, host access, or deployment is authorized by this record alone; S1–S3 require
  their own authorizations and the S3 non-production rehearsal.
- **Date:** 2026-10-02
- **Amendments (2026-10-02, implementation-accuracy):** while landing S1/S2 the implementation
  deviated from the original proposal on four verified points — D3 flag spelling
  (`--no-web.enable-admin-api`), D4 secret delivery (root:prometheus 0640 password file;
  installer-RENDERED username — Prometheus does not expand env vars in `basic_auth`;
  required `--environment` label), D5 signals (`sum_over_time(up[W])` successful-scrape
  count; latency default 0.99; sticky FAIL) and D7 retry budget (deploy uses
  `--max-evals 1`; multi-eval is standalone). The proposal text below is preserved as the
  ratified baseline; the authoritative amended state is
  `docs/adr/0012-metrics-gated-blue-green-canary.md` §Amendments.
- **Reference:** `tasks/epic-25/epic-25-overview.md` (D1–D9), `epic-25-stories.md` S0
  acceptance criteria 1–5, `epic-25-technical-tasks.md` S0, `docs/adr/0012-metrics-gated-blue-green-canary.md`.
- **Target repository:** `daniel-castilho/url-shortener-service` only.
- **Owners:** System Architect / environment owner (decisions) — **approval recorded 2026-10-02**;
  Software Engineer (implementation drafts).

Each decision lists a proposed answer, the evidence/constraint it rests on, and the
alternatives rejected. Numeric thresholds are **placeholders pending owner ratification**
and calibration against real/authorized-traffic evidence — they are not copied from Dargent
or from synthetic k6 results.

---

## D1 — Prometheus packaging and lifecycle

**Proposed:** systemd-managed Prometheus **binary on the deploy host**, installed with a
pinned version + sha256 verification (mirrors the Epic 8 artifact-identity discipline),
running under `deploy/systemd/prometheus.service` with the same hardening style as the app
units (`NoNewPrivileges=true`, `ProtectSystem=strict`, `ProtectHome=true`,
`Restart=on-failure`). Lifecycle (start/stop/status/restart/reload) is systemd-owned; the
configuration lives in `deploy/monitoring/prometheus.yml` (git-owned template) with the two
per-color scrape jobs from D4.

**Evidence/constraint:**
- ADR 0007: the app is a systemd unit on bare metal; "Docker Compose is for Mongo/Redis
  only". Prometheus is an ops service on the same host — systemd keeps a single,
  homogenous, container-free supervision model for everything outside Mongo/Redis.
- No runtime is currently confirmed (`docker-compose.yaml` has no Prometheus; no
  `deploy/systemd/` unit exists). S1 must provision it with authorized-owner evidence; this
  decision only fixes *how* it is managed.

**Rejected:**
- **Compose profile for Prometheus** (Dargent's model) — rejected: ADR 0007 reserves Compose
  for backing data services; a container-based ops service adds a Docker dependency on the
  deploy host and a second supervision model.
- **Already-managed external service** — rejected for now: none exists or is confirmed; would
  still require documenting an external ownership/availability contract (S1 handles that only
  if the owner later selects it).

---

## D2 — Storage, retention, size cap, restart, backup

**Proposed:**
- Data dir `/var/lib/prometheus` (TSDB + WAL), retention `30d` **and** a size cap
  `8GiB` (whichever hits first) — disk use is bounded on a bare-metal host.
- Restart: `Restart=on-failure` with a short backoff; TSDB survives on local disk across
  restarts. `/-/healthy` + `/-/ready` are the health checks; a startup assert checks free
  disk for the cap.
- Backup expectations: **raw TSDB is operational, rebuildable data — not backed up.**
  Rules/alerting/scrape config are git-owned (rebuildable from the tag). Mongo `backup-mongodb.sh`
  remains the only RPO source. This matches the current backup scope (Epic 8).

**Evidence/constraint:** storage path/cap are currently unverified; the exact host path, free
disk, and cap are recorded at S1 by the authorised operator.

---

## D3 — Query API binding/network boundary and deploy-time auth

**Proposed:**
- Bind Prometheus to **`127.0.0.1:9090`** only (loopback) — never a public or LAN interface.
  `deploy.sh` runs on the same host, so the gate reaches it over loopback.
- Disable `--web.enable-admin-api`, `--web.enable-lifecycle` (config is git-owned; reloads go
  through systemd `ExecReload`), disable remote-write of the local TSDB, console templates off.
- The query API is never exposed; any remote access is an operator-only tunnel and out of scope.

**Evidence/constraint:** `deploy/monitoring/prometheus.yml` alerts route to a placeholder
`localhost:9093` today; no runtime exists. The gate queries
`http://127.0.0.1:9090/api/v1/query` (JSON: `status`, result type, values) with bounded
timeouts.

---

## D4 — Scrape authentication and secret delivery

**Proposed:**
- **Reuse the existing Operator BasicAuth account** (`app.security.operator.username/password`
  ⇄ env `OPERATOR_USERNAME` / `OPERATOR_PASSWORD`, constant-time compare, `SecurityConfig` /
  `ProdConfigValidator`) as the scrape credential — it is today's only least-privilege account
  that can read `/actuator/prometheus` (ADMIN|METRICS_VIEWER|OPERATOR gate; `OperatorAccessIT`
  proves an authorized operator scrape 200).
- Secret delivery: Prometheus `basic_auth` block with `username` from an env-overridable value
  and **`password_file`** = a root-owned, `0600` file path on the host (provisioned at S1 by the
  operator). No credential in `prometheus.yml`, process args, shell traces, or logs.
- Per-color scrape jobs with **stable labels** (S1 config, baked into `prometheus.yml`):

  | job | target | labels |
  |---|---|---|
  | `url-shortener-blue` | `127.0.0.1:8080` | `application=url-shortener`, `environment=prod`, `color=blue` |
  | `url-shortener-green` | `127.0.0.1:8081` | `application=url-shortener`, `environment=prod`, `color=green` |

  `metrics_path=/actuator/prometheus`, `scrape_interval=15s`, `honor_labels=false`.

**Evidence/constraint:** the app exposes one operator account; the machines run a single
Managed-Security post — a separate scrape-only account is a cleaner least-privilege shape but
requires an **application/security change (new auth source)**, which is out of scope unless
separately approved (flag it in S3 as a future hardening item). Rotation = replace the
password file + systemd reload.

**Rejected:** committing a credential; putting credentials in CLI args; anonymous scrape
(the endpoint rejects it).

---

## D5 — Gate signals and thresholds (placeholders pending owner approval)

**Proposed signal set** — all computed from existing frozen meters (`http_server_requests*`
histogram, redirect timers; **no new application meter is assumed** — metric-freeze gate
stays green):

| # | Signal | PromQL | Proposed default (placeholder) |
|---|---|---|---|
| S1 | Scrape health | `up{job="url-shortener-<color>"}` | `== 1`; ≥ 2 scrapes in window (a 1-sample pass is invalid) |
| S2 | Error ratio ≤ SLO | `sum(rate(http_server_requests_seconds_count{status=~"5..",job=...,color=...}[W])) / sum(rate(http_server_requests_seconds_count{job=...,color=...}[W]))` | `< 0.001` (SLO 99.9%) |
| S3 | Latency-OK ratio (200 ms) | `sum(rate(http_server_requests_seconds_bucket{le="0.2",job=...,color=...}[W])) / sum(rate(http_server_requests_seconds_count{job=...,color=...}[W]))` | `≥ 0.90` |
| S4 | Minimum volume (traffic floor) | `sum(rate(http_server_requests_seconds_count{job=...,color=...}[W])) * W` | `≥ 300` requests in the window |
| S5 | Freshness | newest sample timestamp for the color | `≤ 45 s` old (3 × scrape) |

- Thresholds must be ratified by the owner against real/authorized-rehearsal traffic (stories
  S0 AC3); the k6 baseline (`docs/load-test-baseline.md`) is structural evidence (what volume *
  could * look like), **not** a threshold source.
- If an owner-approved alternative quantile signal is preferred (e.g. redirect p99 < 200 ms),
  show it here; the bucket-ratio S3 is preferred for low-volume windows because it avoids
  estimator noise at few samples.

---

## D6 — Observation window, freshness, minimum volume

**Proposed:**
- Per-stage observation window `W = 90 s` (configurable) **anchored to start after the
  weight change** to the candidate color — pre-canary traffic never satisfies the floor.
  At 15 s scrape = ~6 samples; the current 30 s dwell (~2 samples) is insufficient evidence
  alone (overview D6), so the gate's observation extends the stage timing without replacing
  the existing readiness/smoke flow.
- Freshness rule as S5. Minimum volume as S4.
- Require ≥ 2 successful `up` samples in `W` for the candidate job (single-scrape pass invalid).

---

## D7 — Missing, stale, NaN, zero-traffic, or query-error behavior

**Proposed (fail-closed, "no evidence ≠ healthy"):**
- Absent series, empty result, wrong result type, `NaN`/infinity, non-numeric value, stale
  timestamp, zero denominator, `up != 1`, timeout, HTTP error, auth failure, or Prometheus
  unreachable — **never a pass**.
- Bounded retry: each subsequent window is a retry, up to **3 total evaluations** (hard
  deadline = dwell + 3 × `W`). After the deadline: **abort** to the old color at 100%, reload,
  smoke the old color, revert the canary per the existing abort path (ADR 0007), exit non-zero
  **naming the gate** (matches `deploy.sh` "abort names the step").
- Insight: with Prometheus down there is no metrics evidence for anyone — the operator can
  only proceed via an approved bypass (D9); default in v1 = **fail-closed, no bypass**.

---

## D8 — Per-stage failure semantics and old-color drain timing

**Proposed:**
- A failed/indeterminate gate at any weight **< 100 %** aborts immediately to the old color
  (per ADR 0007 fail-closed cutover); the old color stays at 100%; the canary color is handled
  identically to today's abort (drained/stopped per the unit contract); exit non-zero naming
  the step+gate.
- The old color is **never stopped before the final 100 % stage gate passes** (`stories.md` S2
  AC). After the final gate passes, drain + stop the old color exactly as the current success
  path does (`rollback.sh`/`last-deploy.txt` are preserved).
- Bounded retries per D7; the abort path reuses existing recovery (rollback is a weight flip,
  no rebuild — ADR 0007).

---

## D9 — Emergency override policy

**Proposed:** **no bypass in v1** (default deny).
- If the owner later approves one: an explicit `--no-metrics-gate` flag, mutually exclusive
  with the normal path, requiring `--reason` (audited, logged, recorded in
  `deploy/runtime/last-deploy.txt`), backward-compatible, and covered by negative + positive
  tests. Nothing silent or implicit.
- Deferred override reviews are recorded as a blocker, never as an auto-pass.

---

## Preservation invariants (from overview / stories / dod)

- Existing artifact identity, validation-first, same-JAR install, readiness, `nginx -t`,
  smoke-per-bump, and rollback contracts remain intact (stories S2 AC8, dod).
- No new application meter without a documented need + approval (metric-freeze gate green).
- CI never contacts the production Prometheus instance.
- Dargent is a read-only reference; no Compose DNS/runtime or thresholds are copied.

## Owner verification checklist (S0 AC2 — required before approval)

1. Authorized access to the real non-production/staging metrics host is confirmed by the
   owner (host, interface, free disk, credentials file path).
2. The chosen packaging (D1) and storage path/cap (D2) are confirmed as acceptable for the
   host.
3. Gate thresholds/windows (D5–D6) are ratified against authorized-traffic evidence — not
   synthetic k6 numbers.
4. D7/D8/D9 policies are accepted as written or amended; the final decision record is
   recorded in `epic-25-stories.md` or a linked ADR before S1/S2 start.

## Approval block

**All D1–D9 APPROVED by the owner on 2026-10-02.** Approved values are recorded in
`docs/adr/0012-metrics-gated-blue-green-canary.md`. Numeric thresholds remain flagged as
placeholder values subject to real-traffic calibration during the S3 authorized rehearsal
before production use.

| Decision | Proposed | Approved (owner) | Notes |
|---|---|---|---|
| D1 systemd binary | ✓ | ✓ | date 2026-10-02 |
| D2 storage/retention/backup | ✓ | ✓ | date 2026-10-02 |
| D3 loopback only | ✓ | ✓ | date 2026-10-02 |
| D4 operator scrape + password file | ✓ | ✓ | date 2026-10-02 |
| D5 signals + placeholder thresholds | ✓ | ✓ | real-traffic calibration at S3 |
| D6 window/freshness/volume | ✓ | ✓ | date 2026-10-02 |
| D7 fail-closed + bounded retry → abort | ✓ | ✓ | date 2026-10-02 |
| D8 abort/drain timing | ✓ | ✓ | date 2026-10-02 |
| D9 no bypass in v1 | ✓ | ✓ | date 2026-10-02 |

*Approved decisions live in `docs/adr/0012-metrics-gated-blue-green-canary.md`. Implementation
(S1–S3) requires S0 approval-clearance, which is now granted; production operation requires
separate authorization.*