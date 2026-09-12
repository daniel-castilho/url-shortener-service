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

### 7.5 DR + injeção sob carga (executado YYYY-MM-DD)

Infra isolada: app `_porta_`, Mongo `_porta_`, Redis `_porta_`.
Seeds (códigos): `_lista curta_`.

$ ./scripts/backup-mongodb.sh
$ ls -l <dump>COLAR path + bytes

drop + restore + curl -sI dos seedsCOLAR 302s

k6 happy isolado:

COLAR p50/p95/p99 + http_req_failed

Redis down no meio:

COLAR summary + distribuição de status

Mongo down (cache frio):

COLAR summary + distribuição de status

Alinhado à matriz 7.1? _sim / gap corrigido em _._

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
