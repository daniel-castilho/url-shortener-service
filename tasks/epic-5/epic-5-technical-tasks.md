# Epic 5 – Tasks Técnicas

## 5.1 Perfil do hot‑path e identificação de gargalos
- [ ] Utilizar `async-profiler` ou `jvm-perf-collector` para gerar perfil de 30 s nas rotas `GET /{id}` e `POST /v1/urls`.
- [ ] Identificar os 3 maiores consumidores de tempo (método, biblioteca, query de banco).
- [ ] Registrar hallazgos em `docs/performance-profiling.md` (ex.: “GC pause > 50 ms em…”, “contention de lock em Redis client”).
- [ ] Aplicar uma mitigação (ex.: ajustar flags `-XX:+UseG1GC`, aumentar heap, otimizar query MongoDB com novo índice).
- [ ] Executar `mvn verify` → verde; confirmar que p95 dos SLOs não se deteriorou.
- [ ] Colar output do profiler e do `mvn verify` no `epic-5-dod.md`.

## 5.2 Otimizar queries MongoDB e índices
- [ ] Revisar o(s) índice(s) existente(s) nas coleções `shorturls` e `click_events`.
- [ ] Criar índice composto `{short_code: 1, created_at: -1}` para queries de listagem e redirecionamento.
- [ ] Garantir índice único em `short_code` (já existente) e índice de texto para buscas avançadas (se houver).
- [ ] Executar `mvn verify` → verde.
- [ ] Colar output do `mongosh`/`compass` e do `mvn verify` no `epic-5-dod.md`.

## 5.3 Configurar benchmarks k6 e validar SLOs
- [ ] Criar script `k6` (`scripts/perf/load-test.k6`) que:
    - Testa `GET /{id}` com carga ascendente (100 req/s → 1 000 req/s) por 5 min cada nível.
    - Testa `POST /v1/urls` com carga similar.
    - Exporte resultados em JSON e HTML.
- [ ] Executar `k6 run scripts/perf/load-test.k6` → gerar relatório.
- [ ] Verificar que p95 `GET /{id}` ≤ 200 ms e p95 `POST /v1/urls` ≤ 300 ms (S2/S3 do `slos.md`).
- [ ] Colar output do `k6` e o relatório no `epic-5-dod.md`.

## 5.4 Validar métricas “frozen” sob carga
- [ ] Executar `mvn verify` → `metrics-frozen-check` PASS.
- [ ] Confirmar que as 24 séries `dargent_*` permanecem estáveis under load (sem novas séries aparecendo).
- [ ] Executar `promtool test rules` → verde.
- [ ] Executar `amtool check-config` → verde.
- [ ] Colar output dos comandos no `epic-5-dod.md`.

## 5.5 Integração retro‑compatível com EP2 e EP3
- [ ] Executar `mvn verify` conjunto (EP1+EP2+EP3+EP5) → verde.
- [ ] Verificar que métricas de segurança (ex.: `dargent_security_*_total`) continuam a ser coletadas.
- [ ] Confirmar que as séries Prometheus “frozen” não têm colisão com as de EP2/EP3.
- [ ] Colar trecho do `mvn verify` conjunto no `epic-5-dod.md`.

## 5.6 Checklist de conclusão do Épico 5
- [ ] Profiling de hot‑path concluído e mitigações aplicadas
- [ ] Índices MongoDB otimizados
- [ ] Scripts k6 gerados e SLOs validados (p95 dentro dos limites)
- [ ] `metrics-frozen-check` PASS + `promtool test rules` + `amtool check-config` verdes
- [ ] `mvn verify` conjunto (EP1‑EP5) verde
- [ ] Evidências coladas no `epic-5-dod.md`

--- 

**Checklist de conclusão do Épico 5:**

- [ ] Histórias 5.1‑5.5 atendidas
- [ ] Profiling + mitigações aplicadas
- [ ] Índices MongoDB otimizados
- [ ] k6 scripts + SLOs validados
- [ ] `metrics-frozen-check` + `promtool` + `amtool` verdes
- [ ] `mvn verify` conjunto verde
- [ ] Evidências coladas no `epic-5-dod.md`

*Ao marcar todos os itens acima, o Épico 5 está **concluído** e o projeto entra na fase de manutenção de longo prazo com SLOs validados e performance comprovada.*