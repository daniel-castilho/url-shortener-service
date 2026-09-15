# Epic 8: Deployable – Release Engineering and Zero-Downtime Deploy

**Project:** url-shortener-service  
**Context:** Java 25, Spring Boot 4.1.1, Hexagonal Architecture, MongoDB 6.0 (single-node), Redis (single-node + AOF + Stream), Tomcat 11 (virtual threads), on-prem bare metal + nginx + systemd  
**Goal:** Make the "validated code → production" path **predictable, automated, and provable**: a release tag only exists as an artifact if it passes every gate; the bare-metal deploy is blue-green with canary and **fail-closed** cutover; the rollback is a single command; the backup is scheduled, manifested, and verified by restore. This epic does **not** change the platform (no k8s/orchestrator), does **not** solve the single-node SPOF (accepted in EP7), and does **not** deploy automatically over SSH (the human gate is a recorded decision).

---

## Repo state (pre-existing, not new work)

The repo **already has the artifacts** — Epic 8 **automates and contracts** them, it does not rewrite them:

- **Docker** multi-stage (non-root, `HEALTHCHECK`, container-aware JVM flags) + `docker-compose.yaml` (mongo+redis with healthchecks and volume).
- **Systemd:** `deploy/url-shortener.service` + `deploy/url-shortener@.service` template (instance N → port `8080+(N-1)`), with hardening (`NoNewPrivileges`, `ProtectSystem=strict`, `KillMode=mixed`, SIGTERM, `TimeoutStopSec=30`).
- **Proxy:** nginx (`deploy/proxy/nginx.conf`) with weighted upstream + `max_fails=2 fail_timeout=10s`; Caddy (auto-HTTPS) as an alternative; TLS + HSTS on both (EP2).
- **Runbook** (`docs/release-runbook.md`): manual deploy (§1), manual rollback (§2), secret rotation (§3), incidents (§5 + playbooks validated in the EP7 drill in §5b), pre-release checklist (§7), TLS (§8), SLOs (§9), baseline (§10), retention (§11), and **scale-out + manual canary** (§12: weight flip 10→30→100 by hand-editing the upstream).
- **EP7 (Reliable) already delivered** what the canary needs: honest readiness (liveness ≠ readiness proven with a real `docker stop`), verified 30s graceful shutdown, incident playbooks, and a DR drill with pasted numbers.
- **EP6 (Scalable):** stateless instances, global rate limit in Redis (ADR 0002), per-instance L1 (ADR 0003), 2-instance scale-out + LB validated under 2× load.
- **Backup/restore:** `scripts/backup-mongodb.sh` (mongodump + `metadata.json` + 30d rotation) and `scripts/restore-mongodb.sh` — today **run by the operator**, with no timer in the repo, no manifest with row counts, no verified restore as a contract.
- **CI** (`.github/workflows/ci.yml`): unit-tests (+ boundary gate + doc-sync), observability (promtool/amtool + frozen metrics), integration-tests (Testcontainers + OWASP on verify), security-check, build (jar). k6 load test on `workflow_dispatch` (`load-test.yml`).
- **SLOs, burn-rate, dashboards, and frozen metrics** (EP3): they are the canary's "eyes".
- **Tags exist** (current `v0.13.0`) — but the pom is at `0.0.1-SNAPSHOT`, the tag triggers nothing in CI, and the jar validated by the pipeline **is not promoted anywhere** (no GitHub Release, no asset, no digest, no SBOM).

**What is really missing:**

1. **Release identity:** the artifact does not carry the tag's semver (the jar and image ship as `0.0.1-SNAPSHOT`/no label). There is no promotion path for the "validated" jar — whoever deploys recompiles by hand, and bit-for-bit equality with what CI validated is a matter of faith.
2. **Automated blue-green deploy:** the canary exists on paper (runbook §12.2) but requires hand-editing nginx + restart with a downtime window for the active instance. No fail-closed cutover, no readiness wait with a named budget, no `last-deploy` record.
3. **Runtime smoke:** nothing proves that the stack **is serving end to end** (the business contract: shorten → 302 → 404 → 410) after a deploy. The Dockerfile healthcheck is not enough — it does not exercise the critical path.
4. **Single-command rollback:** today it means re-running runbook §2 from memory. No `last-deploy.txt`, no incident one-liner.
5. **Automated and verified backup:** no timer in the repo (systemd `Persistent=true`), no manifest with per-collection row counts, no restore that **fails** on divergence, no restore drill as a release gate.
6. **Release workflow:** a `v*` tag does not trigger gates, does not run k6 on the artifact, does not produce a Release with assets (jar + sha256 + SBOM) and notes.

## Why this epic now?

- **EP6 (Scalable)** validated the multi-instance stateless topology + LB — blue-green only makes sense on top of it.
- **EP7 (Reliable)** delivered honest readiness, a 30s drain, and incident playbooks — the EP7 overview itself recorded: *"EP8 (Deployable) needs correct readiness, a drained shutdown, and an incident runbook — otherwise blue-green/canary turns into an outage"*. This epic honours that dependency.
- **EP3 (Observable)** provides the canary's "eyes": burn-rate, SLO dashboard, and frozen metrics to watch during the weight flip.
- **EP5 (Performance)** + k6: the release gate can **enforce** p95 < 200ms / error < 0.1% on the artifact before the tag becomes official.
- Without EP8, everything EP1–EP7 delivered only reaches production "by hand, from memory, at the mercy of the operator" — the Deployable pillar is what closes the loop.

**Out of scope (not doing in this epic):**

- K8s / orchestrator / multi-host / multi-AZ (the platform is on-prem bare metal — the ADR 0001 context).
- Mongo replica set, Redis Sentinel/Cluster (SPOF accepted and documented in EP7).
- Terraform/Ansible/Pulumi (host provisioning is the operator's job; this epic delivers units + scripts + runbook, it does not provision).
- Off-host backup (rsync/rclone to a second disk/host) — operator's responsibility; RPO target unchanged (last backup).
- Automatic deploy via SSH from CI (the human gate on the bare metal is a decision recorded in ADR 0008; CI produces the artifact and the operator runs `deploy.sh`).
- Canary auto-verified by metrics (automatic p95/burn gate between bumps) — today's canary = smoke after each bump + named manual surveillance in Grafana; it becomes TD if traffic demands it.
- JWT key rotation (EP2 debt) — not touched here.

**Acceptance criteria (grounded):**

1. `docs/release-engineering.md` (new) + ADR 0007 (blue-green on bare metal, rendered runtime conf, fail-closed cutover) + ADR 0008 (artifact promotion: the jar built by CI is what gets deployed, never a local rebuild) — the whole flow written down.
2. **Identity:** the jar and image carry the tag's semver (`revision` + flatten pattern); a `v*` tag triggers `release.yml` with: CHANGELOG gate (empty `[Unreleased]`), full `./mvnw verify` (all Maven + bash + promtool/amtool gates), **k6 gate with the SLO thresholds on the artifact**, **end-to-end runtime-smoke**, **restore drill with measured RTO** (≤ budget), and GitHub Release creation (the CI build's jar + sha256 + CycloneDX SBOM + notes).
3. `scripts/deploy.sh <tag>`: zero-downtime blue-green (blue :8080 / green :8081, concrete units with a jar per colour), canary 10/30/100 with 30s dwell and **smoke after each bump**, readiness wait with a named budget, `last-deploy.txt`, **fail-closed abort** (any failure → old colour at 100%, new colour drained and stopped, non-zero exit with the offending step named). Modes `--check` / `--init` / `--active` / `--self-test`.
4. `scripts/smoke.sh`: a single probe with business-contract legs (liveness, readiness, info, shorten `200` + `X-Request-Id`, redirect `302` + `Location`, unknown `404`, expired `410`, `HEAD` mirrors `GET` — the EP7 fix), **two consumers** (deploy.sh and CI).
5. `scripts/rollback.sh`: reads `last-deploy.txt` → previous colour returns to 100% **without rebuild** + incident one-liner; `--self-test`.
6. **Backup:** `deploy/systemd/url-shortener-backup.service` + `.timer` (`Persistent=true`, outside the 02:00 UTC retention purge and 01:10 UTC rollup windows); manifest with per-collection row counts in `backup-mongodb.sh`; `restore-mongodb.sh --verify` re-counts and **exits non-zero on any divergence**; `scripts/ci-restore-drill.sh` (isolated compose project, EP5/6/7 pattern) runs as a **release gate** with measured RTO.
7. `docs/release-runbook.md`: new flow (§1 deploy via `deploy.sh`, §2 rollback via `scripts/rollback.sh`, pre-release checklist extended with "migrations since the last deploy are expand-only" + "backup younger than 26h" + "green drill on the release", a post-deploy verification section, and a "deploy failed" incident).
8. `./mvnw verify` green with all gates + the new scripts' self-tests green (`--self-test` in the repo pattern: a gate that bites).
9. **Rule zero — zero-from-memory:** every number, sha, or count is pasted from real output.

**Quick traceability:**

| Story | Reference doc | Key aspect |
|-------|----------------|---------------|
| 8.1 | `docs/release-engineering.md` + ADR 0007/0008 | Written release contract |
| 8.2 | pom (`revision`) + Dockerfile + CHANGELOG gate | Artifact identity |
| 8.3 | `scripts/deploy.sh` + blue/green units | Blue-green fail-closed |
| 8.4 | `scripts/smoke.sh` + `scripts/rollback.sh` | Runtime proof + one-line rollback |
| 8.5 | systemd timer + manifest + `ci-restore-drill.sh` | Scheduled and verified backup |
| 8.6 | `.github/workflows/release.yml` + runbook | Release as a gate |

---

*Next step: execute stories 8.1–8.6 (`epic-8-technical-tasks.md`) and paste evidence into `epic-8-dod.md`.*
