# Epic 8 – Testing Strategy

Principle: test the **deploy contract** (fail-closed, zero-downtime, rollback, verified backup), not the bash implementation. The business happy path was already done in EP4/5/6; dependency failure was already done in EP7. Here the test is: **the release pipeline and the cutover do what ADR 0007/0008 promise, and fail the way they promise.**

## 8.1 Release contract

- **Goal:** Reviewable decisions; the operator does not depend on the author's memory; the tag→production flow is readable in one session.
- **Action:** `docs/release-engineering.md` + ADR 0007/0008; `git log` pasted.
- **Acceptance criteria:** full flow written (step × executor × failure × behaviour); rejections written (k8s, SSH-deploy, mandatory registry, rolling without canary); the 1 active + 1 idle fleet consequence accepted and documented.
- **Do not:** invent new metrics to "watch the deploy" — the canary uses the existing frozen series (`http.server.requests`, `*.latency`, `schema.migrations.*`). If a new need arises, it goes through the freeze gate **in the same PR**.

## 8.2 Artifact identity

- **Goal:** `0.14.0` on the tag = `url-shortener-service-0.14.0.jar` promoted; nobody recompiles by hand.
- **Action:**
  - `./mvnw -q help:evaluate -Dexpression=project.version -Drevision=0.14.0 -DforceStdout` → `0.14.0` (pasted).
  - `./mvnw clean package -DskipTests -Drevision=0.14.0` → jar name pasted (`ls target/*.jar`).
  - Full `./mvnw verify` green with flatten (no gate regressed) — output pasted.
  - CHANGELOG gate red/green: real tag green run + intentionally red run (dirty Unreleased on a throwaway branch) — both outputs pasted.
- **Acceptance criteria:** tag semver propagated to the jar and the image label; a tag with a dirty Unreleased **cannot** create a Release.
- **Do not:** `mvn release:perform` or git-flow versioning — the `revision`+tag pattern is the chosen one (ADR 0008); do not add another mechanism.

## 8.3 Blue-green fail-closed

- **Goal:** The zero-downtime cutover happens on success and **aborts to the old colour** on any failure — both paths proven, not narrated.
- **Action:**
  - `scripts/deploy.sh --self-test`: render in a temp dir with weight assertions (10/30/100 and complements), `nginx -t` when available, and **proof of the abort** (`READY_BUDGET_SECONDS=1` against a dead port → runtime conf back with the old colour at 100%, exit ≠ 0, step named). Output pasted.
  - Full local exercise (dev stack: compose + jar + nginx or asserted render): deploy a "fake" tag → canary 10/30/100 with green smoke on each bump → correct `last-deploy.txt` → old colour stopped. Paste the runtime conf weights at each bump + the smoke summary.
  - Zero-downtime of the cutover: during the 10→30→100 flip, a redirect `curl` loop (the EP7 seeds or the smoke ones) receives no 5xx/connection-refused — count pasted (allowance: the only acceptable downtime is the `systemctl restart` of the **idle** colour, which carries no traffic — assert with `--active` before the restart).
- **Acceptance criteria:** green self-test; local exercise with outputs; abort proven with the old colour back at 100%.
- **Do not:** mock `systemctl` — the self-test tests render + abort logic; the local exercise tests real systemd (operator host/VM, evidence in the DoD). Do not mutate the `deploy/proxy/nginx.conf` template in any assertion.

## 8.4 Smoke + rollback

- **Goal:** the probe is **crucial and cheap enough** to run on every bump and every release; the rollback undoes without rebuild.
- **Action:**
  - `smoke.sh` against the local dev stack (default rate limits — the 8 legs coexist with the 60/min bucket, 2 shortens per run): green pasted.
  - **Expiry leg** (410): `ttlSeconds: 1` + poll ≤15s — validates the eager expiry contract (expired never leaves the cache) without depending on the TTL index (purge ~60s).
  - CI `runtime-smoke` (job in `release.yml`): mongo+redis services + artifact jar → `smoke.sh` → `verify-graceful-shutdown.sh` (EP7 drain: zero connection-refused). Run pasted.
  - `rollback.sh --self-test` (synthetic last-deploy → render assertions of the previous colour) + local exercise: fake deploy → rollback → weights asserted (previous 100 / current down) + one-liner printed.
- **Acceptance criteria:** 8 legs green on a real stack; rollback symmetric to the deploy (same render mechanism); failed leg named in the exit (prove by running against a dead port — exit message pasted).
- **Do not:** smoke must not create a user or authenticate (anonymous is the public shorten contract); smoke must not follow redirects (`curl -o /dev/null -w '%{http_code}'` + inspect `Location`, never `-L`).

## 8.5 Scheduled and verified backup

- **Goal:** the backup runs on its own and the restore is a **contract that fails** — divergence is exit ≠ 0, not a yellow log.
- **Action:**
  - `backup-mongodb.sh` on the dev stack → `manifest.json` pasted (real counts).
  - `restore-mongodb.sh --verify` green (isolated/parallel dev stack) + **negative test**: corrupted manifest (divergent count) → exit ≠ 0 with the divergence table pasted.
  - Full `ci-restore-drill.sh` (isolated `urlshortener-drill` project): pre seeds=302 / post=404 (EP7 RPO semantics), measured RTO ≤ 300s, manifests as artifacts. Output + RTO pasted.
  - Timer: `systemd-analyze verify` when available + `systemctl list-timers` on the staging host pasted (CI has no systemd: the timer is host evidence, not CI evidence — state it).
- **Acceptance criteria:** divergent restore **fails**; green drill with RTO; timer enabled on the staging host.
- **Do not:** destroy the day-to-day dev volume — the drill is isolated by project + 18xxx ports (EP5/6/7 pattern, `down -v` only reaches `urlshortener-drill`). Do not require off-host backup in this epic (out of scope, named TD).

## 8.6 Release as a gate

- **Goal:** the first real tag proves the whole pipeline; no CI gate is skipped on the road to release.
- **Action:**
  - Tag `v0.14.0` (or `v0.14.0-epic8`) on `main` → end-to-end `release.yml` workflow: gates (verify + bash + promtool/amtool + CHANGELOG), k6-gate (SLO thresholds on the artifact — the k6 exit is the gate), runtime-smoke, restore-drill, release (non-root gate, Trivy HIGH/CRITICAL, SBOM, `gh release create`).
  - Release artifacts listed in the DoD: `url-shortener-service-<semver>.jar` + sha256 (the same sha256 that `deploy.sh` will verify), `SHA256SUMS`, CycloneDX SBOM, notes.
  - `deploy.sh <tag>` on the staging host downloading **that** Release jar (sha256 verified on download) — pasted.
- **Acceptance criteria:** 100% green workflow; Release existing with assets; Release jar = the jar the deploy consumes (same sha256 in both places).
- **Do not:** `if: always()` hiding a gate failure (the release needs **all** jobs green); Trivy with `ignore-failure: true`; skipping the k6-gate "because CI already ran" — the tag artifact is what k6 validates.

## 8.7 Retro-compatible integration (gates)

- [ ] Full `./mvnw verify` → green (JaCoCo floors, SpotBugs, Spotless, OWASP, ArchUnit, doc-sync, metrics-frozen).
- [ ] `promtool` + `amtool` green.
- [ ] Self-tests of the new scripts green (`deploy.sh`, `rollback.sh`, the restore `--verify`).
- [ ] No new series without going through the freeze gate (if something creates a metric, update `scripts/check-metrics-frozen.sh` **in the same PR**).
- [ ] `.gitignore` covers `deploy/runtime/` (and k6/dev-dump artifacts) — `git status` clean after the exercises.

---

**Epic 8 completion checklist:**

- [ ] Matrix/flow + ADRs 0007/0008
- [ ] Versioning + CHANGELOG gate (red/green)
- [ ] `deploy.sh` self-test + local exercise (abort proven)
- [ ] `smoke.sh` 8 legs + `rollback.sh` self-test + exercise
- [ ] Backup manifest + restore `--verify` (negative test) + drill with RTO
- [ ] `release.yml` green on the first tag + Release with assets
- [ ] `./mvnw verify` green
- [ ] Evidence pasted in `epic-8-dod.md`

*When all items above are checked, Epic 8 is **complete**.*
