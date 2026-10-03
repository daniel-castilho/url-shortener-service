# ADR 0012 — Metrics-gated blue-green canary (per-color Prometheus + fail-closed gate)

- **Status:** Accepted (S0 approved 2026-10-02)
- **Date:** 2026-10-02
- **Context:** ADR 0007 gives a fail-closed blue-green cutover whose per-step evidence is
  readiness + `nginx -t` + smoke. It has **no metrics evidence**: the only scrape config
  (`deploy/monitoring/prometheus.yml`) is a single job/target (`localhost:8080`) with no
  blue/green distinction, and **no Prometheus runtime is provisioned or confirmed** on any
  host (`docker-compose.yaml` is Mongo/Redis only; no Prometheus systemd unit exists). A
  canary could be promoted on readiness+smoke even while the new color is measurably
  erroring at higher weights. Dargent provides a read-only structural reference (per-color
  scrape jobs, `promtool` rule tests in CI) but **not** packaging, runtime, thresholds, or
  operating assumptions that transfer to this bare-metal service. Epic 25 S0 resolves the
  operating model and gate policy before any implementation.
- **Decision (D1–D9, Epics 25 S0):**
  1. **Packaging/lifecycle (D1):** systemd-managed Prometheus **binary on the deploy host**,
     pinned + sha256-verified install, unit with the app's hardening style
     (`NoNewPrivileges=true`, `ProtectSystem=strict`, `ProtectHome=true`, `Restart=on-failure`).
     Compose is reserved for Mongo/Redis only (ADR 0007); no external managed service exists.
  2. **Storage/retention/backup (D2):** `/var/lib/prometheus` (TSDB+WAL); retention `30d`
     **and** size cap `8GiB` (whichever first); restart survives on local disk; health via
     `/-/healthy` + `/-/ready`; a startup assert checks free disk. TSDB is operational,
     rebuildable data and is **not backed up**; rules/config are git-owned.
  3. **Query API (D3):** bind **loopback `127.0.0.1:9090`** only; `--web.enable-admin-api=false`,
     `--web.enable-lifecycle=false` (config is git-owned; reload via systemd `ExecReload`),
     no remote-write of the local TSDB. Not publicly exposed. The deploy gate queries
     `http://127.0.0.1:9090/api/v1/query` with bounded timeouts.
  4. **Scrape auth (D4):** reuse the existing Operator BasicAuth account
     (`OPERATOR_USERNAME`/`OPERATOR_PASSWORD`; `app.security.operator.*`, constant-time
     compare) as the scrape credential — the only least-privilege account that may read
     `/actuator/prometheus` (ADMIN|METRICS_VIEWER|OPERATOR). Secrets delivered via Prometheus
     `basic_auth` with **`password_file`** (root-owned `0600` on the host) — username env-fed,
     never in the committed config, args, or logs. Rotation = replace file + systemd reload.
     Per-color jobs: `url-shortener-blue` → `127.0.0.1:8080`, `url-shortener-green` →
     `127.0.0.1:8081`, `metrics_path=/actuator/prometheus`, `scrape_interval=15s`, stable labels
     `application=url-shortener`, `environment=prod`, `color=blue|green`, `honor_labels=false`.
     A dedicated scrape-only account is cleaner but is an app/security change — flagged as a
     future hardening item, not part of this epic.
  5. **Gate signals (D5, from existing frozen `http_server_requests*`/timers — no new meter):**
     - `up{job="url-shortener-<color>"} == 1` with **≥ 2 successful scrapes** in the window;
     - error ratio
       `sum(rate(...{status=~"5..",job=...,color=...}[W])) / sum(rate(...{job=...,color=...}[W])) < 0.001`
       (SLO 99.9%);
     - latency-OK ratio `sum(rate(..._bucket{le="0.2",job=...,color=...}[W])) / sum(rate(..._count{...}[W])) ≥ 0.90`
       (the 200 ms SLO contract in `docs/slos.md`; bucket ratio avoids quantile noise at low volume);
     - minimum volume ≥ 300 requests attributable to the stage window;
     - freshness: newest sample ≤ 45 s old (3 × 15 s).
     Thresholds are **placeholders ratified as policy; numeric values are recalibrated against
     real/authorized-traffic evidence during the S3 rehearsal** — not copied from Dargent or
     synthetic k6 numbers.
  6. **Observation window (D6):** per-stage window `W = 90 s` **anchored to start after the
     weight change** to the candidate color (pre-canary traffic never satisfies the floor).
     At 15 s scrape ≈ 6 samples; the existing 30 s dwell alone is insufficient evidence.
  7. **No-evidence behavior (D7):** absent/stale/`NaN`/infinity/non-numeric/zero-denominator/
     wrong-type/empty/multi-series/timeout/auth-error/Prometheus-down — **never a pass**.
     Bounded retry: each subsequent window is a retry, up to 3 evaluations total (hard
     deadline = dwell + 3×W), then **abort** (old color to 100% + reload + smoke, revert
     canary per ADR 0007, exit non-zero **naming the gate**).
  8. **Failure semantics / drain (D8):** a failed/indeterminate gate at any weight < 100 %
     aborts immediately to the old color; the **old color is never stopped before the final
     100 % stage gate passes**; after it passes, drain + stop exactly as today's success path
     (`rollback.sh` / `deploy/runtime/last-deploy.txt` preserved).
  9. **Bypass (D9):** **no bypass in v1** (default deny). Any future override must be explicit,
     authenticated where applicable, logged, recorded in `last-deploy.txt`, and covered by
     negative + positive tests — never silent.
- **Consequences:**
  - The gate runs after the stage's dwell + functional smoke, before the next weight; a single
    429-egress/`toomanyrequests` convention (debt 26, 34) is untouched — no new meter means
    `check-metrics-frozen.sh` stays green.
  - Operator supervision gains a first-class metrics service whose credentials reuse the
    existing operator account; the operator account now also unlocks scrape access — monitor
    that it remains least-privilege and rotated.
  - Deploy change-ownership: `deploy.sh` gains a metrics-gate step between smoke and the next
    weight and before old-color drain; abort semantics and naming remain ADR 0007-compatible.
  - The S3 authorized non-production rehearsal is required before any production weighting —
    this ADR does **not** authorize host access or deployment.
- **Rejected:**
  - **Compose-hosted Prometheus** (Dargent model) — Compose is for Mongo/Redis only (ADR 0007);
    container ops service adds a second supervision model and host Docker dependency.
  - **External managed Prometheus** — none exists/confirmed; no ownership contract to reuse.
  - **Bypass by default / silent override** — rejected (D9); evidence loss must fail closed.
  - **Anonymous scrape** — `/actuator/prometheus` rejects it (401; `OperatorAccessIT`).
  - **Copying Dargent thresholds / DNS naming** — Dargent is structural reference only.
- **Status/references:** S0 proposal + approval: `tasks/epic-25/epic-25-s0-decisions.md`;
  Epic 25 overview/stories/technical-tasks/testing/DoD: `tasks/epic-25/`. See also ADR 0005
  (fail-open vs fail-closed), ADR 0007 (blue-green), `docs/slos.md` (SLO/frozen meters),
  `deploy/monitoring/`, `scripts/deploy.sh`, `scripts/rollback.sh`.

## Amendments (2026-10-02 — implementation-accuracy, discovered while landing S1/S2)

The S0 decisions above are preserved as ratified; the following points record where the
implementation had to deviate for correctness, all verified by self-tests + the ephemeral
Prometheus e2e (`scripts/canary-prometheus-e2e.sh`):

1. **D3 — flag spelling:** Prometheus 3.x rejects `--web.enable-admin-api=false` at startup.
   The systemd unit uses `--no-web.enable-admin-api` / `--no-web.enable-lifecycle`. Same
   enforcement (both APIs off), corrected syntax.
2. **D4 — secret delivery:**
   - The password file is **`root:prometheus 0640`** (group-readable by the service account
     only), not `root:root 0600` — the `prometheus` process runs as its own user and must
     read the file; `0600 root:root` would break every scrape.
   - The username is **not** env-fed at runtime: Prometheus does **not** expand `${VAR}` in
     `basic_auth.username`/`password_file` (only `external_labels` support env expansion).
     `scripts/install-prometheus.sh` renders concrete values from the git-owned template
     into `/etc/prometheus/prometheus.yml` (`--render-config`), and the rendered form is
     validated with `promtool check config`. An unprovisioned username renders as the literal
     `UNPROVISIONED` → scrapes fail 401 (fail-closed, never anonymous).
   - The `environment` label renders from a **required** `--environment` flag (no default):
     staging can never be implicitly labeled `prod`.
3. **D5 — signals:** the scrape-count signal uses `sum_over_time(up{job=...}[W])` —
   **successful scrapes only** (≥2 required). The originally sketched `count_over_time`
   would also credit failed scrapes and could pass a flapping target. Latency-OK default
   calibrated **0.99** (from the 0.90 placeholder); thresholds remain provisional until the
   S3 staging rehearsal. Within one evaluation a measured breach (FAIL) is **sticky**: a
   later non-numeric/indeterminate signal can never downgrade the verdict to INDET.
4. **D7 — retry budget:** bounded multi-evaluation (up to 3) remains a **standalone gate
   capability** (`--max-evals`). `deploy.sh` drives the gate with **`--max-evals 1`**: the
   90 s window IS the bounded wait, and additional evaluations would extend the stage beyond
   the D6 budget. The single-eval policy is asserted by `deploy.sh --self-test`.
5. **Alerting:** the deploy-host Prometheus carries **no `alerting:` block** — no
   Alertmanager is provisioned by this epic; wiring alert delivery is a future,
   explicitly-approved scope (`docs/slos.md` claim updated accordingly).