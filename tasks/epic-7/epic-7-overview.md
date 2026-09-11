# Epic 7: Reliable – Tolerância a Falha e Recuperação

**Projeto:** url-shortener-service
**Contexto:** Java 25, Spring Boot 4.1.1, Arquitetura Hexagonal, MongoDB 6.0 (single-node), Redis (single-node + AOF + Stream), Tomcat 11 (virtual threads), bare-metal + nginx
**Objetivo:** Tornar o comportamento sob falha **explícito, testado e operável** — o serviço degrada de forma conhecida, recupera sem perda silenciosa do mapeamento URL e não mente no health. Este épico **não** transforma Mongo/Redis em cluster; documenta o SPOF e prova os modos de degradação.

---

## Estado do repo (pré-existente, não é novo trabalho)

O repo **já possui** uma base de resiliência que o Épico 7 deve **evidenciar**, não reescrever:

- **Circuit breakers Resilience4j:** `databaseCb` (`@CircuitBreaker` em adapters Mongo; window 10 / min 5 / 50% / 20s open) e `rateLimiterCb` (40% / 10s). Expostos em `/actuator/circuitbreakers`. ADR 0004 já registra a decisão.
- **Fail-open pontual:** rate-limit quando Redis cai (`RedisRateLimiterAdapter`); tracing OTel (`TracingFailOpenIT`); fila de cliques (`RedisClickEventQueueFailOpenTest`). Redirect **não** espera analytics.
- **Health tiered:** `liveness`/`readiness` públicos; `health` detalhado gated. Probes no Dockerfile e no systemd.
- **Graceful shutdown:** `server.shutdown: graceful` + timeout 30s + `scripts/verify-graceful-shutdown.sh` (in-flight completa; probe para de aceitar).
- **Durabilidade parcial:** Redis Stream (`RedisClickEventQueue` + `ClickBatchWorker`); `$inc` atômico de `clickCount`; AOF no Redis do compose; índice TTL V5; purge `ClickEventsRetentionPurge` (batches idempotentes).
- **Backup/restore:** `scripts/backup-mongodb.sh` e `scripts/restore-mongodb.sh` + menção no `docs/release-runbook.md`.
- **Fail-fast de config:** `ProdConfigValidator` aborta profile `prod` com JWT default / Mongo-Redis localhost.
- **Proxy:** nginx `max_fails=2 fail_timeout=10s` (Épico 6).

**O que falta de verdade:**

1. **Matriz de modos de falha** (Mongo down, Redis down, Stream travado, worker morto, OTel down, disco cheio) com efeito no cliente (302 / 429 / 503 / 404) e no dado (URL mapping vs analytics).
2. **Timeouts e retry budget** explícitos nas portas de saída (hoje o CB existe; timeout/retry/bulkhead não estão contratados nem testados de ponta a ponta).
3. **Semântica liveness ≠ readiness** evidenciada sob falha de dependência (readiness DOWN, liveness UP — o kube/systemd/nginx tira de rota sem matar o processo).
4. **Contrato da fila de analytics:** at-least-once, PEL/ack, poison message, o que acontece se o worker crashar no meio do batch.
5. **Drill de DR e injeção de falha sob carga:** restore Mongo com verificação; Redis/Mongo derrubados durante `stress.js` com 5xx/latência/degradação **colados**, não narrados.

## Por que este épico agora?

- **EP3 (Observable)** deu métricas, tracing fail-open, SLOs e burn-rate — sem eles a falha é invisível.
- **EP4 (Testes)** deu o harness IT/Testcontainers; EP7 acrescenta os testes de *falha induzida*.
- **EP5 (Performance)** provou SLO no happy path; EP7 prova o que sobra do SLO quando uma dependência some.
- **EP6 (Escalável)** provou 2 instâncias + LB + CB CLOSED no happy path. **Dívida #26 resolvida em
  `c0fbb9c`** (operator BasicAuth sobre os tiers do actuator, env `OPERATOR_USERNAME`/`OPERATOR_PASSWORD`):
  a leitura HTTP de `/actuator/circuitbreakers` está disponível ao operator como evidência
  **secundária**; a prova primária nas stories deste épico continua sendo funcional (status HTTP +
  métricas `resilience4j.*` + logs), para não acoplar os testes de falha a credenciais.
- **EP8 (Deployable)** precisa de readiness correta, shutdown drenado e runbook de incidente — senão blue-green/canary vira corte.

**Fora de escopo (não fazer neste épico):**

- Replica set Mongo, Redis Sentinel/Cluster, multi-AZ.
- Trocar fail-open do rate-limit por fail-closed (é decisão de produto; no máximo ADR).
- Resolver dívida #26 (`ROLE_ADMIN`).
- Exatamente-uma-vez nos cliques (o contrato é at-least-once; `clickCount` pode divergir levemente de `click_events` sob retry — isso deve ficar escrito, não “corrigido” com distributed tx).

**Critério de Aceitação (aterrado):**

1. `docs/reliability.md` com matriz componente × falha × efeito no cliente × efeito no dado × como detecta × como recupera.
2. ≥2 ADRs novos em `docs/adr/` (target: 0005 fail-open vs fail-closed por dependência; 0006 analytics at-least-once + PEL).
3. Timeouts/retry budget documentados nas configs reais e cobertos por IT de falha (Mongo timeout → CB abre; Redis down no redirect → comportamento contratado).
4. `scripts/verify-graceful-shutdown.sh` verde **e** prova liveness UP / readiness DOWN com Mongo ou Redis recém-derrubado (output colado).
5. Worker de analytics: crash no meio do batch não perde o Stream; poison message não trava o consumidor; evidência colada.
6. Drill: `backup-mongodb.sh` → drop/restore seletivo em infra **isolada** → lookup de códigos conhecidos 302; injeção Redis-down e Mongo-down sob carga com números colados.
7. `./mvnw verify` verde com todos os gates.
8. **Rule zero — zero-from-memory:** todo número, sha ou contagem é colado de output real.

**Rastreabilidade rápida:**

| Story | Doc referência | Aspecto chave |
|-------|----------------|---------------|
| 7.1 | `docs/reliability.md` + `docs/adr/0005`, `0006` | Contrato de falha escrito |
| 7.2 | Resilience4j + ITs de timeout/CB | Isolamento de dependência |
| 7.3 | `verify-graceful-shutdown.sh` + probes | Dreno e semântica de health |
| 7.4 | `RedisClickEventQueue` + `ClickBatchWorker` | Recuperação da fila |
| 7.5 | `scripts/backup-mongodb.sh` + fault injection | DR + degradação sob carga |

---

*Próximo passo: executar as stories 7.1–7.5 (`epic-7-technical-tasks.md`) e colar evidências no `epic-7-dod.md`.*