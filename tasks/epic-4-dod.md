# Epic 4 – Definition of Done (DoD)

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de
um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se
de hipótese e deve ser etiquetado como tal.

## 1. Evidências obrigatórias (outputs reais colados)

```bash
# 4.1 pirâmide: unit (sem Docker) e IT singleton — tempos reais
time ./mvnw test
time ./mvnw test -Dtest='*IT'

# 4.2 gate de fronteiras + self-test + ArchUnit (job Unit Tests do CI)
bash scripts/check-boundaries.sh
bash scripts/check-boundaries.sh --self-test

# 4.3 SSRF + ConfigValidator + Security Headers (+ gap IPv4-mapped novO)
./mvnw test -Dtest='SsrfProtectionIT,ProdConfigValidatorIT,SecurityHeadersIT,DefaultUrlValidatorTest' --no-transfer-progress 2>&1 | tail -8

# 4.4 frozen + health + diagnóstico
bash scripts/check-metrics-frozen.sh
promtool check rules deploy/monitoring/alerts.yml deploy/monitoring/recording-rules.yml
promtool test rules deploy/monitoring/rules_tests.yml
promtool check config deploy/monitoring/prometheus.yml
amtool check-config deploy/monitoring/alertmanager.yml
./mvnw test -Dtest='ProductionLockdownIT' --no-transfer-progress 2>&1 | tail -4
bash scripts/debug-health.sh

# 4.5 rastreabilidade + CI
bash scripts/check-doc-sync.sh
gh run list --limit 8
gh run view <RUN_ID> --json jobs -q '.jobs[] | "\(.name): \(.conclusion)"'

# gate completo
./mvnw verify 2>&1 | grep -iE "BUILD|coverage|Tests run"

# Limpeza de árvore
git status --porcelain
```

## 2. Self‑audit — rode ANTES de enviar (qualquer "não" = corrigir o handoff, não o audit)

- [ ] Cada sha resolve: `git cat-file -e <sha>` para cada um citado acima
- [ ] Cada (número de run, sha) par aparece idêntico no `gh run list` colado
- [ ] Cada contagem (ex.: 270 unit, 139+101 IT, 24 séries, 3+4 regras) igual ao output colado
      (nunca arredondado, nunca lembrado)
- [ ] Todo vermelho está NA tabela com seu par (um vermelho em footnote = hipótese/template)
- [ ] Todo "owner approved X" CITA a mensagem do channel que aprovou
- [ ] Todo claim sobre `main` é verdadeiro de `main`: trabalho que está só local está etiquetado
      `LOCAL — awaiting push`
- [ ] Nenhum claim de closure: closure é adjudicado pelo canal owner; hand‑offs reportam estado +
      gaps
- [ ] Flip = último commit de conteúdo; cada evidência de CI é sobre a tree do flip

## 3. Definições permanentes

- **Par** = (test, número de run, sha). Ids sozinhos apodrecem; números sozinhos driftam; ambos, de
  `gh run list`.
- **Evidência** = output de comando colado. Memória = hipótese. Hipóteses são etiquetadas como tais.
- **Sanção do dono** = uma mensagem de canal citada.
- **LOCAL** prefix = verdadeiro e não landado. Nunca up‑grade LOCAL para landed.

## 4. Falhas que este código codifica (o registro E9 — por que cada regra existe)

| Regra | A falha que mata |
|---|---|
| §1 `gh run list` | IDs de run inventados; números fora do esperado |
| §1 surefire/grep | Contagens inventadas; templates de outro projeto (ex.: `dargent_*`, "12 séries",
  "Java 21/Boot 3.5.7", "SSRFIT (13)") citados como se fossem este repo |
| §2 sha check | Citar commits que resolvem em lugar nenhum |
| §2 main-claims | "landed" para trabalho local |
| §2 owner-quote | Atribuição sem citação do canal |
| §1 drift | Copiar nomes/gates de outro projeto (`MetricsConfig.java`, `test.yml`) em vez de aterrar
  no código real (`MicrometerMetricsAdapter`, `check-metrics-frozen.sh`, `ci.yml`) |
| §2 no-closure | Fechamento que não foi adjudicado pelo owner |

## 5. Checklist de conclusão do Épico 4

- [ ] `./mvnw test` → verde (270 unit, sem Docker) e `./mvnw test -Dtest='*IT'` → verde (139 IT,
      singleton) — tempos colados
- [ ] `check-boundaries.sh` + `--self-test` + ArchUnit → PASS
- [ ] `SsrfProtectionIT` (9, com IPv4‑mapped), `ProdConfigValidatorIT` (5), `SecurityHeadersIT` (3)
      → verdes
- [ ] `check-metrics-frozen.sh` (24 séries) + `promtool` + `amtool` → verdes
- [ ] `ProductionLockdownIT` (7/7) + `debug-health.sh` → verdes
- [ ] `check-doc-sync.sh` → PASS
- [ ] `ci.yml` (5 jobs) verde no run do flip
- [ ] `./mvnw verify` completo verde (JaCoCo, SpotBugs, OWASP) — retro‑compatível EP1–EP3

*Ao marcar todos os itens acima, o Épico 4 está **concluído** e o próximo épico (EP5 – Performance)
pode iniciar.*

---

*Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 4. Sem o bloco de
evidências e o self‑audit, o hand‑off será rejeitado pelo canal owner.*