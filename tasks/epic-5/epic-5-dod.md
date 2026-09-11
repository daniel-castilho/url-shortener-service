# Epic 5 – Definition of Done (DoD) [aterrado]

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

## 1. Evidências obrigatórias (outputs reais colados)

```bash
# 5.1–5.2 k6 baseline re-run + like-for-like (gravar o STAMP e colar o summary export)
bash scripts/performance-baseline.sh 1m 200 20
#   -> load-tests/results/{shorten,redirect,mixed}-<STAMP>.summary.json
#   -> tabela p50/p95/p99 no log; saída completa colada abaixo

# 5.3 profiling JFR do hot-path (durante uma janela do k6)
jcmd <pid> JFR.start name=epic5-profile settings=profile
jcmd <pid> JFR.dump name=epic5-profile filename=epic5.jfr
jfr summary epic5.jfr
#   -> achados >= 2 e mitigação (se aplicada) colados abaixo

# 5.4 cache L1 externalizado + IT
./mvnw test -Dtest='*Cache*IT' 2>&1 | tail -20
#   -> override app.cache.l1.* validado; métricas frozen inalteradas

# 5.5 stress 2x (ramping até 2x rps nominal, 10min)
k6 run load-tests/stress.js --summary-export=load-tests/results/stress-<STAMP>.summary.json
#   -> summary do stress colado abaixo (5xx / p95 degradado documentado)

# Gates do épico
bash scripts/check-metrics-frozen.sh && bash scripts/check-metrics-frozen.sh --self-test
bash scripts/check-boundaries.sh && bash scripts/check-boundaries.sh --self-test
bash scripts/check-doc-sync.sh && bash scripts/check-doc-sync.sh --self-test
promtool check rules deploy/monitoring/recording-rules.yml deploy/monitoring/alerts.yml
promtool test rules deploy/monitoring/rules_tests.yml
amtool check-config deploy/monitoring/alertmanager.yml

# 5.x integração completa
./mvnw verify --no-transfer-progress 2>&1 | grep -E "BUILD|SUCCESS|FAILURE|Tests run"

# Limpeza de árvore
git status --porcelain
```

## 2. Self‑audit — rode ANTES de enviar (qualquer "não" = corrigir o handoff, não o audit)

- [ ] Cada sha resolve: `git cat-file -e <sha>` para cada um citado acima
- [ ] Cada (número de run, sha) par aparece idêntico no `gh run list` colado (use `gh run list --limit 5`)
- [ ] Cada contagem (por ex., número de testes verdes, hits do grep) igual ao output colado (nunca arredondado, nunca lembrado)
- [ ] Todo vermelho está NA tabela com seu par (um vermelho em footnote = TD‑13)
- [ ] Todo "owner approved X" CITA a mensagem do channel que aprovou
- [ ] Todo claim sobre `main` é verdadeiro de `main`: trabalho que está só local está etiquetado `LOCAL — awaiting push`, nunca descrito como "landed"
- [ ] Nenhum claim de closure: closure é adjudicado pelo canal owner; hand‑offs reportam estado + gaps
- [ ] Flip = último commit de conteúdo; citation = commit final separado, citando um run cujo tree É o flip, com nada landed depois dele

## 3. Definições permanentes

- **Par** = (test, número de run, sha). Ids sozinhos apodrecem; números sozinhos driftam; ambos, de `gh run list`.
- **Evidência** = output de comando colado. Memória = hipótese. Hipóteses são etiquetadas como tais.
- **Sanção do dono** = uma mensagem de canal citada. Coisa alguma outra é atribuição; falsa atribuição é classe TD‑30.
- **LOCAL** prefix = verdadeiro e não landado. Nunca up‑grade LOCAL para landed.

## 4. Falhas que este código codifica (o registro E9 — por que cada regra existe)

| Regra | A falha que mata |
|---|---|
| §1 `gh run list` | IDs de run inventados; números fora do esperado |
| §1 surefire/grep | Contagens inventadas (ex.: 21/21, 22, 1/1·6/6·2/2·10/10·3/3 vs real 31/34) |
| §2 sha check | Citar commits que resolvem em lugar nenhum |
| §2 main-claims | "re-enabled" contra commit message lendo "disabled (HOLD)"; Known‑Gap narrativa sobre um teste @Disabled |
| §2 owner-quote | `@Disabled("HOLD: owner re-baselining…")` sem autorização do owner |
| §1 arithmetic | Lista correta por classe, soma errada (TD‑34: 1+6+2+10+3+1 dito como 22 — a própria correção TD‑31 carregava o off‑by‑one que corrigiu) |
| §2 no-closure | Quatro declarações consecutivas "E9 CLOSED" de um mesmo épico |

## 5. Checklist de conclusão do Épico 5

- [ ] Histórias 5.1–5.5 atendidas (evidências coladas abaixo)
- [ ] Pendência like-for-like resolvida (veredito em `docs/load-test-baseline.md`)
- [ ] Profiling JFR + achados em `docs/performance-profiling.md`; mitigações aplicadas se justificadas
- [ ] Cache L1 externalizado + IT + evidência sob carga
- [ ] Stress 2× (`load-tests/stress.js`) rodado e documentado
- [ ] `metrics-frozen-check` PASS + `promtool test rules` verde + `amtool check-config` verde
- [ ] `./mvnw verify` conjunto verde
- [ ] Evidências coladas abaixo; self-audit rodado

---

*Evidências, vereditos e self-audit são adicionados abaixo conforme as stories são executadas. Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 5.*