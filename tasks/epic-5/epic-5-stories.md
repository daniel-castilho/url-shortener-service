# Epic 5 – Stories (Aceitação)

| # | Story | Critérios de Aceitação | Referência Ágil / Âncora |
|---|-------|------------------------|--------------------------|
| **5.1** | **Validação de SLO de latência p95** – confirmar que a rota `GET /{id}` tem p95 ≤ 200 ms sob carga normal (sem chaos). | • `k6` script `GET /{id}` com carga de 500 req/s durante 5 min → p95 medido no relatório ≤ 200 ms. <br>• `metrics-frozen-check` PASS – série `dargent_redirect_latency_seconds` p95 dentro do limite. <br>• Relatório `k6` colado no `epic-5-dod.md`. | Histórias 2‑3 do `slos.md` |
| **5.2** | **Validação de SLO de latência p95 (shorten)** – confirmar que a rota `POST /v1/urls` tem p95 ≤ 300 ms sob carga normal. | • `k6` script `POST /v1/urls` com carga de 300 req/s durante 5 min → p95 medido no relatório ≤ 300 ms. <br>• Série `dargent_url_shortened_total` e `dargent_redirect_latency_seconds` dentro dos limites. <br>• Relatório `k6` colado no `epic-5-dod.md`. | Histórias 2‑3 do `slos.md` |
| **5.3** | **Perfil de JVM e redução de gargalos** – identificar e mitigar no mínimo dois gargalos (ex.: coleta de GC, contention de threads, queries MongoDB sem índice). | • Perfil via `async-profiler` ou `jvm‑perf‑collector`; relatório listando os dois gargalos e a mitigação aplicada. <br>• Após a mitigação, `mvn verify` permanece verde e o p95 dos SLOs não se degrada (≥ 5 % de melhoria ou manutenção dentro do limite). <br>• Mitigação documentada em `docs/performance-profiling.md`. | Boas práticas de profiling Java |
| **5.4** | **Cache‑aside com Caffeine + Bloom filter** – garantir que o lookup de código curto tenha latência < 5 ms na maior parte das requisições. | • Benchmark `Caffeine` cache lookup vs MongoDB `findOne`; latência média de cache ≤ 5 ms. <br>• `dargent_cache_hits_total` e `dargent_cache_misses_total` refletem o comportamento esperado. <br>• Configurações de TTL e tamanho máximo definidas em `application.yml`. | Padrão de cache‑aside (EP3) |
| **5.5** | **Teste de carga e estresse (k6)** – validar que o sistema mantém estabilidade sob carga acima do esperado (pico 2× SLO). | • Script `k6` com carga ascendente (até 2 × SLO throughput) por 10 min. <br>• Nenhum `5xx`; latência p95 mantém‑se dentro dos SLOs ou degradação esperada é documentada. <br>• Relatório `k6` anexado ao `epic-5-dod.md`. | Testes de carga e estresse (EP4) |

---

**Rastreabilidade rápida:**

| Story | Doc referência | Aspecto chave |
|-------|----------------|---------------|
| 5.1 | `slos.md` S2 | Latência p95 GET /{id} |
| 5.2 | `slos.md` S3 | Latência p95 POST /v1/urls |
| 5.2 | `k6` scripts + `MetricsIT` | Throughput e estabilidade |
| 5.3 | Perfil JVM + `async‑profiler` | Gargaros identificados e mitigados |
| 5.4 | Cache‑aside com Caffeine + Bloom filter | Latência de lookup < 5 ms |
| 5.5 | `k6` + SLOs | Estabilidade under load |

--- 

*Próximo passo: criar as tasks técnicas (5.1‑5.5) e a estratégia de teste (epic-5-testing.md).*