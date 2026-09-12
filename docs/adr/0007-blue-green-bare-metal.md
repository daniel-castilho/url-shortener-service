# ADR 0007 — Blue-green deploy on bare metal (concrete units, rendered runtime conf, fail-closed cutover)

- **Status:** Accepted
- **Date:** 2026-09-12
- **Context:** Epic 6 proved the stateless multi-instance topology behind an nginx LB (ADR 0001),
  and the runbook §12 documents a *manual* canary (edit upstream weights by hand, restart, accept a
  downtime window on the active instance). The systemd template `url-shortener@.service` points
  every instance at the **same jar path**, which makes "artifact per color" impossible — a deploy
  is therefore a rebuild-in-place, and the bit-for-bit identity between what CI validated and what
  serves traffic is an assumption, not a fact. Epic 7 delivered the prerequisites this decision
  depends on: honest readiness (liveness ≠ readiness under a real `docker stop`), a drained 30s
  graceful shutdown, and incident playbooks with drill numbers.
- **Decision:**
  1. **Two concrete systemd units** — `url-shortener-blue.service` (port 8080, jar at
     `/opt/url-shortener/blue/url-shortener.jar`) and `url-shortener-green.service` (port 8081, jar
     at `/opt/url-shortener/green/url-shortener.jar`), with the same hardening as the template
     (`Type=notify`, `KillMode=mixed`, `KillSignal=SIGTERM`, `TimeoutStopSec=30`,
     `NoNewPrivileges=true`, `ProtectSystem=strict`, `Restart=on-failure`). Each color owns its
     jar: cutover stops the old color, it never deletes its jar, so rollback never needs a rebuild.
  2. **Rendered runtime conf** — `deploy/proxy/nginx.conf` is the single human-owned template; the
     deploy tooling never writes it. `scripts/deploy.sh` renders `deploy/runtime/nginx.conf` (two
     variable `server 127.0.0.1:808x` lines with weights) via awk **to a temp file then `cat`** over
     the runtime copy — never awk-in-place on the same path in/out (dargent E12 S2 lesson: the
     redirect truncates the target before awk reads it). `deploy/runtime/` is gitignored.
  3. **Fail-closed cutover** — every precondition (release exists, sha256 matches, jar staged) and
     every step (readiness within budget, `nginx -t`, smoke after each bump) aborts to the OLD
     color at 100%, drains and stops the new color, and exits non-zero **naming the offending step**.
  4. **Canary 10/30/100** with 30s dwell per step and a `scripts/smoke.sh` probe after each bump;
     `nginx -t` runs before every `nginx -s reload`.
  5. **`deploy/runtime/last-deploy.txt`** (`previous` / `current` / `tag` / `at`) is written before
     the first weight flip — `scripts/rollback.sh` reads it to revert in one command, no rebuild.
  6. The template `url-shortener@.service` **remains** for scale-out ≥ 3 instances (runbook §12);
     the *deploy plan* is blue+green only.
- **Consequences:**
  - After a successful cutover the fleet is **1 active + 1 idle** instance: deploy capacity is 1×.
    Sustained 2× capacity requires an extra instance outside the deploy plan (scale-out template) —
    an explicit operator decision, not a deploy side effect.
  - Deploys are zero-downtime: the active color keeps serving through every bump; the idle color
    receives the new jar, boots, and is readiness-gated before receiving any traffic.
  - Rollback is a weight flip + `systemctl start` of the previous color — seconds, not a rebuild.
  - The nginx front must be reloaded (not restarted) — reload keeps worker processes and existing
    connections alive; `nginx -t` before each reload is what makes a bad render fail-closed.
- **Rejected:**
  - **Kubernetes / an orchestrator** — the platform is on-prem bare metal (ADR 0001 context).
  - **Rolling restart without canary** — binary traffic flip, no gradual exposure; a bad artifact
    hits 100% of traffic at once. Rejected in favour of 10/30/100 with smoke probes.
  - **In-place fleet update** (stop everything, swap jar, start) — downtime window; rejected.
  - **Docker Compose for the app itself** — the app is a systemd unit on bare metal (deployment
    contract since Epic "operational excellence"); compose is for Mongo/Redis only.

## Links

- `docs/release-engineering.md` (the full release contract), `docs/release-runbook.md` §12
  (scale-out), `scripts/deploy.sh`, `scripts/rollback.sh`, `deploy/systemd/url-shortener-{blue,green}.service`.
