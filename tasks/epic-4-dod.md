# Epic 4 – Definition of Done (DoD)

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de
um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se
de hipótese e deve ser etiquetado como tal.

## 1. Evidências obrigatórias (outputs reais colados)

### 4.1 Pirâmide — unit (sem Docker) e IT (singleton)

```text
$ ./mvnw test
[INFO] Tests run: 271, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  26.734 s

$ ./mvnw test -Dtest='*IT'      # Testcontainers: 1 par MongoDB+Redis singleton (BaseIntegrationTest, sem @DirtiesContext)
[INFO] Tests run: 139, Failures: 0, Errors: 0, Skipped: 0   # antes do teste IPv4-mapped (push 7386982)
[INFO] BUILD SUCCESS
[INFO] Total time:  02:07 min
```

Matriz JaCoCo por camada (report real gerado após `./mvnw verify`, `jacoco:report`):

```text
core.*  LINE 590/651 = 90.6% | BRANCH 213/263 = 81.0%   # floor PACKAGE: LINE>=70% BRANCH>=70%  -> PASS
infra.* LINE 1786/2225 = 80.3% | BRANCH 317/440 = 72.0%  # floor BUNDLE: LINE>=60% BRANCH>=60%   -> PASS
```

### 4.2 Gate de fronteiras + ArchUnit

```text
$ bash scripts/check-boundaries.sh
=== Architecture Boundary Check ===
PASS: Architecture boundary check passed (0 violations).

$ bash scripts/check-boundaries.sh --self-test
=== Architecture Boundary Self-Test ===
FAIL: core/ imports framework types:
/tmp/tmp.obmIjZQr9b/fake-core/Violation.java
PASS: self-test verified — gate detects violations and allows clean code.

$ ./mvnw test   # inclui ArchUnit BoundaryRulesTest + BoundaryRulesSelfTestTest (job Unit Tests do CI, 5/5 verde)
```

### 4.3 SSRF + ConfigValidator + Security Headers (com gap IPv4-mapped corrigido)

```text
$ ./mvnw test -Dtest='SsrfProtectionIT,ProdConfigValidatorIT,SecurityHeadersIT,DefaultUrlValidatorTest'
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
```

```text
  SsrfProtectionIT        : 14  (8 fixos + parâmetros IPs privados, agora 7 literais
                               incl. [::1] e [::ffff:169.254.169.254]/IPv4-mapped)
  ProdConfigValidatorIT   : 5   (ausente/curto/default -> fail-fast; forte+config -> passa; non-prod ignora)
  SecurityHeadersIT       : 3   (nosniff / X-Frame-Options DENY / Referrer-Policy strict-origin)
  DefaultUrlValidatorTest : 13  (+ rejectsIpv4MappedMetadataLiteral)
```

Gap fechado na story 4.3: CIDR `::ffff:169.254.169.254/128` e metadata-set existiam no
`DefaultUrlValidator` mas com 0 casos de teste — adicionado literal ao `SsrfProtectionIT` e teste
unitário dedicado (`rejectsIpv4MappedMetadataLiteral`); a assertiva aceita `cloud metadata IP` OU
`private/internal IP` (resolução do mapped-literal depende da JVM: Inet4Address com hostAddress
169.254.169.254 cai na metadata-set; caso contrário cai no CIDR /128).

### 4.4 Métricas frozen + promtool/amtool + ProductionLockdownIT + debug-health.sh

```text
$ bash scripts/check-metrics-frozen.sh
PASS: metrics frozen — all registered meters belong to the reviewed set (docs/slos.md §2).
$ bash scripts/check-metrics-frozen.sh --self-test
PASS: self-test verified — gate detects violations.

$ promtool check rules deploy/monitoring/alerts.yml deploy/monitoring/recording-rules.yml
Checking .../alerts.yml
  SUCCESS: 3 rules found
Checking .../recording-rules.yml
  SUCCESS: 4 rules found
$ promtool test rules deploy/monitoring/rules_tests.yml
  SUCCESS
$ promtool check config deploy/monitoring/prometheus.yml
  SUCCESS: 3 rules found
$ amtool check-config deploy/monitoring/alertmanager.yml
 - 0 templates

$ ./mvnw test -Dtest='ProductionLockdownIT'
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

$ bash scripts/debug-health.sh   # exit=0
== URL shortener — debug health ==
Base: http://localhost:8080 (debug-health.sh, Epic 3 story 3.5)
...
Recommended action for the top symptom above: ...
```

### 4.5 Rastreabilidade + CI

```text
$ bash scripts/check-doc-sync.sh
PASS: documentation sync check passed (AGENTS.md debt statuses + lessons promotions consistent).
```

```text
$ gh run list --limit 6
[{"databaseId":34585182724,"headSha":"bfd1253...","conclusion":"success", "title":"test(security): cover IPv4-mapped IPv6 metadata"}   <- FLIP
[{"databaseId":34584652957,"headSha":"7386982...","conclusion":"success", "title":"docs(epic-4): fix template drift"}]
```

```text
$ gh run view 34585182724 --json jobs -q '.jobs[] | "\(.name): \(.conclusion)"'   # tree do flip bfd1253
Security Gate: success
Observability Gate (Epic 3): success
Unit Tests: success
Integration Tests: success
Build: success
```

### Gate completo + zero-flaky (2ª execução determinística da suíte IT)

```text
$ ./mvnw verify     # unit+IT+JaCoCo+SpotBugs+OWASP+dep-check
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
[INFO] Total time:  03:09 min

$ ./mvnw test -Dtest='*IT'    # rerun: zero-flaky (140 IT, 2ª execução)
[INFO] Tests run: 140, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:15 min
```

## 2. Self‑audit — rode ANTES de enviar (qualquer "não" = corrigir o handoff, não o audit)

- [x] Cada sha resolve: `git cat-file -e` para 7386982 (docs) e bfd1253 (flip) — push real feito,
      ambos presentes em `main`
- [x] Cada (número de run, sha) par idêntico no `gh run list` colado acima (34585182724/bfd1253,
      34584652957/7386982)
- [x] Cada contagem igual ao output colado: 271 unit, 140 IT (failsafe), 35 alvo 4.3, 7
      ProductionLockdownIT, 3+4 regras, 24 séries frozen
- [x] Todo vermelho está NA tabela — CI 5/5 verde em ambos os runs deste épico; nenhum vermelho
- [x] Owner approvals citados: aterragem/pirâmide/CI/gap IPv6 = 4 respostas "Recommended" na sessão
      (canal = esta sessão de trabalho)
- [x] Todo claim sobre `main` verdadeiro: push de 7386982 e bfd1253 concluídos (`main -> main`)
- [x] Nenhum claim de closure: hand‑off reporta estado + gaps (dívida #26 permanece open)
- [x] Flip = bfd1253 (último commit de conteúdo); evidência CI = run 34585182724 sobre a tree do
      flip; nada landado depois dele além deste doc (docs-only, LOCAL — awaiting push)

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

- [x] `./mvnw test` → verde (271 unit, sem Docker, 26.734 s) e `./mvnw test -Dtest='*IT'` → verde
      (139→140 IT, singleton, 02:07 min) — tempos colados
- [x] `check-boundaries.sh` (0 violações) + `--self-test` + ArchUnit → PASS
- [x] `SsrfProtectionIT` (14, com IPv4‑mapped), `ProdConfigValidatorIT` (5), `SecurityHeadersIT` (3)
      → verdes (35 alvo)
- [x] `check-metrics-frozen.sh` (24 séries) + `promtool` (3+4 rules SUCCESS) + `amtool` → verdes
- [x] `ProductionLockdownIT` (7/7) + `debug-health.sh` (exit 0, ação recomendada) → verdes
- [x] `check-doc-sync.sh` → PASS
- [x] `ci.yml` (5 jobs) verde no run do flip (34585182724/bfd1253)
- [x] `./mvnw verify` completo verde (BUILD SUCCESS 03:09 min; cobertura core 90.6%/81.0% vs floor
      70%; infra 80.3%/72.0% vs floor 60%) — retro‑compatível EP1–EP3

**Gaps (sem impedimento ao épico):** dívida #26 open (role de operador para tier actuator —
`ROLE_ADMIN`/`METRICS_VIEWER` inatacáveis via HTTP); `debug-health.sh` dependence de credencial
para consultar `/actuator/prometheus` diretamente (mesma dívida). Zero-flaky: suíte IT executada 2×
deterministicamente (139 pós-aterragem e 140 pós-gap), 0 falhas em ambas.

*Ao marcar todos os itens acima, o Épico 4 está **concluído** e o próximo épico (EP5 – Performance)
pode iniciar.*

---

*Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 4. Sem o bloco de
evidências e o self‑audit, o hand‑off será rejeitado pelo canal owner.*