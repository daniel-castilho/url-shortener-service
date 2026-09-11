# Epic 5 – Estratégia de Testes [aterrado]

## 5.1/5.2 Testes de performance com k6 (seguir a baseline real)
- **Objetivo:** Validar os SLOs de latência (p99 < 200ms via k6 `p95 < 200ms`) e resolver a pendência like-for-like.
- **Ação:**
  - `bash scripts/performance-baseline.sh 1m 200 20` (ou duração maior) em modo isolated (Redis 8.10.1 / Mongo 6.0.28).
  - Verificar p95 `GET /{id}` ≤ 200ms e p95 `POST /api/v1/urls` ≤ 200ms no summary export.
  - Confirmar `http_req_failed < 0.1%`; latência estável no pico.
  - Comparar com baselines anteriores e documentar veredito.
- **Critério aceite:** Relatório k6 verde; p95 dentro dos SLOs; saída colada no `epic-5-dod.md`.

## 5.3 Perfil JVM (JFR) e validação de mitigações
- **Objetivo:** Confirmar que as mitigações de perfil reduziram gargalos e não introduziram regressões.
- **Ação:**
  - Perfil JFR 30s via `jcmd` durante carga k6 no hot-path (`GET /{id}`, `POST /api/v1/urls`).
  - `./mvnw verify` após as mitigações aplicadas.
  - Comparar p95 antes/para após (registrado em `docs/performance-profiling.md`).
- **Critério aceite:** `./mvnw verify` verde; p95 não se deteriora; achados documentados.

## 5.4 Cache L1 externalizado + comportamento
- **Objetivo:** Garantir que o cache-aside (L1 Caffeine + bloom + Redis L2) seja configurável e validado.
- **Ação:**
  - IT de override de `app.cache.l1.*` (size/TTL) e comportamento L1+bloom (hit/miss/bloom-negative).
  - Sob carga: colher `cache.hits.total`, `cache.misses.total`, `bloomfilter.rejections.total`, timers `url.retrieval.duration`/`redirect.latency`.
- **Critério aceite:** IT verde; métricas frozen inalteradas; evidência de hit-ratio/latência colada.

## 5.5 Stress 2× (ramping)
- **Objetivo:** Validar estabilidade sob carga acima da nominal.
- **Ação:**
  - `k6 run load-tests/stress.js` (ramping até 2× rps nominal por 10min, thresholds p95 < 200ms / err < 0.1%).
  - Documentar `5xx`, degradação de p95, comportamento rate-limiter/cache; degradação esperada é documentada, não "consertada" às cegas.
- **Critério aceite:** Relatório colado; degradação/5xx documentados no `epic-5-dod.md`.

## Gates de regressão (por story e no fim)
- [ ] `./mvnw verify` → `metrics-frozen-check` PASS + `promtool test rules` verde + `amtool check-config` verde.
- [ ] `scripts/check-boundaries.sh` (+ `--self-test`) → PASS.
- [ ] `scripts/check-doc-sync.sh` → PASS (doc sync é parte do *done*).
- [ ] Saída dos comandos colada no `epic-5-dod.md`.

---

**Checklist de conclusão do Épico 5:**

- [x] k6 scripts gerados/validados (p95 dentro dos limites; like-for-like resolvido)
- [x] Perfil JFR concluído e mitigações avaliadas (nenhuma justificada pelos dados)
- [x] Cache L1 externalizado e evidenciado
- [x] `metrics-frozen-check` PASS + `promtool test rules` verde + `amtool check-config` verde
- [x] `./mvnw verify` conjunto verde
- [x] Evidências coladas no `epic-5-dod.md`

*Ao marcar todos os itens acima, o Épico 5 está **concluído**.*