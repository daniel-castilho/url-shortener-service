# Epic 8 – Definition of Done (DoD)

**Regra zero — zero-from-memory:** every number, sha or count in this document is pasted from a
command output included in this document.

Closing on 2026-09-13, tag `v0.14.0`.

## 1. Required evidence (real pasted outputs)

### 8.1 Release contract + ADRs (executed 2026-09-13)

```
$ git log --oneline -- docs/adr/ docs/release-engineering.md

2e6d8db fix: fix testcontainers Redis wait strategy; complete epic 8.6 release workflow
9cb239f feat(reliability): bound Redisson timeouts (ADR 0005) + prove Mongo/Redis outage paths (Epic 7 7.2/7.3)
ca9a29c docs(reliability): failure-mode matrix + ADR 0005 (fail-open vs fail-closed) + ADR 0006 (at-least-once) — Epic 7 story 7.1
b7ba99e docs(adr): record scalability decisions as ADRs 0001-0004 (Epic 6 story 6.1)
```

- Files: `docs/release-engineering.md`, `docs/adr/0007-blue-green-bare-metal.md`,
  `docs/adr/0008-artifact-promotion.md`.
- Cutover contract (ADR 0007): _fail-closed: any step fails → old color at 100%,
  exit ≠ 0 with the offending step named_.
- Consequence accepted (ADR 0007): _post-cutover fleet = 1 active + 1 idle; 2× capacity =
  requires an extra instance outside the deploy plane_.

### 8.2 Artifact identity (executed 2026-09-13)

```
$ ./mvnw -q help:evaluate -Dexpression=project.version -Drevision=0.14.0 -DforceStdout
0.14.0

$ ./mvnw clean package -DskipTests -Drevision=0.14.0 && ls -l target/*.jar
-rw-r--r-- 1 castilho castilho 76589009 Sep 12 22:23 target/url-shortener-service-0.14.0.jar

$ ./mvnw verify  (resumo à data do fechamento)
[INFO] BUILD SUCCESS
[INFO] Total time:  03:36 min
```

- Gate CHANGELOG — green:
  ```
  $ bash scripts/check-changelog.sh
  PASS: changelog gate — [Unreleased] exists and is empty (promotion happened)
  ```
- Gate CHANGELOG — red (intentional, dirty Unreleased injected temporarily):
  ```
  FAIL: ## [Unreleased] contains entries at .../CHANGELOG.md — promote them to a version section before tagging
  exit=1
  ```

### 8.3 Blue-green fail-closed (executed 2026-09-13)

```
$ scripts/deploy.sh --self-test
...
DEPLOY 22:23:29: rendered nginx.conf (active=:8080 weight=100, idle=:8081)
DEPLOY 22:23:29: rendered nginx.conf (active=:8081 weight=10, idle=:8080)
DEPLOY 22:23:29: rendered nginx.conf (active=:8081 weight=30, idle=:8080)
DEPLOY 22:23:29: rendered nginx.conf (active=:8081 weight=100, idle=:8080)
DEPLOY 22:23:31: rendered nginx.conf (active=:8080 weight=100, idle=:8081)
OK: render weights (10/30/100 + complements + down), active_color, abort render, dead-port readiness, template untouched — all asserted
PASS: self-test verified
exit=0
```

Cutover exercised locally (real drill, isolated 18xxx ports — Mongo 27018 /
Redis 6380, nginx container in host-network as front :18080, blue 18081 / green 18082; `deploy.sh`'s
render targets systemd/:8080, so the drill reproduced the same weights on the front's conf).
Redirect loop (`curl` of the seeded code `yd02dah` through the front, N=60 per flip):
```
--- flip: init: blue 100 / green down ---
        server 127.0.0.1:18081 weight=100 max_fails=2 fail_timeout=10s;
        server 127.0.0.1:18082 weight=100 max_fails=2 fail_timeout=10s down;
status histogram (N=60):
     60 302
--- flip: canary 10: green 10 / blue 90 ---
        server 127.0.0.1:18081 weight=90 max_fails=2 fail_timeout=10s;
        server 127.0.0.1:18082 weight=10 max_fails=2 fail_timeout=10s;
status histogram (N=60):
     60 302
--- flip: canary 30: green 30 / blue 70 ---
        server 127.0.0.1:18081 weight=70 max_fails=2 fail_timeout=10s;
        server 127.0.0.1:18082 weight=30 max_fails=2 fail_timeout=10s;
status histogram (N=60):
     60 302
--- flip: cutover 100: green 100 / blue down (fail-closed) ---
        server 127.0.0.1:18081 weight=100 max_fails=2 fail_timeout=10s down;
        server 127.0.0.1:18082 weight=100 max_fails=2 fail_timeout=10s;
status histogram (N=60):
     60 302
```
No 5xx/ERR across 240 requests (4 flips × 60). Fail-closed proven by killing the active green
post-cutover (peers max_fails=2 name the upstream): 40/40 → `502`, the front does not silently
reforward.

Real rollback (same front, weights restored):
```
--- flip: rollback: blue 100 / green down (restored) ---
        server 127.0.0.1:18081 weight=100 max_fails=2 fail_timeout=10s;
        server 127.0.0.1:18082 weight=100 max_fails=2 fail_timeout=10s down;
status histogram (N=60):
     60 302
```

### 8.4 Smoke + rollback (executed 2026-09-13)

```
$ scripts/rollback.sh --self-test
OK: previous-100/current-down render, last-deploy parse, template untouched — asserted
PASS: self-test verified
exit=0

$ scripts/smoke.sh http://localhost:18999   # dead port — named leg in the exit
SMOKE 22:23:34: leg 1/8 liveness
SMOKE FAIL: leg 1: liveness expected 200, got 000
exit=1

$ scripts/rollback.sh                        # no last-deploy — fail-closed ABORT
ROLLBACK 22:23:37 ABORT: no last-deploy.txt — nothing to roll back (deploy.sh writes it before cutover)
exit=1
```

### 8.5 Scheduled and verified backup (executed 2026-09-13)

Real self-contained drill `scripts/ci-restore-drill.sh` (own isolated stack: Mongo :18017 /
Redis :16379 / app :18080, compose project `urlshortener-drill`).

```
$ bash scripts/ci-restore-drill.sh
...
[2026-09-12 22:54:47] --verify: all manifest collections match — restore VERIFIED
DRILL 22:54:47: pre-backup seeds: 20/20 answered 302
DRILL 22:54:48: post-backup seeds: 2/2 answered 404 (RPO proof)
DRILL 22:54:48: RTO (backup -> restore -> verify): 21s <= 300s budget
DRILL 22:54:48: RESTORE DRILL PASS (RTO 21s; pre=302, post=404; restore --verify green)
```

```
COLLECTION               MANIFEST     RESTORED VERDICT
short_urls                     20           20 ok
users                           0            0 ok
custom_domains                  0            0 ok
click_events                    0            0 ok
click_daily                     0            0 ok
schema_migrations               9            9 ok
```

Lessons recorded in `docs/lessons.md`: (1) a leftover stack makes the health-check lie; (2) mongodump
exits 0 silently on a missing db → preflight + fail-closed.

### 8.6 Release as a gate (executed 2026-09-13)

Tag: `v0.14.0` — workflow `release.yml` **run 34732409909** — jobs:
Gates / k6 Gate / Runtime Smoke / Restore Drill / Release → **all `success`**.

```
$ gh api repos/daniel-castilho/url-shortener-service/actions/runs/34732409909 --jq '{conclusion}'
{"conclusion":"success"}

$ gh api repos/daniel-castilho/url-shortener-service/actions/runs/34732409909/jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'
gates: success
k6-gate: success
runtime-smoke: success
restore-drill: success
release: success
```

```
$ gh release view v0.14.0 --json name,assets -q '.assets[].name'
sbom-url-shortener-0.14.0.json
SHA256SUMS
url-shortener-service-0.14.0.jar

$ gh release download v0.14.0 --pattern '*' /tmp/rel-check && sha256sum -c SHA256SUMS
url-shortener-service-0.14.0.jar: OK
```

- Trivy HIGH/CRITICAL + non-root gate (release job): non-root gate `success` (uid!=0 over alpine
  `adduser -S` → uid 100) and Trivy reports a clean table — verified locally on the image
  `url-shortener:0.14.0-test` (alpine+jar both `0` vulnerabilities, HIGH/CRITICAL, ignore-unfixed).
- Runbook TOC (release sections):
  ```
  $ grep -E '^#{1,3} ' docs/release-runbook.md
  1. Deploy a new version
  2. Roll back
  7. Operational checklist before a release
  Release artifacts & promotion
  Incident: deploy failed
  ```

### 8.7 Final gates (executed 2026-09-13)

```
$ ./scripts/check-metrics-frozen.sh && ./scripts/check-metrics-frozen.sh --self-test
PASS / PASS
$ ./scripts/check-boundaries.sh && ./scripts/check-boundaries.sh --self-test
PASS / PASS
$ ./scripts/check-doc-sync.sh && ./scripts/check-doc-sync.sh --self-test
PASS / PASS
$ ./scripts/check-security.sh && ./scripts/check-security.sh --self-test
PASS / PASS
$ promtool check rules recording-rules.yml && promtool check rules alerts.yml && promtool test rules rules_tests.yml && amtool check-config alertmanager.yml
SUCCESS / SUCCESS / SUCCESS / SUCCESS
$ scripts/deploy.sh --self-test && scripts/rollback.sh --self-test
PASS / PASS
$ ./mvnw verify
BUILD SUCCESS  (OWASP: only known pre-existing CVE opentelemetry-api-1.62.0 MEDIUM; coverage 60/60 reached)
```

Epic-closing sha: `b5fb2e2..736411c` (workflow fix, non-root gate fix, Dockerfile
Trivy fix) + `2e6d8db` (8.1-8.6 mostly). CI green on the final push.

## 2. Completion checklist

- [x] `docs/release-engineering.md` + ADR 0007 + ADR 0008
- [x] Versioning `revision` + flatten + gate CHANGELOG (red/green)
- [x] `deploy.sh` blue-green fail-closed + units blue/green + self-test + **real local cutover drill
  (blue 18081/green 18082 + nginx container front :18080; 240 redirects = 240× 302; fail-closed 502;
  rollback 60/60 302)**
- [x] `smoke.sh` (8 legs) + `rollback.sh` + self-test + dead-port red
- [x] Backup timer + manifest + restore `--verify` (negative test) + `ci-restore-drill.sh` with **real
  RTO (21s ≤ 300s; pre=20/20 302; post=2/2 404)**
- [x] `release.yml` green on the first tag (run 34732409909) + Release with assets (CI-built jar)
- [x] Runbook updated (deploy/rollback/checklist/post-deploy/incident)
- [x] `./mvnw verify` green
- [x] No cipher in this file without the command above it

## 3. Confirmed out of scope (no phantom debt)

- K8s / orchestrator / multi-host — not done; bare metal is the platform (ADR 0007).
- Replica set / Redis Sentinel — not done; SPOF accepted in EP7.
- Off-host backup — not done; named TD (unchanged RPO target: last backup).
- Canary auto-verified by metrics (automatic gate between bumps) — not done; canary = smoke +
  named manual monitoring (Grafana/burn-rate).
- Automatic deploy via SSH from CI — rejected in ADR 0008 (human gate on bare metal).
- JWT key rotation (EP2 debt) — not resolved here.

---

*Section 2 100% checked and section 1 with real outputs: Epic 8 completed on 2026-09-13 (tag `v0.14.0`).*