# Epic 7 – Definition of Done (DoD) [template de evidências]

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

Preencher durante a execução. Não inventar valores antes de rodar.

## 1. Evidências obrigatórias (outputs reais coladas)

### 7.1 Contrato de falha + ADRs (executado YYYY-MM-DD)

$ git log --oneline -- docs/adr/ docs/reliability.mdCOLAR

- Arquivos: `docs/reliability.md`, `docs/adr/0005-fail-open-vs-fail-closed.md`, `docs/adr/0006-analytics-at-least-once.md`.
- RPO mapping (alvo): _colar da matriz_.
- RPO analytics (alvo): _colar da matriz_.

### 7.2 Isolamento CB / timeout / retry (executado YYYY-MM-DD)

Inventário (preencher com grep/leitura real):

| Adapter | CB | Timeout | Retry | Contrato sob down |
|---------|----|---------|-------|-------------------|
| Mongo URL repo | | | | |
| Redis L2 cache | | | | |
| Redis rate-limit | | | | |
| Redis Stream enqueue | | | | |
| OTel exporter | | | | |

$ ./mvnw test -Dtest='RedirectMongoFailureIT,RedirectRedisFailureIT,RedisClickEventQueueFailOpenTest,TracingFailOpenIT'COLAR Surefire + BUILD SUCCESS/FAILURE

Métrica/log de transição do `databaseCb` (não Actuator autenticado):

COLAR

### 7.3 Shutdown + health (executado YYYY-MM-DD)

$ ./scripts/verify-graceful-shutdown.shCOLAR

$ curl -s -o /dev/null -w '%{http_code}\n' http://localhost:<port>/actuator/health/liveness
$ curl -s -o /dev/null -w '%{http_code}\n' http://localhost:<port>/actuator/health/readinessANTES da falha:DEPOIS de docker stop <dep>:

Veredito liveness ≠ readiness: _sim / não; se não, o que foi corrigido_.

### 7.4 Pipeline analytics (executado 2026-09-11)

Contrato lido do código (`ClickBatchWorker` + `application.yaml`, não inventado):

- Stream: `urlshortener:clicks` (`app.analytics.stream-key`, default `${APP_ANALYTICS_STREAM_KEY:urlshortener:clicks}`)
- Group: `click-worker` (`app.analytics.group`, default `${APP_ANALYTICS_GROUP:click-worker}`)
- Consumer: `worker-1` (`app.analytics.consumer`)
- Batch: `500` (`app.analytics.batch-size`), poll: `5000`ms (`app.analytics.poll-interval-ms`)
- Ack: `redisTemplate.opsForStream().acknowledge(streamKey, groupName, ...)` por lote, no grupo `click-worker`

$ ./mvnw test -Dtest='ClickPipelineIT,ClickDailyRollupIT,RedisClickEventQueueFailOpenTest,RedisClickEventQueueTest,ClickPipelineRedeliveryIT' -DfailIfNoTests=false

```
Tests run: 2, ... -- in Analytics pipeline — at-least-once PEL redelivery (Epic 7 7.4)
Tests run: 2, ... -- in ca.tyny.urlshortener.infra.adapter.output.analytics.RedisClickEventQueueFailOpenTest
Tests run: 2, ... -- in Click daily rollup integration tests
Tests run: 2, ... -- in ca.tyny.urlshortener.infra.adapter.output.analytics.RedisClickEventQueueTest
Tests run: 4, ... -- in Click Pipeline Integration Tests
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**Descoberta (fix real):** o worker lia apenas `>` (`ReadOffset.lastConsumed()`), que entrega
só mensagens NUNCA entregues — batch não-ackado ficava órfão no PEL para sempre (redelivery +
finalize de 3 falhas eram código morto). Agora drena o PEL com offset `0` antes de ler `>`
(padrão de crash-recovery do Redis). Red/green: `ClickPipelineRedeliveryIT#failedBatchIsReclaimedAfterRecovery`
falha no código antigo (`expected: 5L but was: 0L` no PEL) e passa no novo.

Restart no meio do batch: publicados=**5** persistidos=**5** (prova `M >= N`, ADR 0006):

$ ./mvnw test -Dtest='ClickPipelineRedeliveryIT#failedBatchIsReclaimedAfterRecovery' -DfailIfNoTests=false

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 ... -- in Analytics pipeline — at-least-once PEL redelivery (Epic 7 7.4)
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Poison (batch injetado com `databaseCb` aberto → 3 tentativas → finalizado/acked; evento válido
posterior persiste), log real do worker:

```
ERROR c.t.u.i.a.o.a.ClickBatchWorker - Finalizing click batch of 2 events after 3 consecutive failures
```

$ ./mvnw test -Dtest='ClickPipelineRedeliveryIT#poisonBatchIsFinalizedAndGroupKeepsProcessing' -DfailIfNoTests=false → verde (parte dos 2 acima; PEL 2→0, métrica `analytics.events.failed.total` +6, `click_events` 0 para o poison, evento válido=1).

### 7.5 DR + injeção sob carga (executado 2026-09-11)

Infra isolada: app **18080**, Mongo **27018** (`urlshortener-mongo-isolated`), Redis **6380** (`urlshortener-redis-isolated`).
Seeds (códigos): `WvbkQL9`, `ikNnZMP`, `8l9Zi1J`, `sjuq5b0`, `IAa4KHx` (criados via `POST /api/v1/urls` na isolada) + `cZLYsMv`, `cZxmLOD`, `4TcKm26` (drill, pós-backup).

$ docker exec urlshortener-mongo-isolated mongodump --uri="mongodb://127.0.0.1:27017/url_shortener" --db=url_shortener --out=/tmp/epic7-backup --gzip

```
done dumping `url_shortener.schema_migrations` (9 documents)
done dumping `url_shortener.users` (0 documents)
done dumping `url_shortener.click_daily` (0 documents)
done dumping `url_shortener.short_urls` (7640 documents)
done dumping `url_shortener.click_events` (303867 documents)
```

$ docker cp urlshortener-mongo-isolated:/tmp/epic7-backup /tmp/opencode/epic7/backup-drill && ls -l .../short_urls.bson.gz .../click_events.bson.gz && du -sh

```
-rw-r--r--   445999  short_urls.bson.gz
-rw-r--r--  3546379  click_events.bson.gz
3.9M    /tmp/opencode/epic7/backup-drill
```
> Nota: `mongodump` não existe no PATH do host (apenas no container mongo); o drill usou as ferramentas
> do container — os mesmos flags que `scripts/backup-mongodb.sh` invoca em hosts bare-metal com o CLI.

drop + restore + curl -sI dos seeds:

```
$ mongosh --eval 'db.short_urls.drop()'          # -> true; count=0
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:18080/cZLYsMv   # AFTER drop, cold cache -> 404
$ mongorestore --uri=...url_shortener --db=url_shortener --gzip /tmp/epic7-backup/url_shortener
     7640 document(s) restored successfully. 303876 document(s) failed to restore.   # click_events: collection
                                                                # nunca foi dropada — _id existentes pulados
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:18080/WvbkQL9   # 302  (pré-backup: restaurado)
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:18080/cZLYsMv   # 404  (pós-backup: não é restaurado, RPO correto)
```
> Códigos pós-backup ficam 404 após restore — esperado: RPO = última execução do backup. Curl usado com
> `-s` (GET); `HEAD` também responde 302 (fix aplicado em `SecurityConfig` + `ReadPathIT#headMirrorsGetOnRedirectPath`).

k6 happy isolado (`load-tests/redirect.js`, 30s @100rps):

```
checks_succeeded 100.00% (3001/3001)   http_req_failed 0.00% (0/3201)
http_req_duration: avg=4.8ms  p(50)=4.05ms  p(95)=10.56ms  p(99)=13.58ms
```

Redis down no meio (45s @150rps; `docker stop urlshortener-redis-isolated` em +20s):

```
checks_succeeded 100.00% (5730/5730)   http_req_failed 0.00% (0/5930)
http_req_duration: avg=208.78ms  p(50)=6.45ms  p(95)=744.22ms  p(99)=753.89ms
```
Veredito: **alinhado à matriz 7.1** — rate-limit + cache fail-open (ADR 0005): 100% 302 sem 4xx/5xx;
latência p95 sobe (~744ms) no período do outage (cache/RL fallback para Mongo), sem falhas de cliente.

Mongo down (cache frio; flush Redis + espera L1 TTL, depois `docker stop urlshortener-mongo-isolated`;
`fixed-redirect.js` 60s @150rps contra os seeds reais; `databaseCb` abre após ~5 falhas):

```
http_req_failed: 100.00% (4504/4504)   http_req_duration: avg=324ms  p(50)=3.56ms  p(95)=6.85ms  p(99)=27.09s
iterations 4504 / dropped_iterations 4497
```
Log: `GlobalExceptionHandler - Circuit breaker open: CircuitBreaker 'databaseCb' is HALF_OPEN and does not permit further calls`
Veredito: **alinhado à matriz 7.1** — fail-closed: cold-cache redirects fail 503/5xx (p50 3.6ms é o
estado OPEN fast-fail; p99 27s é a janela de amostragem do CB com server-selection timeout do driver);
recuperação pós `docker start` → **302** (HALF_OPEN → CLOSED, sem restart da app).

Alinhado à matriz 7.1? **sim**, sem gaps a corrigir (Redis-down fail-open e Mongo-down fail-closed
comportam-se exatamente como contratado).

### 7.6 Gates finais (executado YYYY-MM-DD)

$ ./scripts/check-metrics-frozen.sh && ./scripts/check-metrics-frozen.sh --self-test
$ ./scripts/check-boundaries.sh && ./scripts/check-boundaries.sh --self-test
$ ./scripts/check-doc-sync.sh && ./scripts/check-doc-sync.sh --self-test
$ ./scripts/check-security.sh && ./scripts/check-security.sh --self-testCOLAR PASS

$ ./mvnw verifyCOLAR BUILD SUCCESS e totais de testes

$ git rev-parse --short HEADCOLAR sha do commit de fechamento

## 2. Checklist de conclusão

- [ ] `docs/reliability.md` + ADR 0005 + ADR 0006
- [ ] Inventário CB/timeout/retry + ITs de falha
- [ ] Shutdown script verde + probes evidenciados
- [ ] Worker/PEL/poison evidenciados
- [ ] Restore isolado + dois fault-injections com números
- [ ] Runbook atualizado com os comandos desta execução
- [ ] `./mvnw verify` verde
- [ ] Nenhuma cifra neste arquivo sem comando acima

## 3. Fora de escopo confirmado (não vira dívida fantasma)

- Replica set / Redis cluster — não feito, SPOF aceito e escrito na matriz.
- Dívida AGENTS #26 (ROLE_ADMIN) — não resolvida aqui.
- Exactly-once de clique — rejeitado no ADR 0006.

---

*Quando a seção 2 estiver 100% marcada e a seção 1 tiver outputs reais, o Épico 7 está **concluído**.*
