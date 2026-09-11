# Epic 5: Performance – Latência e Throughput

**Projeto:** url-shortener-service
**Contexto:** Java 25, Spring Boot 4.1.1, Arquitetura Hexagonal, MongoDB, Redis, Tomcat 11 (virtual threads)
**Objetivo:** Validar a performance dos caminhos críticos (encurtamento e redirecionamento), resolver a pendência "like-for-like" da baseline (isolar efeito da plataforma Tomcat 11 vs Undertow) e provar que os SLOs de latência do `slos.md` (p99 < 200ms) são atendidos sob carga nominal e de estresse.

---

## Estado do repo (pré-existente, não é novo trabalho)

O repo **já possui** o núcleo que este épico imaginava construir:

- **Harness k6 real:** `load-tests/{shorten,redirect,mixed}.js` (thresholds-as-code `p95 < 200ms`, `http_req_failed < 0.1%`) + `scripts/performance-baseline.sh` (boot do app com rate limits relaxados, executa os 3 cenários, exporta summary JSON e imprime tabela p50/p95/p99). k6 resolve para binário nativo se presente, senão container `grafana/k6` (host networking). Script aceita `[duration] [redirect-rps] [shorten-rps]` e modo isolated `BASELINE_SKIP_COMPOSE=1` com `PORT`/`MONGODB_URI`/`REDIS_HOST`/`REDIS_PORT`.
- **Baseline publicada:** `docs/load-test-baseline.md` — 2026-09-09 post-platform-upgrade (shorten p95 24.1 ms @ 20 rps, redirect p95 12.8 ms @ 200 rps, mixed p95 13.3 ms). Todos os thresholds passaram (k6 exit 0).
- **Cache-aside em produção:** `RedisUrlCache` = Caffeine L1 (100 itens / 5s TTL, **hardcoded**) + bloom filter Redisson (100M / 1% fpp) + Redis L2. Caffeine já é dependência no `pom.xml`.
- **Índices MongoDB migrados:** `MongoSchemaMigrator` V1–V7 (short code É o `_id` — não existe campo `short_code`; V3 `userId`, V4 `click_events` composto `(shortCode, timestamp)` + `(timestamp)`, V5 TTL `expiresAt`, V6 `users` (email único, plan, n), V7 `(userId, createdAt DESC)` para cursor pagination).
- **Métricas frozen:** 24 séries reais via `MetricsPort` → `MicrometerMetricsAdapter` (nomes SEM prefixo `dargent_`), gate `scripts/check-metrics-frozen.sh` (CI + self-test). SLO real: p99 < 200ms (enforced por k6 `p95 < 200ms`).

## Por que este épico agora?

- **EP3 (Observable)** forneceu as métricas (`shorten.latency`, `redirect.latency`, `url.retrieval.duration`, `cache.hits.total`/`cache.misses.total`, `bloomfilter.rejections.total`) que permitem medir latência e hit-ratio sob carga.
- **EP4 (Testes)** trouxe os *IT* (incl. `ReadPathIT`) que impedem regressões nas mudanças de performance.
- **Pendência como pauta:** `docs/load-test-baseline.md` declara que os tails 40–85% acima da baseline 2026-08-27 **não podem ser atribuídos** ao Tomcat 11 vs Undertow, porque a stack de medição mudou junto (k6 v0.58.0 → v2.2.0, Redis 7 → 8.10.1). O Épico 5 resolve isso re-executando a mesma stack atual e comparando run-to-run.

## Critério de Aceitação (aterrado)

1. `./mvnw verify` → **verde** com todos os gates (unit, IT, JaCoCo, SpotBugs, OWASP, check-doc-sync, check-boundaries).
2. **SLOs de latência comprovados** pelo harness k6 real (p95 `GET /{id}` ≤ 200ms e p95 `POST /api/v1/urls` ≤ 200ms — o alvo global do `slos.md` é p99 < 200ms; não existe "S3 = 300ms" no repo).
3. **Pendência like-for-like resolvida:** baseline re-executada na mesma stack (k6 v2.2.0, Redis 8.10.1, Mongo 6.0.28) e comparada com 2026-09-09; veredito documentado (regressão real da plataforma vs variância de medição).
4. **Caffeine L1 externalizado** para `@ConfigurationProperties` (hoje `maximumSize(100)`/`expireAfterWrite(5s)` hardcoded em `RedisUrlCache`), com IT validando override.
5. **Profiling JFR** (built-in JDK 25 via `jcmd`; async-profiler não está instalado e não é necessário) do hot-path sob carga; ≥2 achados documentados em `docs/performance-profiling.md`, mitigações aplicadas se justificadas pelos dados.
6. **Stress 2× SLO:** novo `load-tests/stress.js` (ramping até 2× rps nominal) rodando 10min; degradação/5xx documentada.
7. **Rule zero — zero‑from‑memory:** todo número, sha ou contagem nas evidências é colado de output de comando real.

**Rastreabilidade rápida:**

| Story | Doc referência | Aspecto chave |
|-------|----------------|---------------|
| 5.1 | `slos.md` + `docs/load-test-baseline.md` | Latência p95 GET /{id} + like-for-like |
| 5.2 | `slos.md` + `load-tests/shorten.js` | Latência p95 POST /api/v1/urls |
| 5.3 | JFR via `jcmd` | Gargalos identificados e mitigados |
| 5.4 | `RedisUrlCache` + métricas frozen | Cache-aside L1/bloom externalizado e evidenciado |
| 5.5 | `load-tests/stress.js` + `slos.md` | Estabilidade sob carga 2× / ramping |

---

*Próximo passo: executar as stories 5.1–5.5 (definição no `epic-5-stories.md`) e as tasks correspondentes (`epic-5-technical-tasks.md`).*