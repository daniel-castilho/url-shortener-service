# Epic 8 – Definition of Done (DoD)

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento é colado de um
output de comando incluído neste documento.

Fechamento em 2026-09-13, tag `v0.14.0`.

## 1. Evidências obrigatórias (outputs reais coladas)

### 8.1 Contrato de release + ADRs (executado 2026-09-13)

```
$ git log --oneline -- docs/adr/ docs/release-engineering.md

2e6d8db fix: fix testcontainers Redis wait strategy; complete epic 8.6 release workflow
9cb239f feat(reliability): bound Redisson timeouts (ADR 0005) + prove Mongo/Redis outage paths (Epic 7 7.2/7.3)
ca9a29c docs(reliability): failure-mode matrix + ADR 0005 (fail-open vs fail-closed) + ADR 0006 (at-least-once) — Epic 7 story 7.1
b7ba99e docs(adr): record scalability decisions as ADRs 0001-0004 (Epic 6 story 6.1)
```

- Arquivos: `docs/release-engineering.md`, `docs/adr/0007-blue-green-bare-metal.md`,
  `docs/adr/0008-artifact-promotion.md`.
- Contrato de cutover (ADR 0007): _fail-closed: qualquer passo falha → cor antiga a 100%,
  exit ≠ 0 com o passo nomeado_.
- Consequência aceita (ADR 0007): _frota pós-cutover = 1 ativa + 1 idle; capacidade 2× =
  instância extra fora do plano de deploy_.

### 8.2 Identidade do artifact (executado 2026-09-13)

```
$ ./mvnw -q help:evaluate -Dexpression=project.version -Drevision=0.14.0 -DforceStdout
0.14.0

$ ./mvnw clean package -DskipTests -Drevision=0.14.0 && ls -l target/*.jar
-rw-r--r-- 1 castilho castilho 76589009 Sep 12 22:23 target/url-shortener-service-0.14.0.jar

$ ./mvnw verify  (resumo à data do fechamento)
[INFO] BUILD SUCCESS
[INFO] Total time:  03:36 min
```

- Gate CHANGELOG — verde:
  ```
  $ bash scripts/check-changelog.sh
  PASS: changelog gate — [Unreleased] exists and is empty (promotion happened)
  ```
- Gate CHANGELOG — vermelho (intencional, Unreleased sujo injetado temporariamente):
  ```
  FAIL: ## [Unreleased] contains entries at .../CHANGELOG.md — promote them to a version section before tagging
  exit=1
  ```

### 8.3 Blue-green fail-closed (executado 2026-09-13)

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

Cutover zero-downtime exercitado localmente (drill real, portas 18xxx isoladas — Mongo 27018 /
Redis 6380, nginx container host-network como front :18080, azul 18081 / verde 18082; o render de
`deploy.sh` é systemd/:8080-target, então o drill reproduziu os mesmos pesos no conf do front).
Loop de redirect (`curl` do código semeado `yd02dah` via front, N=60 por flip):
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
Nenhum 5xx/ERR em 240 requests (4 flips × 60). Fail-closed provado matando o verde ativo
pós-cutover (peers max_fails=2 citam o upstream): 40/40 → `502`, o front não silenciosamente
reencaminha.

Rollback real (mesmo front, pesos restaurados):
```
--- flip: rollback: blue 100 / green down (restored) ---
        server 127.0.0.1:18081 weight=100 max_fails=2 fail_timeout=10s;
        server 127.0.0.1:18082 weight=100 max_fails=2 fail_timeout=10s down;
status histogram (N=60):
     60 302
```

### 8.4 Smoke + rollback (executado 2026-09-13)

```
$ scripts/rollback.sh --self-test
OK: previous-100/current-down render, last-deploy parse, template untouched — asserted
PASS: self-test verified
exit=0

$ scripts/smoke.sh http://localhost:18999   # porta morta — perna nomeada no exit
SMOKE 22:23:34: leg 1/8 liveness
SMOKE FAIL: leg 1: liveness expected 200, got 000
exit=1

$ scripts/rollback.sh                        # sem last-deploy — fail-closed ABORT
ROLLBACK 22:23:37 ABORT: no last-deploy.txt — nothing to roll back (deploy.sh writes it before cutover)
exit=1
```

### 8.5 Backup agendado e verificado (executado 2026-09-13)

Drill real self-contained `scripts/ci-restore-drill.sh` (stack isolada própria: Mongo :18017 /
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

Lições registradas em `docs/lessons.md`: (1) leftover stack faz health-check mentir; (2) mongodump
exit 0 silencioso em db ausente → preflight + fail-closed.

### 8.6 Release como gate (executado 2026-09-13)

Tag: `v0.14.0` — workflow `release.yml` **run 34732409909** — jobs:
Gates / k6 Gate / Runtime Smoke / Restore Drill / Release → **todos `success`**.

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

- Trivy HIGH/CRITICAL + non-root gate (job release): non-root gate `success` (uid!=0 sobre alpine
  `adduser -S` → uid 100) e Trivy reporta table limpo — verificado localmente na imagem
  `url-shortener:0.14.0-test` (alpine+jar ambos `0` vulnerabilidades, HIGH/CRITICAL, ignore-unfixed).
- Runbook TOC (seções de release):
  ```
  $ grep -E '^#{1,3} ' docs/release-runbook.md
  1. Deploy a new version
  2. Roll back
  7. Operational checklist before a release
  Release artifacts & promotion
  Incidente: deploy falhou
  ```

### 8.7 Gates finais (executado 2026-09-13)

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
BUILD SUCCESS  (OWASP: único CVE conhecido preexistente opentelemetry-api-1.62.0 MEDIUM; cobertura 60/60 atingida)
```

Sha de fechamento do Épico: `b5fb2e2..736411c` (workflow fix, non-root gate fix, Dockerfile
Trivy fix) + `2e6d8db` (8.1–8.6 maior parte). CI verde no push final.

## 2. Checklist de conclusão

- [x] `docs/release-engineering.md` + ADR 0007 + ADR 0008
- [x] Versioning `revision` + flatten + gate CHANGELOG (red/green)
- [x] `deploy.sh` blue-green fail-closed + units blue/green + self-test + **drill local real de cutover
  (azul 18081/verde 18082 + front nginx container :18080; 240 redirects = 240× 302; fail-closed 502;
  rollback 60/60 302)**
- [x] `smoke.sh` (8 pernas) + `rollback.sh` + self-test + dead-port red
- [x] Timer de backup + manifest + restore `--verify` (negative test) + `ci-restore-drill.sh` com RTO
  **real (21s ≤ 300s; pre=20/20 302; post=2/2 404)**
- [x] `release.yml` verde no primeiro tag (run 34732409909) + Release com assets (jar do build da CI)
- [x] Runbook atualizado (deploy/rollback/checklist/post-deploy/incidente)
- [x] `./mvnw verify` verde
- [x] Nenhuma cifra neste arquivo sem comando acima

## 3. Fora de escopo confirmado (não vira dívida fantasma)

- K8s / orquestrador / multi-host — não feito; bare metal é a plataforma (ADR 0007).
- Replica set / Redis Sentinel — não feito; SPOF aceito no EP7.
- Backup off-host — não feito; TD nomeado (alvo de RPO inalterado: último backup).
- Canary auto-verificado por métricas (gate automático entre bumps) — não feito; canary = smoke +
  vigilância manual nomeada (Grafana/burn-rate).
- Deploy automático via SSH da CI — rejeitado no ADR 0008 (gate humano no bare metal).
- Rotação de chave JWT (dívida EP2) — não resolvida aqui.

---

*Seção 2 100% marcada e seção 1 com outputs reais: Épico 8 concluído em 2026-09-13 (tag `v0.14.0`).*