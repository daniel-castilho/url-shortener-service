# Epic 5 – Stories (Aceitação) [aterrado]

| # | Story | Critérios de Aceitação (aterrados) | Referência Real |
|---|-------|------------------------|--------------------------|
| **5.1** | **Validação de SLO de latência p95 (redirect) + pendência like-for-like** – confirmar que `GET /{id}` tem p95 ≤ 200ms sob carga nominal e resolver a nota de `docs/load-test-baseline.md` (tails 40–85% acima da baseline 08-27, stack de medição mudou junto). | • Re-executar `load-tests/redirect.js` via `scripts/performance-baseline.sh` na checkpoint de main atual (mesma stack: k6 v2.2.0 container, Redis 8.10.1, Mongo 6.0.28) → p95 ≤ 200ms no summary export. <br>• Comparação das 3 baselines (08-27, 09-09, nova) com veredito sobre regressão real vs variância. <br>• `docs/load-test-baseline.md` atualizado. <br>• Relatório k6 colado no `epic-5-dod.md`. | `slos.md` (p99 < 200ms via k6 `p95 < 200ms`); `docs/load-test-baseline.md` |
| **5.2** | **Validação de SLO de latência p95 (shorten)** – confirmar que `POST /api/v1/urls` tem p95 ≤ 200ms sob carga nominal. | • Re-executar `load-tests/shorten.js` (thresholds k6 `p95 < 200ms`, `http_req_failed < 0.1%`) → p95 dentro do limite. <br>• Relatório k6 colado no `epic-5-dod.md`. | `slos.md` (alvo único p99 < 200ms; **não existe** "S3 = 300ms" no repo) |
| **5.3** | **Perfil JVM e mitigação de gargalos** – identificar ≥2 gargalos no hot-path e aplicar mitigação se justificada pelos dados. | • Perfil **JFR** (built-in JDK 25 via `jcmd`; async-profiler **não instalado** e não necessário) de 30s durante carga k6 nas rotas `GET /{id}` e `POST /api/v1/urls`. <br>• ≥2 achados (ex.: GC pause, contention, alocação no caminho crítico) documentados em `docs/performance-profiling.md`. <br>• Mitigações aplicadas **somente** se os dados justificarem; `./mvnw verify` permanece verde; p95 não se degrada. | Boas práticas de profiling Java; JFR no JDK 25 |
| **5.4** | **Cache-aside externalizado + evidência** – garantir lookup de código curto com hit de cache rápido e config externalizada. | • `maximumSize(100)`/`expireAfterWrite(5s)` (hardcoded em `RedisUrlCache`) externalizados para `@ConfigurationProperties` (`app.cache.l1.*`), env-overridable. <br>• IT validando override e comportamento do L1+bloom. <br>• Evidência sob carga: hit-ratio e latência (séries frozen `cache.hits.total`, `cache.misses.total`, `bloomfilter.rejections.total`, timer `url.retrieval.duration` + `redirect.latency`). | Padrão de cache-aside (EP3); métricas frozen `slos.md` §2 |
| **5.5** | **Teste de carga e estresse (k6)** – validar estabilidade sob carga acima da nominal (pico 2× SLO). | • Novo `load-tests/stress.js` com ramping até 2× rps nominal (redirect 400 / shorten 40 por 10min) e thresholds. <br>• Run documentado: `5xx`, degradação de p95, comportamento do rate-limiter/cache; degradação esperada documentada (não "consertar" thresholds). <br>• Relatório k6 anexado ao `epic-5-dod.md`. | Testes de carga e estresse (k6) |

---

**Rastreabilidade rápida:**

| Story | Doc referência | Aspecto chave |
|-------|----------------|---------------|
| 5.1 | `slos.md` + `docs/load-test-baseline.md` | Latência p95 GET /{id}; like-for-like resolvido |
| 5.2 | `slos.md` + `load-tests/shorten.js` | Latência p95 POST /api/v1/urls |
| 5.3 | Perfil JFR via `jcmd` | Gargalos identificados e mitigados |
| 5.4 | `RedisUrlCache` + métricas frozen | Cache L1/bloom externalizado e evidenciado |
| 5.5 | `load-tests/stress.js` + `slos.md` | Estabilidade under load (2×/ramping) |

---

*Executar as stories 5.1–5.5 na ordem do `epic-5-technical-tasks.md`.*