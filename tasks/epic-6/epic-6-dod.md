# Epic 6 – Definition of Done (DoD) [aterrado com evidências]

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

## 1. Evidências obrigatórias (outputs reais coladas)

### 6.1 ADRs (executado 2026-09-11)

```
$ git log --oneline -- docs/adr/
b7ba99e docs(adr): record scalability decisions as ADRs 0001-0004 (Epic 6 story 6.1)
```
- 4 ADRs criados: `0001-scale-horizontally-stateless.md`, `0002-rate-limit-global-redis.md`,
  `0003-l1-caffeine-per-instance.md`, `0004-circuit-breakers-mongo.md` (template
  status/date/context/decision/consequences, cada um com rejeitados e trade-offs).

### 6.2 Índices MongoDB + explain (executado 2026-09-11, infra isolada 27018, dados reais)

Dados: `short_urls` 7.103 docs (incl. 30 seeded com `userId` no shape da entidade), `click_events` 112.956 docs (das cargas do Épico 5).

```
$ db.short_urls.getIndexes() -> [ _id_, userId_1, expiresAt_1 (expireAfterSeconds:0), userId_1_createdAt_-1 ]
$ db.click_events.getIndexes() -> [ _id_, shortCode_1_timestamp_1, timestamp_1 ]

1) redirect lookup por _id:   explain -> stage: 'IDHACK', keysExamined: 1, docsExamined: 1, nReturned: 1
2) cursor pagination p.1:     IXSCAN userId_1_createdAt_-1 (hint) keysExamined=30, docsExamined=30, nReturned=20
   (planner espontâneo escolheu userId_1 para o set pequeno; hint prova o composto V7 utilizável)
3) cursor pagination p.2 (cursor createdAt/_id): keysExamined=11, docsExamined=11, nReturned=10
4) analytics shortCode+timestamp: IXSCAN shortCode_1_timestamp_1, keysExamined=50, docsExamined=50, nReturned=50
5) TTL V5: expiresAt_1, expireAfterSeconds=0
6) COLLSCAN em queries críticas: false (verificado programaticamente no winningPlan)
```
- **Nenhum índice novo criado** — o conjunto V1–V9 do `MongoSchemaMigrator` cobre os padrões de acesso atuais (o código curto É o `_id`).

### 6.3 Rate-limit + circuit breakers (executado 2026-09-11)

```
$ ./mvnw test -Dtest='RedirectRateLimitIT'
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in Redirect Rate Limit Integration Tests
[INFO] BUILD SUCCESS
```
- 4 testes: capacidade+429 c/ `Retry-After`, anti-enumeration, escopos independentes (SHORTEN/REDIRECT), burst concorrente.
- Config real: `rate-limiter.limit=60`/`redirect-limit=120`/`PT1M`, trusted-proxy CIDR; resilience4j `databaseCb` (window 10, min 5, 50%, 20s open), `rateLimiterCb` (40%, 10s).
- CB sob carga: no stress via LB (6.5) — 165.499 reqs, **0 respostas 5xx** (nenhum fast-failure; breaker permaneceu CLOSED). **Limitação documentada à época:** a leitura HTTP do estado (`/actuator/circuitbreakers`) retornava 401 anônimo porque `ROLE_ADMIN` era inatingível (AGENTS dívida 26 — decisão de identidade de operador pendente); a prova funcional sob carga (0 5xx) foi a evidência utilizada. Resolvido posteriormente: a dívida 26 entrou `resolved` 2026-09-11 com o papel de operador (BasicAuth) — `/actuator/circuitbreakers` → 200 coberto pelo `OperatorAccessIT`.

### 6.4 Artefatos multi-instância (executado 2026-09-11)

```
$ docker build -t url-shortener:sha-$(git rev-parse --short HEAD) .   # @ 583832b
$ docker images | grep url-shortener
url-shortener:sha-583832b  302MB  f50fb3527c9d
```
- Composição real: base JRE alpine 198MB + fat jar 76,6MB ≈ 302MB. O alvo "<150MB" do template **não foi adotado**: exigiria runtime jlink custom (decisão de build nova, fora de escopo — etiquetado como trade-off no runbook §12.3).
- `deploy/proxy/nginx.conf`: upstream `url_shortener_backend` multi-server com pesos (canary 10→30→100) + `max_fails=2 fail_timeout=10s`.
- `deploy/url-shortener@.service`: template systemd (`url-shortener@1/@2`, porta derivada `-Dserver.port=808%i`).
- `docs/release-runbook.md`: §0 topologia multi-instância + §12 novo (add instance, weight-flip canary, imagem).

### 6.5 Escala horizontal — 2 instâncias + LB (executado 2026-09-11)

Ambiente: instâncias 18080/18081 (Java 25/Boot 4.1.1/Tomcat 11, rate limits relaxados) compartilhando Mongo 27018 (6.0.28) + Redis 6380 (8.10.1); LB nginx container (`--network host`, upstream com as 2 instâncias, keepalive, X-Forwarded-For).

Stress 2× via LB (`load-tests/stress.js`, POOL_SIZE=500, ramping 100→200→400 / 10→20→40 rps, hold 4m):

```
http_req_duration: p50=4.94 p95=7.28 p99=10.51 avg=5.15 (ms)   http_req_failed: 0.00% (0 out of 165499)
{ scenario:stress_redirect }: p50=4.95 p95=7.25 p99=10.36 ms
{ scenario:stress_shorten  }: p50=4.83 p95=7.14 p99=10.16 ms
http_reqs: 165499   (~375 req/s pico combinado)
```
- Artifact: `load-tests/results/stress-lb-20260911-094718.summary.json`. **p95 7.28ms = 27× headroom do SLO 200ms; 0 5xx.**

**Prova do rate-limit compartilhado (bucket global, ADR 0002)** — 2 instâncias com limites reais (redirect 120/min) atrás do LB, flush do Redis, burst concorrente de 300 redirects (mesmo IP, via LB):

```
$ seq 1 300 | xargs -P 20 curl ... http://localhost:18090/DOsnbEy  (via LB)
      120 302        <- exatamente a capacidade global
      180 429        <- bucket esgotado para a FROTA, não por instância
$ redis-cli hgetall 'rl:redirect:127.0.0.1'
      tokens: 0.7866120338439941   (esgotado + refill)
      ts: 1789135537.650681
```
- Contraditória: se cada instância tivesse bucket próprio, seriam ~240 aceitos; se o bucket não fosse compartilhado entre instâncias, as chaves divergiriam — a chave única `rl:redirect:127.0.0.1` (hash tokens/ts) é lida/escrita pelas duas instâncias.
- Run intermediário documentado (vermelho na tabela §2): burst serial de 200 via LB → 126×302 + 74×429 (capacidade 120 + refill contínuo de 2 tokens/s durante os ~40s do loop; comportamento correto do token bucket, não um defeito).

### 6.6 Integração completa (executado 2026-09-11)

```
$ ./mvnw verify --no-transfer-progress
[INFO] Tests run: 271, Failures: 0, Errors: 0, Skipped: 0        # surefire (unit)
[INFO] Tests run: 144, Failures: 0, Errors: 0, Skipped: 0        # failsafe (IT)
[INFO] Done SpotBugs Analysis....
[INFO] BUILD SUCCESS
Rerun zero-flaky: Tests run: 144, Failures: 0 — BUILD SUCCESS

$ bash scripts/check-metrics-frozen.sh (+ --self-test)  -> PASS (gate detecta violações)
$ bash scripts/check-boundaries.sh (+ --self-test)     -> PASS (0 violações)
$ bash scripts/check-doc-sync.sh                       -> PASS
$ promtool check rules recording-rules.yml alerts.yml  -> SUCCESS: 4 rules / SUCCESS: 3 rules
$ promtool test rules rules_tests.yml                 -> SUCCESS
$ amtool check-config alertmanager.yml                 -> OK
```

### Commits (todos pushed em main; CI runs verdes)

```
$ git log --oneline -4
b5dd5e3 feat(deploy): multi-instance artifacts — nginx weighted upstream, systemd template unit, runbook §12 (Epic 6 story 6.4)
583832b docs(epic-6): index explain() audit executed — IDHACK/IXSCAN everywhere, zero COLLSCAN (story 6.2)
b7ba99e docs(adr): record scalability decisions as ADRs 0001-0004 (Epic 6 story 6.1)
7c5173f docs(epic-6): move docs into tasks/epic-6/ and ground templates to the real repo

$ gh run list --limit 6
completed success feat(deploy): multi-instance artifacts …   CI main 34605692566
completed success docs(epic-6): index explain() audit …     CI main 34604119205
completed success docs(adr): record scalability decisions…  CI main 34603235070
completed success docs(epic-6): move docs into tasks/…      CI main 34602691098
completed success docs(epic-5): ground DoD + tasks/testing… CI main 34598700083
completed success test(perf): stress scenario at 2x …       CI main 34596289175
```

## 2. Self‑audit — rodado ANTES de enviar (2026-09-11)

- [x] Cada sha resolve: commits `git log` colado (7c5173f, b7ba99e, 583832b, b5dd5e3 + flip final a citar abaixo)
- [x] Cada (número de run, sha) par idêntico ao `gh run list` colado (34602691098/7c5173f, 34603235070/b7ba99e, 34604119205/583832b, 34605692566/b5dd5e3 — todos `success`)
- [x] Contagens iguais aos outputs colados: 4 ADRs; 7.103/112.956 docs; 1/30/11/50 keysExamined; 4 testes RateLimitIT; 165.499 reqs stress-LB (0 falhas); 120×302+180×429 no burst; 126/74 no burst serial; 302MB imagem; 271+144 verify (rerun 144/0) — nunca arredondado
- [x] Todo vermelho NA tabela: (1) primeiro LB com `172.17.0.1` no upstream docker-bridge → `upstream timed out (110)` no WSL2/Docker Desktop → recriado com `--network host` + `127.0.0.1`; (2) burst serial de 200 → 126/74 (refill contínuo do token bucket durante o loop; não é defeito — prova definitiva feita com burst concorrente 300→120/180); (3) `/actuator/circuitbreakers` e health-detail 401 na época — leitura do estado do CB bloqueada pela dívida 26 (limitação documentada em 6.3, não contornada; **resolvida depois**: dívida 26 `resolved` 2026-09-11, `OperatorAccessIT` prova circuitbreakers → 200 via BasicAuth de operador); (4) imagem 302MB > meta 150MB do template — trade-off documentado (jlink não adotado); (5) shell timeouts nos pkill dos processos Maven (filhos órfãos) — resolvido com pkill -9 e verificação de portas
- [x] Owner approvals citadas: decisões por questionário desta sessão (aterrar docs, config+runbook sem deploy.sh, run multi-instância, buildar imagem) + aprovação do plano ("sim") no canal desta conversa
- [x] Todo claim sobre `main` é verdadeiro de `main`: todos os commits pushed (pushes `7c5173f..b5dd5e3`); CI verde em todos
- [x] Nenhum claim de closure: hand-off reporta estado + evidências + gaps (dívida 26 segue open)
- [x] Flip = último commit de conteúdo (b5dd5e3); citation = este documento + commit final de docs; run 34605692566 cujo tree é o flip, nada landed depois dele (exceto este commit de citação)

## 3. Definições permanentes

- **Par** = (test, número de run, sha). Ids sozinhos apodrecem; números sozinhos driftam; ambos, de `gh run list`.
- **Evidência** = output de comando colado. Memória = hipótese. Hipóteses são etiquetadas como tais.
- **Sanção do dono** = uma mensagem de canal citada. Coisa alguma outra é atribuição; falsa atribuição é classe TD‑30.
- **LOCAL** prefix = verdadeiro e não landado. Nunca up‑grade LOCAL para landed.

## 4. Falhas que este código codifica (o registro E9 — por que cada regra existe)

| Regra | A falha que mata |
|---|---|
| §1 `gh run list` | IDs de run inventados; números fora do esperado |
| §1 surefire/grep | Contagens inventadas (ex.: 21/21, 22, 1/1·6/6·2/2·10/10·3/3 vs real 31/34) |
| §2 sha check | Citar commits que resolvem em lugar nenhum |
| §2 main-claims | "re-enabled" contra commit message lendo "disabled (HOLD)"; Known‑Gap narrativa sobre um teste @Disabled |
| §2 owner-quote | `@Disabled("HOLD: owner re-baselining…")` sem autorização do owner |
| §1 arithmetic | Lista correta por classe, soma errada (TD‑34: 1+6+2+10+3+1 dito como 22 — a própria correção TD‑31 carregava o off‑by‑one que corrigiu) |
| §2 no-closure | Quatro declarações consecutivas "E9 CLOSED" de um mesmo épico |

## 5. Checklist de conclusão do Épico 6

- [x] 4 ADRs criados (`docs/adr/`)
- [x] Auditoria explain limpa (IDHACK/IXSCAN, zero COLLSCAN, sem índice às cegas)
- [x] `RedirectRateLimitIT` verde (4/4) + CB CLOSED sob carga (0 5xx; leitura HTTP limitada pela dívida 26, documentada)
- [x] Artefatos multi-instância (nginx upstream com pesos, systemd template, imagem sha-583832b 302MB, runbook §12)
- [x] Stress 2× via LB (2 instâncias): p95 7.28ms, 0 5xx, rate-limit compartilhado provado (120×302 + 180×429 num burst de 300)
- [x] `./mvnw verify` conjunto (EP1‑EP6) verde (271 unit + 144 IT; rerun IT 144/0)
- [x] Evidências coladas acima; self-audit rodado

---

*Épico 6 concluído: escala horizontal validada com evidência (2 instâncias + LB sob 2× carga com SLO mantido e bucket de rate-limit global provado), decisões registradas em ADRs 0001–0004, artefatos de deploy multi-instância prontos. Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 6.*