# Epic 5 – Tasks Técnicas [aterrado]

Guia de execução aterrado na realidade do repo. Marcos `[x]` são preenchidos
durante a execução; evidências coladas no `epic-5-dod.md`.

## 5.1/5.2 Re-executar baseline k6 e resolver a pendência like-for-like
- [ ] Garantir infra isolated up (Mongo 27018 + Redis 6380) ou usar docker-compose.
- [ ] Executar `bash scripts/performance-baseline.sh <duration> <redirect-rps> <shorten-rps>` na checkpoint atual de `main` (mesma stack da baseline 2026-09-09: k6 v2.2.0 container, Redis 8.10.1, Mongo 6.0.28).
- [ ] Coletar o summary export JSON (`load-tests/results/{shorten,redirect,mixed}-<STAMP>.summary.json`) e colar p50/p95/p99.
- [ ] Comparar com 2026-08-27 e 2026-09-09; atribuir ou isn't atribuir regressão à plataforma (Tomcat 11 vs Undertow).
- [ ] Atualizar `docs/load-test-baseline.md` (nova seção "Baseline — <data>", veredito da pendência).
- [ ] Colar output do k6 e do script no `epic-5-dod.md`.

## 5.3 Perfil JFR do hot-path
- [ ] Boot do app em porta isolada com rate limits relaxados (ou via `performance-baseline.sh`).
- [ ] Iniciar perfil JFR 30s via `jcmd <pid> JFR.start` (settings=profile) e despejar com `JFR.dump`.
- [ ] Analisar eventos: GC, lock contention, alocação, single-thread top.
- [ ] Registrar ≥2 achados em `docs/performance-profiling.md` (novo).
- [ ] Aplicar mitigação somente se justificada pelos dados; `./mvnw verify` → verde; confirmar p95 SLOs não degradou.
- [ ] Colar output do profiler e do `./mvnw verify` no `epic-5-dod.md`.

## 5.4 Externalizar cache L1 + evidência
- [ ] Criar `UrlCacheProperties` (`@ConfigurationProperties(prefix = "app.cache.l1")`) e registrá-lo em `infra/config`; substituir hardcodes em `RedisUrlCache` (máx. 100 / TTL 5s; bloom 100M / 1% fpp).
- [ ] Adicionar teste/IT validando override das propriedades.
- [ ] Sob carga (durante baseline ou stress), colher séries `cache.hits.total`, `cache.misses.total`, `bloomfilter.rejections.total`, timers `url.retrieval.duration`/`redirect.latency`.
- [ ] `./mvnw verify` → verde (ética: métricas frozen inalteradas — sem série nova).
- [ ] Evidências no `epic-5-dod.md`.

## 5.5 Stress 2× SLO
- [ ] Criar `load-tests/stress.js` (ramping até `REDIRECT_RPS * 2` / `SHORTEN_RPS * 2`, 10min, thresholds p95 < 200ms/err < 0.1% ou degradação documentada).
- [ ] Executar em modo isolated; coletar summary export; documentar 5xx/degradado.
- [ ] Evidências no `epic-5-dod.md`.

## 5.x Gates finais do épico
- [ ] `./scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-boundaries.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-doc-sync.sh` (+ `--self-test`) → PASS.
- [ ] `promtool check rules` + `promtool test rules` + `amtool check-config` → verdes.
- [ ] `./mvnw verify` conjunto → BUILD SUCCESS (unit + IT + JaCoCo + SpotBugs + OWASP).
- [ ] Evidências coladas no `epic-5-dod.md`; self-audit rodado.

---

**Checklist de conclusão do Épico 5:**

- [ ] Histórias 5.1–5.5 atendidas (evidências coladas)
- [ ] Pendência like-for-like resolvida (veredito em `docs/load-test-baseline.md`)
- [ ] Profiling JFR + achados/`docs/performance-profiling.md`
- [ ] Cache L1 externalizado + IT + evidência sob carga
- [ ] Stress 2× (`load-tests/stress.js`) rodado e documentado
- [ ] `metrics-frozen-check` + `promtool` + `amtool` verdes
- [ ] `./mvnw verify` conjunto verde
- [ ] Evidências coladas no `epic-5-dod.md`

*Ao marcar todos os itens acima, o Épico 5 está **concluído** com SLOs validados, pendência de baseline resolvida e cache/índices/profiling evidenciados.*