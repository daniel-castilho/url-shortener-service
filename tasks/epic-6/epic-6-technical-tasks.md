# Epic 6 – Tasks Técnicas [aterrado]

Marcos `[x]` preenchidos durante a execução; evidências coladas no `epic-6-dod.md`.

## 6.1 Registros de decisão (ADRs)
- [x] Criar `docs/adr/` com 4 ADRs (0001–0004), template: status/date/context/decision/consequences.
- [x] 0001: escala horizontal stateless + recursos compartilhados (vs vertical) — âncora `docs/twelve-factor.md` §6/§8.
- [x] 0002: rate-limit per-IP **global** via Redis (bucket Lua atômico compartilhado entre instâncias).
- [x] 0003: Caffeine L1 por instância (TTL 5s = staleness limitado; bloom/L2 compartilhados).
- [x] 0004: circuit breakers resilience4j (`databaseCb`) nos adapters Mongo.
- [x] Colar `git log --oneline -- docs/adr/` no `epic-6-dod.md`.

## 6.2 Auditoria explain dos índices (sem criação às cegas)
- [x] Infra isolada (Mongo 27018) com dados reais (pool do stress).
- [x] mongosh: `db.short_urls.find({_id: "<code>"}).explain("executionStats")` → ID_SCAN, docs examined mínimo.
- [x] mongosh: explain do cursor pagination (`userId` + `createdAt DESC` + cursor) → IXSCAN em `userId_1_createdAt_-1` (V7).
- [x] mongosh: explain do rollup/aggregation (`shortCode`+`day`) e `click_events` (V4).
- [x] mongosh: `getIndexes()` de `short_urls`/`click_events` colado (prova do conjunto V1–V9).
- [x] `./mvnw verify` → verde.
- [x] Colar outputs no `epic-6-dod.md`.

## 6.3 Rate-limit + circuit breakers (evidência do existente)
- [x] `./mvnw test -Dtest='RedirectRateLimitIT'` → verde (5 testes), output colado.
- [x] Sob carga 2× (story 6.5): `GET /actuator/circuitbreakers` → `databaseCb`/`rateLimiterCb` `CLOSED`, output colado.
- [x] Documentar no DoD as configs reais: `rate-limiter.*` (60/PT1M, 120/PT1M, scopes) e `resilience4j.circuitbreaker.*` (window 10, min 5, 50%/20s).

## 6.4 Artefatos de release multi-instância
- [x] `deploy/proxy/nginx.conf`: upstream `url_shortener_backend` com N servers + pesos comentados (flip 10→30→100).
- [x] `deploy/url-shortener@.service`: systemd template (`url-shortener@1.service`, `@2.service`, portas distintas).
- [x] `docker build -t url-shortener:sha-<short> .` → `docker images` com tamanho + tag sha colados.
- [x] Atualizar `docs/release-runbook.md` com o procedimento multi-instância + weight-flip.
- [x] Colar outputs no `epic-6-dod.md`.

## 6.5 Validação de escala horizontal (2 instâncias + LB)
- [x] Boot de 2 instâncias (portas 18080/18081) compartilhando Mongo 27018 + Redis 6380 (rate limits relaxados para o stress).
- [x] LB nginx container na frente das 2 instâncias.
- [x] `stress.js` 2× (ramping 400/40, hold 4m) via LB → p95 < 200ms, 0 5xx; summary colado.
- [x] **Prova de rate-limit compartilhado:** com limites reais (redirect 120/min), burst via LB → 429 após a capacidade global (não por instância); output colado.
- [x] Evidências no `epic-6-dod.md`.

## 6.6 Gates finais do épico
- [x] `./scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS.
- [x] `./scripts/check-boundaries.sh` (+ `--self-test`) → PASS.
- [x] `./scripts/check-doc-sync.sh` (+ `--self-test`) → PASS.
- [x] `promtool check rules` + `promtool test rules` + `amtool check-config` → verdes.
- [x] `./mvnw verify` conjunto → BUILD SUCCESS.
- [x] Evidências coladas no `epic-6-dod.md`; self-audit rodado.

---

**Checklist de conclusão do Épico 6:**

- [x] 4 ADRs criados (`docs/adr/`)
- [x] Auditoria explain limpa (IXSCAN/ID_SCAN evidenciado)
- [x] `RedirectRateLimitIT` verde + circuit breakers CLOSED sob carga
- [x] Artefatos multi-instância (nginx, systemd template, imagem sha, runbook)
- [x] Stress 2× via LB (2 instâncias): SLOs ok, 0 5xx, rate-limit compartilhado provado
- [x] `./mvnw verify` verde (todos gates)
- [x] Evidências coladas no `epic-6-dod.md`

*Ao marcar todos os itens acima, o Épico 6 está **concluído** com escala horizontal validada.*