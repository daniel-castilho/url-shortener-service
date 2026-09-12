# Epic 7 – Tasks Técnicas [aterrado]

Marcos `[x]` preenchidos durante a execução; evidências coladas no `epic-7-dod.md`.

## 7.1 Contrato de falha + ADRs
- [ ] Criar `docs/reliability.md` com matriz componente × falha × efeito cliente × efeito dado × detecção × recuperação × RTO/RPO alvo.
- [ ] Cobrir: Mongo, Redis cache/rate-limit, Redis Stream, `ClickBatchWorker`, OTel collector, nginx, volume Mongo.
- [ ] ADR `docs/adr/0005-fail-open-vs-fail-closed.md` (status/date/context/decision/consequences + rejeitados).
- [ ] ADR `docs/adr/0006-analytics-at-least-once.md` (duplicata de click vs perda; por que não transação distribuída).
- [ ] Ligar a matriz às séries frozen relevantes (`resilience4j.*`, `analytics.queue.depth`, `http.server.requests`, `cache.*`).
- [ ] Colar `git log --oneline -- docs/adr/ docs/reliability.md` no `epic-7-dod.md`.

## 7.2 Isolamento (CB + timeout + retry budget)
- [ ] Inventariar adapters de saída: anotação Resilience4j, timeout, retry. Tabela no DoD.
- [x] Confirmar valores reais em `application.yaml` (`spring.data.mongodb.*`, `spring.data.redis.timeout`, `resilience4j.circuitbreaker.instances.*`).
- [x] Se o lookup de redirect não tiver timeout explícito no cliente Mongo/Redis, externalizar (não hardcode) e documentar o valor escolhido (alvo: Redis ≤ 500ms já configurado; Mongo socket 30s é alto para hot-path — justificar ou baixar **somente** com evidência e sem quebrar ITs).
      **Finding — a claim "Redis ≤ 500ms já configurado" era falsa:** o starter Redisson 4.7.0 **ignora** `spring.data.redis.timeout` (o `RedissonAutoConfigurationV4.buildSingleServerConfig` mapeia apenas host/port/password/ssl/database); os defaults reais do Redisson (timeout 3s, connect 10s, 3 retries a 1.5s) faziam cada op Redis falhar em ~5–25s — 200 GETs sob outage = "hang" de horas. Corrigido: bloco `app.redis.*` (`command-timeout-ms 500`, `connect-timeout-ms 500`, `retry-attempts 1`, `retry-interval-ms 100`, env-overridable) aplicado via `RedissonAutoConfigurationCustomizer` em `RedisConfig`; Mongo 30s mantido com justificativa no ADR 0005 (CB é a proteção operacional).
- [x] IT Mongo down / recusado no cache-miss do `GET /{id}`: status + log/métrica de CB. Nome sugerido: `RedirectMongoFailureIT`.
- [x] IT Redis down no redirect: rate-limit fail-open + fallback Mongo. Nome sugerido: `RedirectRedisFailureIT` (estender `RedisUrlCache` tests se já cobrirem o essencial).
- [x] **Nota técnica (singleton containers):** as ITs de falha sobem containers **dedicados à própria classe** (start/stop no ciclo de vida delas) — os singleton de `BaseIntegrationTest` são compartilhados por todas as ITs e não podem ser parados no meio da suíte.
      `RedirectMongoFailureIT` (4/4: CB open→503, CB closed→404, half-open probe, hot code via L2 / cold→503) e `RedirectRedisFailureIT` (2/2: cache-miss→Mongo degrades, rate-limiter fail-open além do limite) — **verdes juntos** (6/6, ~40s).
- [ ] `./mvnw test -Dtest='RedirectMongoFailureIT,RedirectRedisFailureIT,RedisClickEventQueueFailOpenTest,TracingFailOpenIT'` → verde; output colado.
- [ ] Não depender de `GET /actuator/circuitbreakers` autenticado como prova primária (métrica `resilience4j.circuitbreaker.*` / log). Nota: a dívida #26 foi resolvida em `c0fbb9c` (operator BasicAuth) — o endpoint está acessível ao operator como evidência secundária opcional.

## 7.3 Shutdown + semântica de health
- [ ] Rodar `./scripts/verify-graceful-shutdown.sh` contra uma instância local; colar stdout/stderr relevante (in-flight ok; recusa após SIGTERM).
- [ ] Mapear o que o `HealthEndpoint` realmente agrega hoje (Mongo, Redis, disk, CB). Output de `GET /actuator/health` **em perfil de teste/dev** colado (prod esconde details).
- [ ] Experimento: app healthy → `docker stop` da dependência crítica → `curl` liveness vs readiness (HTTP code + body resumido).
- [ ] Se liveness e readiness caem juntos: ajustar indicators (readiness inclui Mongo; liveness é processo/event loop apenas) + IT `HealthProbeSemanticsIT`.
- [ ] Documentar no `docs/reliability.md` o papel do nginx `max_fails=2 fail_timeout=10s`.
- [ ] Colar outputs no DoD.

## 7.4 Pipeline de analytics sob falha
- [x] Documentar stream name, group, ack, PEL em `docs/reliability.md` (valores lidos do código, não inventados).
- [x] Estender `ClickPipelineIT` (ou irmão): publish N → interrupt worker → restart → assert coleção + `clickCount` sob contrato at-least-once.
      **Fix real do PEL:** o worker lia apenas `>` (`ReadOffset.lastConsumed()`), que entrega só mensagens NUNCA entregues — batch não-ackado ficava órfão no PEL e NUNCA era re-entregue (redelivery e o finalize de 3 falhas eram código morto). Agora faz o padrão de crash-recovery do Redis: drena o PEL com offset `0` ANTES de ler `>` (`readGroup` compartilhado com self-heal NOGROUP). Red/green: IT novo `ClickPipelineRedeliveryIT` falha com o código antigo (`expected: 5L but was: 0L` no PEL) e passa com o fix.
- [x] Caso poison: evento inválido não bloqueia o group; métrica/log de drop; eventos válidos seguintes persistem.
      `poisonBatchIsFinalizedAndGroupKeepsProcessing`: batch injetado com `databaseCb` aberto → 3 tentativas consecutivas → finaliza (acked, `analytics.events.failed.total` +6) → evento válido posterior persiste.
- [x] Reconfirmar fail-open do enqueue no redirect (`RedisClickEventQueueFailOpenTest`) — output colado.
- [x] `./mvnw test -Dtest='ClickPipelineIT,ClickDailyRollupIT,RedisClickEventQueueFailOpenTest,RedisClickEventQueueTest'` → verde.

## 7.5 DR drill + fault injection sob carga
- [x] Subir infra isolada (portas fora de 27017/6379/8080 — padrão Épico 5/6: 27018 / 6380 / 18080).
- [x] Seed códigos; guardar a lista no DoD.
- [x] `./scripts/backup-mongodb.sh` (ajustar env `MONGODB_URI` da isolada); colar path + tamanho do dump + `ls -l`.
- [x] Simular perda: drop `short_urls` **na isolada**; restore; `curl -sI` dos códigos seed → 302; colar.
- [x] Run `load-tests/redirect.js` (duração curta) happy path na isolada — baseline local desta sessão.
- [x] `docker stop` Redis no meio de um run; colar summary k6 + veredito vs matriz 7.1.
- [x] `docker start` Redis; `docker stop` Mongo; run cache-frio; colar summary + veredito.
- [x] Atualizar `docs/release-runbook.md` com playbooks Redis-down / Mongo-down / restore (comandos reais usados).
- [x] Artefatos k6 em `load-tests/results/` (gitignored) — no DoD entra o summary, não o binário.

## 7.6 Gates finais do épico
- [ ] `./scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-boundaries.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-doc-sync.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-security.sh` (+ `--self-test`) → PASS.
- [ ] `promtool check rules` + `promtool test rules` + `amtool check-config` → verdes.
- [ ] `./mvnw verify` conjunto → BUILD SUCCESS.
- [ ] Evidências coladas no `epic-7-dod.md`; self-audit da regra zero.

---

**Checklist de conclusão do Épico 7:**

- [ ] `docs/reliability.md` + ADR 0005 + ADR 0006
- [ ] Inventário CB/timeout/retry + ITs de Mongo/Redis down
- [ ] Shutdown script verde + liveness ≠ readiness evidenciado (ou corrigido)
- [x] Worker recupera PEL; poison não trava o group
- [ ] Backup/restore isolado verde + dois fault-injections com números
- [ ] Runbook de incidente atualizado
- [ ] `./mvnw verify` verde (todos os gates)
- [ ] Evidências coladas no `epic-7-dod.md`

*Ao marcar todos os itens acima, o Épico 7 está **concluído** com modos de falha contratados e provados.*