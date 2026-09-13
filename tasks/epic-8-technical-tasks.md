# Epic 8 – Technical Tasks

Checkboxes `[x]` are marked during execution; evidence is pasted in `epic-8-dod.md`.

## 8.1 Release contract + ADRs

- [ ] Create `docs/release-engineering.md`: tag → gates → GitHub Release → `deploy.sh` → post-deploy verification flow; step × executor (CI × operator) × failure × behaviour (fail-closed) table.
- [ ] ADR `docs/adr/0007-blue-green-bare-metal.md`: context (manual canary §12.2; single shared jar in the template prevents a per-colour artifact) / decision (concrete blue+green units, rendered runtime conf, fail-closed cutover, canary 10/30/100 with 30s dwell, `last-deploy.txt`) / consequences (fleet 1 active + 1 idle; 2× capacity = an extra instance outside the plan) / rejected (k8s, rolling without canary, in-place fleet update, compose for the app).
- [ ] ADR `docs/adr/0008-artifact-promotion.md`: the gates job's jar is the promoted one (single build); `deploy.sh` downloads the exact Release jar (never a local rebuild); secondary image (non-root + Trivy + SBOM); immutable tag (fix-forward); rejected (SSH-deploy, mandatory registry, `release:perform`).
- [ ] Tie the document to the frozen series the canary watches (`http.server.requests`, `redirect.latency`, `shorten.latency`, `schema.migrations.*` — `docs/slos.md`).
- [ ] Paste `git log --oneline -- docs/adr/ docs/release-engineering.md` in the DoD.

## 8.2 Artifact identity (versioning + CHANGELOG gate)

- [ ] `pom.xml`: `<revision>0.0.1-SNAPSHOT</revision>` + `<version>${revision}</version>` + `flatten-maven-plugin` (`resolveCiFriendliesOnly`, `flatten` goal on `process-resources` / `flatten.clean` on `clean`).
- [ ] Verify that **no** gate breaks with flatten: `./mvnw verify` green (JaCoCo, SpotBugs, Spotless, OWASP, ArchUnit, metrics-frozen) — output pasted. If any plugin assumes a literal version (e.g. the artifact name), fix it at the source, not with a workaround.
- [ ] Resolution evidence: `./mvnw -q help:evaluate -Dexpression=project.version -Drevision=0.14.0 -DforceStdout` → `0.14.0`; `./mvnw clean package -DskipTests -Drevision=0.14.0` → `target/url-shortener-service-0.14.0.jar` (name pasted).
- [ ] `Dockerfile`: `ARG VERSION=local` + `LABEL org.opencontainers.image.version="${VERSION}"` (the `COPY --from=build` of the jar continues to work).
- [ ] CHANGELOG gate (step in `release.yml`): fails if `## [Unreleased]` in `CHANGELOG.md` at the tag commit is missing **or** contains entries (`###` or content lines). Prove red/green: green run on the real tag + intentionally red run (throwaway branch with a dirty Unreleased, screenshot/output pasted).
- [ ] HTTP contracts read from the code and pasted in the DoD (not invented): `ShortenResponse{String id, String shortUrl}`; `POST /api/v1/urls` → 200 (`@ApiResponse`); `GET /{id}` → 302/404/410; `X-Request-Id` echoed (`RequestCorrelationFilter`); `HEAD` mirrors `GET` (EP7 fix).

## 8.3 Blue-green with fail-closed canary (`scripts/deploy.sh`)

- [ ] Concrete units `deploy/url-shortener-blue.service` (:8080, `WorkingDirectory`/jar `/opt/url-shortener/blue/url-shortener.jar`, `SyslogIdentifier=url-shortener-blue`) and `deploy/url-shortener-green.service` (:8081, `.../green/...`) — hardening identical to the template (`Type=notify`, `KillMode=mixed`, `KillSignal=SIGTERM`, `TimeoutStopSec=30`, `NoNewPrivileges=true`, `ProtectSystem=strict`, `Restart=on-failure`). The `url-shortener@.service` template is **not deleted** (scale-out §12) — add a comment in it that the deploy plan is blue/green.
- [ ] Normalize the `deploy/proxy/nginx.conf` upstream: two active lines (`server 127.0.0.1:8080 weight=100 ...` + `server 127.0.0.1:8081 weight=100 ...`, the 8081 one uncommented) — the deploy render replaces exactly those two lines.
- [ ] `deploy/runtime/` → `.gitignore` (render target + `last-deploy.txt`, never committed).
- [ ] `scripts/deploy.sh` (bash, `set -euo pipefail`):
  - [ ] `--init` (renders runtime conf blue 100 / green down), `--check <tag>` (plan + last deploy, zero mutation), `--active` (active colour from the runtime conf), `--self-test` (render in a temp dir + weight assertions + **proof of the abort**: `READY_BUDGET_SECONDS=1` against a dead port → old colour back to 100%, exit ≠ 0 with the step named). Self-test output pasted.
  - [ ] Flow: download the Release jar by semver (API `gh release download` / cURL API) + **verify sha256** against the published asset → stage in `/opt/url-shortener/<cor>/` (write via named sudo/cp; user `urlshortener`) → `systemctl restart` the idle colour (graceful 30s) → readiness wait `http://127.0.0.1:<porta>/actuator/health/readiness` with `READY_BUDGET_SECONDS=90` (comment in the script: Dockerfile HEALTHCHECK = start-period 40s; 90 covers prod boot + fail-fast migrator) → canary 10/30/100: render → `nginx -t` → `nginx -s reload` → `scripts/smoke.sh` → 30s dwell → success: `last-deploy.txt` (previous/current/tag/at) + drain and `systemctl stop` the old colour + one-liner.
  - [ ] **Fail-closed at every step:** trap/check — any failure (download, sha256, readiness timeout, `nginx -t`, smoke) → render the old colour at 100% → `nginx -t` + reload → drain/stop the new colour → exit ≠ 0 with the offending step named. The nginx template is **never** written by the script (render reads the template → writes `deploy/runtime/nginx.conf` in tmp and `cat`s it up — never awk in-place on the same path, dargent E12 S2 lesson).
- [ ] `bash -n scripts/deploy.sh` + green self-test; full simulation on a local stack (compose + jar + local nginx or `--check`/`--init` + asserted render) with outputs pasted.

## 8.4 Smoke + rollback (`scripts/smoke.sh`, `scripts/rollback.sh`)

- [ ] `scripts/smoke.sh <base>`: 8 legs (liveness 200 → readiness 200 → info 200 → shorten 200 + `id`/`shortUrl` + `X-Request-Id` → `GET /<id>` 302 + `Location`==originalUrl → `HEAD /<id>` 302 → `GET /zzzzzzz` 404 → shorten `ttlSeconds:1` + poll 410 within ≤15s). Exit ≠ 0 naming the leg. `originalUrl` unique per run (`https://<base>/smoke/$(date +%s%N)`).
- [ ] Run the smoke against the local dev stack (compose + jar, default rate limits) → green pasted.
- [ ] `scripts/rollback.sh`: parse `last-deploy.txt` (machine: `previous`/`current`/`tag`/`at`) → `systemctl start` the previous colour (no-op if already up) → render (previous 100 / current down, via tmp + `cat`) → `nginx -t` + reload → `smoke.sh` → incident one-liner. `--self-test` with a synthetic last-deploy in a temp dir; output pasted.
- [ ] Last item: smoke + rollback exercised together on a local stack (deploy fake tag → rollback → both weight directions asserted) — output pasted.

## 8.5 Scheduled, manifested, and verified backup

- [ ] `scripts/backup-mongodb.sh`: generate `manifest.json` (ISO timestamp, db, `mongo --version`, dump size, row counts of `short_urls`, `users`, `custom_domains`, `click_events`, `click_daily`, `schema_migrations` via `mongosh --quiet --eval 'db.<c>.countDocuments()'`); keep the 30d rotation; if `mongosh`/`mongodump` are not in PATH, document the `docker exec urlshortener-mongo` fallback in the header (EP7 drill pattern) and fail closed with a clear message.
- [ ] `scripts/restore-mongodb.sh --verify <dir>`: after the restore, re-count the same collections and compare with the manifest; printed table; **exit ≠ 0 on any divergence** (missing collection, lower count, unreadable manifest). Self-test: corrupted manifest → exit ≠ 0 (pasted).
- [ ] `deploy/systemd/url-shortener-backup.service` (`Type=oneshot`, `User=<dedicado>`, `After=docker.service`, `ExecStart=/opt/url-shortener/scripts/backup-mongodb.sh /var/backups/url-shortener`) + `.timer` (`OnCalendar=*-*-* 03:30:00`, `Persistent=true`, comment with the window rationale: retention purge 02:00 UTC / rollup 01:10 UTC).
- [ ] `scripts/ci-restore-drill.sh` (body of the `restore-drill` job, locally executable): isolated compose project `urlshortener-drill` (port override 18xxx — EP5/6/7 pattern; `down -v` only destroys the drill volume) → app (current tag/branch jar, relaxed rate limits) on 18080 → seed 20 codes via API (list in the DoD) + 2 post-backup → `backup-mongodb.sh` (MONGODB_URI→18017) → `mongosh drop short_urls` → `restore-mongodb.sh --verify` → asserts: pre-backup **302** / post-backup **404** → **wall-clock RTO** (backup→restore→verify) vs `RTO_BUDGET_S=300` → artifact of the manifests.
- [ ] Run the drill locally (or via the release `workflow_dispatch` before the first tag) → outputs + RTO pasted.
- [ ] Runbook: routine section (timer `systemctl enable --now url-shortener-backup.timer`) + pre-release item `ls -lt /var/backups/url-shortener | head -1` (backup younger than 26h).

## 8.6 Release workflow + runbook

- [ ] `.github/workflows/release.yml`: trigger tag `v*` only; `permissions: contents: read` top-level; jobs `gates` → `k6-gate` / `runtime-smoke` / `restore-drill` → `release` (needs all of them). Jobs per story 8.6: gates (`verify -Drevision` + bash gates + self-tests + pinned promtool/amtool + CHANGELOG gate + jar upload), k6-gate (mongo+redis services, relaxed app rate limits, `k6 run load-tests/mixed.js`, artifact always), runtime-smoke (artifact jar + `smoke.sh` + `verify-graceful-shutdown.sh`), restore-drill (script 8.5), release (image + non-root gate + Trivy HIGH/CRITICAL SHA-pinned + CycloneDX SBOM + sha256 of the artifact jar + `gh release create` with notes = tag annotation + `--generate-notes`; `permissions: contents: write, packages: write` only in this job).
- [ ] `docs/release-runbook.md`: §1 (deploy via `deploy.sh` + post-deploy verification: smoke + 10 min burn-rate on the SLO dashboard + `schema.migrations.*` in the new colour's logs), §2 (`rollback.sh`; manual becomes a named fallback), §7 checklist extended (annotated tag; empty Unreleased; `V*` migrations since the `last-deploy.txt` tag **expand-only** — rationale: the fail-fast migrator protects, but a destructive migration breaks the old colour still serving mid-cutover; backup younger than 26h; green drill on the release), new section "Release artifacts & promotion" and "deploy failed" incident (one-liner + rollback + when to call the §5b Mongo/Redis playbook).
- [ ] First test tag: `v0.14.0-epic8` (or `v0.14.0`) on `main` post-epic — end-to-end green workflow; Release assets listed in the DoD (name + sha256 + SBOM).
- [ ] Closing: item in the `AGENTS.md` debt matrix (EP8 → resolved with sha) + Deployable line in the README Current State; `check-doc-sync` green.

## 8.7 Final epic gates

- [ ] `./scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-boundaries.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-doc-sync.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-security.sh` (+ `--self-test`) → PASS.
- [ ] `promtool check rules` + `promtool test rules` + `amtool check-config` → green.
- [ ] `scripts/deploy.sh --self-test` + `scripts/rollback.sh --self-test` + `smoke.sh` green + `ci-restore-drill.sh` green → PASS.
- [ ] `./mvnw verify` full → BUILD SUCCESS.
- [ ] Evidence pasted in `epic-8-dod.md`; rule-zero self-audit.

---

**Epic 8 completion checklist:**

- [ ] `docs/release-engineering.md` + ADR 0007 + ADR 0008
- [ ] `revision` versioning + flatten + CHANGELOG gate (red/green proven)
- [ ] `deploy.sh` blue-green fail-closed + blue/green units + self-test
- [ ] `smoke.sh` (8 legs, 2 consumers) + `rollback.sh` + self-test
- [ ] Backup timer + manifest + `--verify` + `ci-restore-drill.sh` with RTO
- [ ] `release.yml` end-to-end green on the first tag + Release with assets
- [ ] Runbook updated (deploy/rollback/checklist/post-deploy/incident)
- [ ] `./mvnw verify` green (all gates)
- [ ] Evidence pasted in `epic-8-dod.md`

*When all items above are checked, Epic 8 is **complete**, with the "tag → production" path automated, contracted, and proven.*
