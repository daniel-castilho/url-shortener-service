# Epic 6: Escalável – Base para Crescimento

**Projeto:** url-shortener-service
**Contexto:** Java 25, Spring Boot 4.1.1, Arquitetura Hexagonal, MongoDB, Redis, Tomcat 11 (virtual threads)
**Objetivo:** Preparar o sistema para crescer de forma sustentável — múltiplas instâncias sem degradação nem reestruturação — e **evidenciar** (não reimplementar) os mecanismos de escala que o repo já possui.

---

## Estado do repo (pré-existente, não é novo trabalho)

O repo **já possui** a maior parte da base de escala:

- **Stateless por design** (`docs/twelve-factor.md` §6/§8): auth JWT stateless; Mongo/Redis são recursos compartilhados; a fila de analytics é um Redis Stream durável (`RedisClickEventQueue` + `ClickBatchWorker`) — nenhum estado em processo.
- **Rate-limit per-IP compartilhado:** `RedisRateLimiterAdapter` — token bucket **via Redis** (um único script Lua atômico, TIME-driven), escopos SHORTEN (60/min) e REDIRECT (120/min) independentes, trusted-proxy CIDR, fail-open. Todas as instâncias batem no mesmo bucket → o limite é global, não por instância. `RedirectRateLimitIT` (5 testes) cobre capacidade, anti-enumeration, escopos e burst concorrente.
- **Circuit breakers Resilience4j:** `databaseCb` (`@CircuitBreaker`) em `MongoUrlRepository`, sliding window 10 / min 5 calls / 50% failure / 20s open; `rateLimiterCb`; expostos em `/actuator/circuitbreakers`.
- **Índices MongoDB (V1–V9 via `MongoSchemaMigrator`):** lookup de redirect é por `_id` (o código curto É a PK — índice `{short_code:1}` seria inútil); cursor pagination usa V7 `(userId, createdAt DESC)`; analytics usa V4 `(shortCode, timestamp)` + `(timestamp)`; expiração usa TTL V5 `expiresAt`.
- **Cache-aside compartilhado:** bloom filter + Redis L2 compartilhados; **Caffeine L1 é por instância** (TTL 5s → staleness limitado, aceitável), agora configurável via `app.cache.l1-*` (Épico 5).
- **Baseline/stress single-instance (Épico 5):** nominal 200/20 rps p95 < 12ms; stress 2× (ramping 400/40, hold 4m): 165.498 reqs, 0 falhas, p95 < 5ms.
- **SLO real:** p99 < 200ms (k6 thresholds `p95 < 200ms`, err < 0.1%) — não existe "S3 = 300ms".

**O que falta de verdade:** (a) decisões de escala registradas como ADRs; (b) auditoria `explain` com evidência de uso de índice; (c) artefatos de deploy multi-instância (nginx upstream N servers, systemd template, imagem com tag sha, runbook); (d) uma **validação multi-instância real** (2 instâncias + LB sob carga 2×, provando rate-limit compartilhado).

## Por que este épico agora?

- **EP5 (Performance)** validou SLOs single-instance; o EP6 valida que o design stateless **de fato escala horizontalmente** e registra as decisões que sustentam isso.
- **EP7/EP8** dependem de uma base escalável (tolerância a falhas, deploy blue-green/canary sem interrupção).

**Critério de Aceitação (aterrado):**

1. ≥2 ADRs (target: 4) em `docs/adr/` com template status/date/context/decision/consequences.
2. Auditoria `explain` nas queries críticas com evidência colada (IXSCAN, `totalDocsExamined` mínimo) — **sem** criar índice às cegas.
3. Rate-limit e circuit breakers **evidenciados** sob carga (IT verde + `/actuator/circuitbreakers`).
4. Artefatos multi-instância: nginx upstream com weight-flip, systemd template `url-shortener@.service`, imagem Docker construída (tamanho + tag sha no DoD), runbook atualizado.
5. Run **2 instâncias + LB** sob stress 2×: SLOs mantidos, 0 5xx, rate-limit compartilhado provado.
6. `./mvnw verify` verde com todos os gates.
7. **Rule zero — zero-from-memory:** todo número, sha ou contagem é colado de output real.

**Rastreabilidade rápida:**

| Story | Doc referência | Aspecto chave |
|-------|----------------|---------------|
| 6.1 | `docs/adr/` | Decisões de escala registradas |
| 6.2 | `MongoSchemaMigrator` V3–V9 + mongosh explain | Índices usados de fato |
| 6.3 | `RedisRateLimiterAdapter` + resilience4j | Limites globais + circuit breakers |
| 6.4 | `deploy/proxy/nginx.conf`, `deploy/url-shortener@.service`, `Dockerfile` | Deploy multi-instância |
| 6.5 | `load-tests/stress.js` via LB | Escala horizontal validada |

---

*Próximo passo: executar as stories 6.1–6.5 (`epic-6-technical-tasks.md`) e colar evidências no `epic-6-dod.md`.*