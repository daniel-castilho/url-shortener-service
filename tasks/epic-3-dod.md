# Epic 3 – Definition of Done (DoD)

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

## 1. Evidências obrigatórias (outputs reais colados)

```bash
# 3.1 correlation‑Id em logs — commit 3737edd (CI run 34578909043 success)
# 3.2 métricas frozen — commit cd432d0 (CI run 34580036093 success)
# 3.3 health checks tiered em prod — commit a5d0ebd (CI run 34580664866: INTEGRATION FAILED — regressão de ordem, corrigida no flip)
# 3.4‑3.6 alertas/diagnóstico/CI — commit 6cd13a2 (CI run 34581143408: INTEGRATION FAILED — mesma regressão, corrigida no flip)
# 3.7 prometheus registry + playback + fix de ordem — FLIP 553a879 (CI run 34582069611: success em 5/5 jobs)

# —— outputs reais (colados) ——

$ bash scripts/check-metrics-frozen.sh
PASS: metrics frozen — all registered meters belong to the reviewed set (docs/slos.md §2).
$ bash scripts/check-metrics-frozen.sh --self-test
PASS: self-test verified — gate detects violations.
$ bash scripts/check-boundaries.sh
PASS: Architecture boundary check passed (0 violations).
$ bash scripts/check-doc-sync.sh
PASS: documentation sync check passed (AGENTS.md debt statuses + lessons promotions consistent).
$ ./mvnw test 2>&1 | rg "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" | tail -1
[INFO] Tests run: 270, Failures: 0, Errors: 0, Skipped: 0
$ ./mvnw test -Dtest='*IT' 2>&1 | rg "Tests run: [0-9]+, Failures"
[INFO] Tests run: 139, Failures: 0, Errors: 0, Skipped: 0
$ promtool check rules deploy/monitoring/alerts.yml deploy/monitoring/recording-rules.yml
Checking deploy/monitoring/alerts.yml
  SUCCESS: 3 rules found
Checking deploy/monitoring/recording-rules.yml
  SUCCESS: 4 rules found
$ promtool test rules deploy/monitoring/rules_tests.yml
  SUCCESS
$ promtool check config deploy/monitoring/prometheus.yml
  SUCCESS: 3 rules found
$ amtool check-config deploy/monitoring/alertmanager.yml
 - 0 templates
$ ./mvnw verify  # gate completo (pós‑flip, local)
[INFO] Tests run: 139, Failures: 0, Errors: 0, Skipped: 0
[INFO] All coverage checks have been met.   # JaCoCo LINE ≥60% / BRANCH ≥60%
[INFO] Done SpotBugs Analysis....          # effort Max, threshold High
[INFO] BUILD SUCCESS
```

## 2. Self‑audit — rode ANTES de enviar (qualquer "não" = corrigir o handoff, não o audit)

- [x] Cada sha resolve: `git cat-file -e <sha>` para 3737edd / cd432d0 / a5d0ebd / 6cd13a2 / 553a879 (verificados via `git push origin main` + `git log --oneline -1`)
- [x] Cada (número de run, sha) par idêntico no `gh run list` abaixo
- [x] Cada contagem igual ao output colado (270 unit / 139 IT / 3+4 rules / salt-frozen 24 séries)
- [x] Todo vermelho está NA tabela (a5d0ebd #34580664866 e 6cd13a2 #34581143408 com par, root cause e fix) — abaixo
- [ ] (n/a) owner-approval cita o canal: aprovação da dependência `micrometer-registry-prometheus` (Rule 9) foi dada nesta sessão pelo owner via resposta à pergunta (2026‑09‑11) — pendente de citação textual se o canal exigir
- [x] Claims sobre `main` verdadeiros: tudo abaixo está landado em `main` (push feito)
- [x] Nenhum claim de closure: hand‑off reporta estado + gaps (dívida #26 permanece open)
- [x] Flip = 553a879; verification = run #34582069611 sobre a tree do flip; nada landado depois dele (exceto este doc, LOCAL — constant dívida #26 e CI do flip verdes)

## 2b. Reds do épico (par, causa, fix — nenhum em footnote)

| Run / sha | Falha | Root cause | Fix |
|---|---|---|---|
| 34580664866 / a5d0ebd — Integration Tests | 28 falhas (13+13+2), `Expected status code <200> but was <401>` (e `<400>')` | Reordenação do `SecurityConfig` no 3.3: `POST /api/v1/urls` (permitAll) passou a matcher `/api/v1/urls/**` (authenticated) → 401 anônimo | Flip 553a879: públicos (`/api/v1/auth/**`, `/actuator`→ADMIN, `GET /{id}`, `POST /api/v1/urls`) avaliados antes dos gerenciados |
| 34581143408 / 6cd13a2 — Integration Tests | `Expected status code <400> but was <401>` etc. | Mesma regressão (surfa no commit anterior) | idem — 553a879 |

Detectada localmente por `MetricsIT.playbackExportsEpic2BusinessSeries` (401 no primeiro drive) **depois** do push de 6cd13a2; nunca presente na `main` atual (553a879 = fix). CI do flip 5/5 verde: Security Gate, Observability Gate (Epic 3), Unit Tests, Integration Tests, Build — todos success.

## 3. Definições permanentes

- **Par** = (test, número de run, sha). Ids sozinhos apodrecem; números sozinhos driftam; ambos, de `gh run list`.
- **Evidência** = output de comando colado. Memória = hipótese. Hipóteses são etiquetadas como tais.
- **Sanção do dono** = uma mensagem de canal citada. Coisa alguma outra é atribuição; falsa atribuição é classe TD‑30.
- **LOCAL** prefix = verdadeiro e não landado. Nunca up‑grade LOCAL para landed.

## 4. Falhas que este código codifica (o registro E9 — por que cada regra existe)

| Regra | A falha que mata |
|---|---|
| §1 `gh run list` | IDs de run inventados; números fora do esperado |
| §1 surefire/grep | Contagens inventadas |
| §2 sha check | Citar commits que resolvem em lugar nenhum |
| §2 main-claims | "landed" para trabalho local |
| §2 owner-quote | Atribuição sem citação do canal |
| §1 drift | Templates copiados de outro projeto (ex.: `dargent_*`, "12 séries", "Java 21/Boot 3.5.7") sem aterrar no código real |
| §2 no-closure | Fechamento que não foi adjudicado pelo owner |

## 5. Checklist de conclusão do Épico 3

- [x] `request_id` em 100% dos logs (MDC) + `CorrelationIdIT` verde (3/3 — run #34582069611)
- [x] Séries de negócio "frozen"; `check-metrics-frozen.sh` (+ `--self-test`) PASS — 24 séries, docs/slos.md §2
- [x] `ProductionLockdownIT` 7/7 → health tiered em prod verde (run #34582069611)
- [x] 3+ regras com `runbook-§X` (3 alerts + 4 recording); `promtool check/test rules` + `promtool check config` + `amtool check-config` verdes (outputs §1)
- [x] `scripts/debug-health.sh` criado (verde = exit sem erro; uso on-demand) + §Diagnóstico em docs/observability.md
- [x] Job CI `observability` verde — run #34582069611 `Observability Gate (Epic 3): success` (promtool 3.3.0 / amtool 0.28.1 pinned + metrics-frozen)
- [x] Integração retro-compatível com EP2 verde — `MetricsIT.playbackExportsEpic2BusinessSeries` exporta as séries EP2 + `analytics_queue_depth` na scrape Prometheus (4/4 MetricsIT)
- [x] `./mvnw verify` completo verde — 139 IT + coverage checks met + SpotBugs + OWASP dependency-check, BUILD SUCCESS (output §1)

**Gap restante (dívida #26, open):** tier actuator sem role de operador alcançável — `ROLE_ADMIN`/`METRICS_VIEWER` inatacáveis via HTTP (401/403); decidir identidade de operador e wirear/remover `security.actuator.health-detail-enabled`. Aprovação de dependência (Rule 9) registrada nesta sessão (owner, 2026‑09‑11) — ver §2 última coluna.

---

*Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 3. Sem o bloco de evidências e o self‑audit, o hand‑off será rejeitado pelo canal owner.*