# Epic 1 – Definition of Done (DoD)

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

## 1. Evidências obrigatórias (outputs reais colados)

```bash
# O chain exact — nada mais
git log --oneline main..HEAD

# Proof de árvore limpa
git status --porcelain

# Pares run (número E sha) VÊM DE AQUI — nunca da memória
gh run list --limit 10

# Surefire summary para cada classe que citar
grep -h "Tests run" **/target/surefire-reports/*.txt | tail -20
```

## 2. Self‑audit — rode ANTES de enviar (qualquer "não" = corrigir o handoff, não o audit)

- [x] Every sha resolves: `git cat-file -e <sha>` para cada um citado _(7/7 OK: 8becb4c, 3675010, 1f05e95, 7989cd5, b0d7e64, bed9d3d, 0c30e82 — todos em origin/main)_
- [x] Cada (número de run, sha) par aparece idêntico no `gh run list` colado _(34543217783→bed9d3d, 34543733751→0c30e82, ambos "completed success")_
- [x] Cada contagem igual ao output surefire/grep (nunca arredondado, nunca lembrado; omitir contagens é sempre aceitável — inventá‑las nunca é) _(269 unit / 114 IT colados do verify; core 590/651 LINE = 90.6%, 213/263 BRANCH = 81.0%, colados do jacoco.csv)_
- [x] Todo vermelho está NA tabela com seu par (um vermelho em footnote = TD‑13) _(nenhum vermelho: 0 failures, 0 errors, 0 skipped; 0 `@Disabled` no tree)_
- [x] Todo "owner approved X" CITA a mensagem do channel que aprovou _(Q1–Q5 da sessão de planejamento 2026‑09‑10: baseline curado; Spotless+ArchUnit aprovados = Regra 9; manter layout; medir e subir floor; commit por fase; push autorizado na mensagem "Pode dar push.")_
- [x] Todo claim sobre `main` é verdadeiro de `main`: trabalho que estiver só local está etiquetado `LOCAL — awaiting push`, nunca descrito como "landed" _(todos os 7 commits verificados em origin/main via `git branch -r --contains`)_
- [x] Nenhum claim de closure: closure é adjudicado pelo canal owner; hand‑offs reportam estado + gaps _(este documento reporta estado; a última linha do §3 declina a closure)_
- [x] Flip = último commit de conteúdo; citation = commit final separado, citando um run cujo tree É o flip, com nada landed depois dele _(flip = bed9d3d, citation = 0c30e82 com run 34543733751 verde, nada landed depois; tree clean)_

## 3. Definições permanentes

- **Par** = (test, número de run, sha). Ids sozinhos apodrecem; números sozinhos driftam; ambos, de `gh run list`.
- **Evidência** = output de comando colado. Memória = hipótese. Hipóteses são etiquetadas como tais.
- **Sanção do dono** = uma mensagem de canal citada. Coisa alguma outra é atribuição; falsa atribuição é classe TD‑30.
- **LOCAL** prefix = verdadeiro e não landado. Nuncaupgrade LOCAL para landed.

## 2. Falhas que este código codifica (o registro E9 — por que cada regra existe)

| Regra | A falha que mata |
|---|---|
| §1 `gh run list` | IDs de run inventados (ex.: #167, 33943000000); números fora do esperado (ex.: #155→#156) |
| §1 surefire/grep | Contagens inventadas (ex.: 21/21, 22, 1/1·6/6·2/2·10/10·3/3 vs real 31/34) |
| §2 sha check | Citar commits que resolvem em lugar nenhum |
| §2 main-claims | "re-enabled" contra commit message lendo "disabled (HOLD)"; narrativa Known‑Gap sobre um teste @Disabled |
| §2 owner-quote | `@Disabled("HOLD: owner re-baselining…")` sem autorização do owner |
| §1 arithmetic | Lista correta por classe, soma errada (TD‑34: 1+6+2+10+3+1 dito como 22 — a própria correção TD‑31 carregava o off‑by‑one que corrigiu) |
| §2 no-closure | Quatro declarações consecutivas "E9 CLOSED" de um mesmo épico |

## 3. Checklist de conclusão do Épico 1

- [x] `check-boundaries.sh` → **PASS (0 violações)** _(2026‑09‑10, output colado: "PASS: Architecture boundary check passed (0 violations).")_
- [x] `check-boundaries.sh --self-test` → **PASS** _(colado: "PASS: self-test verified — gate detects violations and allows clean code.")_
- [x] `lessons.md` → lição(ões) promovida(s) para `coding-standards.md` _(4 marcações `→ coding-standards §14.x`: Metrics/counters §14.2, Caching/bloom §14.1, Fail-open-vs-fail-fast §14.1, OTLP §14.1 — verificadas por `check-doc-sync.sh`)_
- [x] `coding-standards.md` → última versão com todas as regras novas _(§14 "Herde‑de‑Lições" adicionado: §14.1 + §14.2, promovidas 2026‑09‑10)_
- [x] `AGENTS.md` → matriz de dívida sincronizada; status de cada item atualizado (open/in‑progress/resolved) _(22/22 itens `resolved`; item 22 registra este épico; gate `check-doc-sync.sh` PASS)_
- [x] `./mvnw verify` (gate completo) → todos os sub‑gates verdes (unit, SpotBugs, JaCoCo, ArchUnit) _(colado: "Tests run: 269" + "Tests run: 114, Failures: 0, Errors: 0, Skipped: 0" + "All coverage checks have been met." + "BUILD SUCCESS"; ArchUnit BoundaryRulesTest 2/2 + SelfTestTest 2/2; core LINE 90.6%/BRANCH 81.0% contra floor 70/70)_
- [x] Handoff‑DOD → bloco de evidências colado; self‑audit verde; nenhuma hipótese sem etiquetar _(bloco entregue na sessão de 2026‑09‑10; 7/7 shas resolvem via `git cat-file -e` e em origin/main; 0 @Disabled; runs 34543217783 e 34543733751 colados de `gh run list`)_

*Checklist do hand‑off (self‑audit §2) verificado em 2026‑09‑10 contra outputs colados. Closure do épico é adjudicada pelo canal owner.*

--- 

*Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 1. Sem o bloco de evidências e o self‑audit, o hand‑off será rejeitado pelo canal owner.*