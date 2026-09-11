# Epic 6 – Stories (Aceitação) [aterrado]

| # | Story | Critérios de Aceitação (aterrados) | Referência Real |
|---|-------|------------------------|--------------------------|
| **6.1** | **ADRs de escalabilidade** – registrar as decisões de escala que o código já implementa (e as rejeitadas) em `docs/adr/`. | • 4 ADRs: 0001 escala horizontal stateless (vs vertical), 0002 rate-limit per-IP global via Redis, 0003 Caffeine L1 por instância (staleness limitado), 0004 circuit breakers resilience4j. <br>• Cada ADR: status/date/context/decision/consequences. <br>• `git log --oneline -- docs/adr/` colado no `epic-6-dod.md`. | Padrão ADR (Nygard) |
| **6.2** | **Auditoria `explain` dos índices** – provar que as queries críticas usam os índices V3–V9 (não criar índices às cegas). | • mongosh na infra isolada com dados reais: explain do lookup por `_id` (redirect), cursor pagination (V7), rollup/analytics (V4), TTL (V5). <br>• Evidência: estágio `IXSCAN`/`ID_SCAN` e `totalDocsExamined` mínimo colados. <br>• Sem novas migrations de índice (o modelo já está completo). <br>• `./mvnw verify` verde. | `MongoSchemaMigrator` V1–V9 |
| **6.3** | **Rate-limit + circuit breakers evidenciados** – o template pedia para "implementar"; já implementados. Evidenciar sob carga. | • `RedirectRateLimitIT` (5 testes) verde, output colado. <br>• `/actuator/circuitbreakers` sob carga 2×: `databaseCb`/`rateLimiterCb` em `CLOSED` (com config: 50%/20s, window 10, min 5). <br>• Configs `rate-limiter.*` e `resilience4j.circuitbreaker.*` documentadas no DoD. | `RedisRateLimiterAdapter`, `@CircuitBreaker(name="databaseCb")` |
| **6.4** | **Artefatos de release multi-instância** – nginx upstream N servers + weight-flip, systemd template, imagem com tag sha, runbook. | • `deploy/proxy/nginx.conf` com upstream multi-server + pesos documentados (flip 10→30→100). <br>• `deploy/url-shortener@.service` (template systemd instanciado `@1/@2/...`). <br>• Imagem Docker construída: `docker images` com tamanho + tag `sha` colados. <br>• `docs/release-runbook.md` atualizado com o procedimento multi-instância. | Bare-metal systemd + nginx (não k8s) |
| **6.5** | **Validação de escala horizontal** – 2 instâncias + LB sob stress 2× (o single-instance 2× já foi validado no Épico 5). | • 2 instâncias (portas distintas) compartilhando Mongo/Redis; nginx LB na frente. <br>• `stress.js` 2× via LB: p95 < 200ms, 0 5xx, relatório colado. <br>• **Prova de rate-limit compartilhado:** com limites reais, burst via LB → 429 exatamente após a capacidade global (mesmo bucket Redis nas 2 instâncias). <br>• Evidências no `epic-6-dod.md`. | `load-tests/stress.js`, `docs/twelve-factor.md` §6 |

---

**Rastreabilidade rápida:**

| Story | Doc referência | Aspecto chave |
|-------|----------------|---------------|
| 6.1 | `docs/adr/` | Decisões registradas |
| 6.2 | mongosh explain + V3–V9 | Índices usados de fato |
| 6.3 | `RedirectRateLimitIT` + `/actuator/circuitbreakers` | Resiliência evidenciada |
| 6.4 | `deploy/proxy/` + `Dockerfile` + runbook | Multi-instância |
| 6.5 | `stress.js` via LB | Escala horizontal provada |

---

*Executar na ordem do `epic-6-technical-tasks.md`; evidências no `epic-6-dod.md`.*