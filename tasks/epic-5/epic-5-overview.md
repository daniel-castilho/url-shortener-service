# Epic 5: Performance – Latência e Throughput

**Projeto:** url-shortener-service  
**Contexto:** Java 21, Spring Boot 3.5.7, Arquitetura Hexagonal, MongoDB, Redis, Undertow  
**Objetivo:** Validar e melhorar a performance dos caminhos críticos (encurtamento e redirecionamento), garantindo que os SLOs de latência (S2/S3 do slos.md) sejam consistentemente atendidos e que o sistema mantenha stable throughput sob carga.

---

## Por que este épico em quinto lugar?

- **EP1 (Maintainable)** fornece o código limpo e padrões necessários para medição e otimização confiáveis.
- **EP2 (Secure)** garante que as otimizações de performance não abram brechas de segurança (ex.: validações de SSRF, logs sanitizados).
- **EP3 (Observable)** fornece as métricas e logs fundamentais para medir latência, throughput e identificar gargaros.
- **EP4 (Testes)** traz a base de testes automatizados (unitários, IT, k6) que validam que as mudanças de performance não causam regressões.
- **EP6 (Scalable)** depende de performance estável para definir horizontes de escalonamento (horizontal vs vertical).

**Relação com outros épicos:**

| Epico | Dependência direta |
|--------|-------------------|
| EP1 – Maintainable | Código limpo, convenções de nomeação que facilitam profiling e otimização. |
| EP2 – Secure | Validações de entrada e sanitização que não devem ser removidas em nome de performance. |
| EP3 – Observable | Métricas Prometheus (`dargent_*`) e health checks que definem e medem os SLOs. |
| EP4 – Testes | Suite de testes (unitários + IT + k6) que impede regressões de performance. |
| EP6 – Escalável | Resultados de performance norteiam decisões de escalonamento (máquinas, containers, sharding). |
| EP7 – Reliable | Comportamento consistente sob carga influencia a tolerância a falhas e circuit‑breaker thresholds. |
| EP8 – Deployable | Resultados de benchmark podem impactar a escolha de imagem Docker, flags JVM e configuração de threads. |

**Critério de Aceitação Elevado:**

1. `mvn verify` → **verde** com todos os gates (unit, IT, JaCoCo, SpotBugs, ArchUnit, OWASP).
2. **SLOs de latência** comprovados pelo `metrics-frozen-check` e por benchmarks `k6` (p95 da rota `GET /{id}` ≤ 200 ms; p95 da rota `POST /v1/urls` ≤ 300 ms, conforme `slos.md` S2/S3).
3. **Benchmarks JMH** ou `k6` relatório anexado ao `epic-5-dod.md` mostrando throughput mínimo aceitável (ex.: ≥ 1 000 req/s no caminho de encurtamento).
4. **Zero regressão** de performance: `mvn verify` em `main` (flip atual) continua verde; quaisquer mudanças de performance são acompanhadas de novo benchmark e documentação.
5. **Rule zero — zero‑from‑memory:** todo número, sha ou contagem nas evidências é colado de output de comando real.

**Rastreabilidade rápida:**

| Story | Doc referência | Aspecto chave |
|-------|----------------|---------------|
| 5.1 | `slos.md` S2/S3 | Latência p95 nas rotas críticas |
| 5.2 | `k6` scripts + `MetricsIT` | Throughput e estabilidade under load |
| 5.3 | Perfil JVM + `java -XX:...` | Gargaros identificados e mitigados |
| 5.4 | Queries MongoDB/Redis otimizadas | Redução de latência de I/O |
| 5.5 | `mvn verify` verde + SLOs validados | Nenhuma regressão de performance |

--- 

*Próximo passo: criar as stories detalhadas (5.1‑5.5) e as tasks técnicas correspondentes.*