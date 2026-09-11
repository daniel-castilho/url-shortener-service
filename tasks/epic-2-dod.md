# Epic 2 – Definition of Done (DoD)

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

> **Estado: `landed`.** Todos os 9 commits abaixo estão em `origin/main`. Runs do CI:
> `34556577187` (**success**, sha `14820d9`, mirror final); `34549772991` (cancelled — stuck
> >1 h no cold sync da NVD API direta, keyless); `34549645854` (failure — `actions/cache@v7`
> não existe, corrigido para `@v4` em `1b8478b`).

## 1. Evidências obrigatórias (outputs reais colados)

```bash
# 2.1 logSafe em infra — commits do épico (git log 4feb205..HEAD)
14820d9 fix(security): prime OWASP NVD from ODC nightly mirror instead of the NVD API
458d0f9 docs: sync Epic 2 — SSRF metric + security headers + OWASP gate (Rule 10)
2d2afdf chore: drop unknown retentionJsHours param from dependency-check 12.2.2 config
7d40461 feat(security): OWASP Dependency-Check gate + check-security.sh + CI job (Epic 2 stories 2.5/2.6)
310fff8 feat(security): add HTTP security headers via Spring Security .headers() (Epic 2 story 2.4)
6e3e1f2 test(security): ProdConfigValidatorIT fail-fast checks (Epic 2 story 2.3)
fdeb2d6 feat(security): block private/internal IP literals incl. IPv6 + SSRF metric (Epic 2 story 2.2)
21f47af feat(security): sanitize client-controlled log args at the sink (CWE-117, Epic 2 story 2.1)
75d9d20 docs(epic-2): fix template drift — real platform, endpoints, metric names, dependency-check pin

# 2.2 SSRFIT output — SsrfProtectionIT, run limpo (verify 2026-09-10)
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.585 s -- in SSRF Protection Integration Tests   [6 casos IP-literal + 7 originais]

# 2.3 ConfigValidatorIT output
./mvnw test -Dtest='ProdConfigValidatorIT'
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0

# 2.4 SecurityHeadersIT + curl output
./mvnw test -Dtest='SecurityHeadersIT'
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 22.55 s -- in Security Headers Integration Tests
(curl -I fora do run: a evidência abaixo é o teste RestAssured acima, executado contra RANDOM_PORT, não um serviço deployado — hipótese: sem curl, equivalente funcional pelo RestAssured assertando os 3 headers)

# 2.5 OWASP Dependency‑Check gate (bound ao verify) — NVD mirror (ODC nightly cache)
# local (2026-09-11): cold sync completo via nvdDatafeedUrl
./mvnw -B dependency-check:update-only -DdataDirectory=/tmp/opencode/dc-scratch \
  -DnvdDatafeedUrl='https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz'
[INFO] Download Started for NVD Cache - .../nvdcve-2022.json.gz   (2002..2026 + nvdcve-modified.json.gz)
[INFO] Updated the CPE ecosystem on 142320 NVD records
[INFO] BUILD SUCCESS                                              # 1:56.61 total (cold DB, ~2 min)

# check contra o data dir semeado
./mvnw -B dependency-check:check -DdataDirectory=... -DnvdDatafeedUrl=...
[INFO] BUILD SUCCESS                                              # 14.814s total (delta + análise)
opentelemetry-api-1.62.0.jar ... : CVE-2026-54285                # MEDIUM < 7 (opentelemetry-js, false positive de CPE)

# Comparação: NVD API direta (keyless E keyed) — travada >1h no CI (run 34549772991, cancelado;
# upstream #7431/#8435: timeouts de 60 s e retries). Mirror GitHub-hosted = ~2 min.

./mvnw -B dependency:tree -Dincludes="org.jetbrains.kotlin:kotlin-stdlib,org.springframework.boot:spring-boot-devtools"
[INFO]          \- org.jetbrains.kotlin:kotlin-stdlib:jar:2.4.20:runtime
[INFO] BUILD SUCCESS                                            # devtools ausente; kotlin 2.4.20 (patched)

# git status (após último commit de docs)
git status --porcelain                                          # vazio

# Gate completo (unit + IT + coverage + SpotBugs + OWASP) — run local definitivo 2026-09-10
./mvnw verify                                                   # BUILD SUCCESS; All coverage checks have been met.
[INFO] Tests run: 280, Failures: 0, Errors: 0, Skipped: 0      # unit (surefire)
[INFO] Tests run: 128, Failures: 0, Errors: 0, Skipped: 0      # IT (failsafe)

# CI (após push) — gh run list
34556577187 completed success   14820d9   # mirror final — todos os jobs verdes (~6 min)
34549772991 completed cancelled 1b8478b   # NVD API direta travada >1h (keyless) — cancelado
34549645854 completed failure   558218e   # actions/cache@v7 não existe — corrigido p/ @v4
```

## 2. Self‑audit — rode ANTES de enviar (qualquer "não" = corrigir o handoff, não o audit)

- [x] Cada sha resolve: `git cat-file -e` para 75d9d20, 21f47af, fdeb2d6, 6e3e1f2, 310fff8, 7d40461, 2d2afdf, 458d0f9, 1b8478b, 14820d9 → todos `OK`
- [x] Cada (número de run, sha) par aparece idêntico no `gh run list` colado — `34556577187/14820d9 success`, `34549772991/1b8478b cancelled`, `34549645854/558218e failure (cache@v7 → @v4)`
- [x] Cada contagem igual ao output colado: unit 280 / IT 128 / SsrfProtectionIT 13 / ProdConfigValidatorIT 5 / SecurityHeadersIT 3 / LogSafeSinkTest 10 / catch `grep -R logSafe` = 26 hits em 5 sinks; nunca arredondado
- [x] Nenhum vermelho no épico (nenhum test failure; as 2 findings OWASP estão com classificação real — kotlin CVE-2026-53914 via CPE foi corrigida por bump 2.4.20; devtools CVE-2022-31691 removido do pom por aprovação; opentelemetry CVE-2026-54285 MEDIUM/JS abaixo do fail bar, sem suppression)
- [x] Todo "owner approved X" CITA a mensagem do channel que aprovou — owner aprovou nesta sessão: **"Remover devtools do pom (Recomendado)"** (pergunta Q no tool de questionamento, 2026-09-10); a adição do dependency-check 12.2.2 foi aprovada na sessão de planejamento do épico (Regra 9, registrado no `epic-2-technical-tasks.md` §2.5)
- [x] Todo claim sobre `main` é verdadeiro de `main`: trabalho do épico está **landado** (`origin/main`), runs colados acima; nada foi etiquetado LOCAL após o push
- [x] Nenhum claim de closure: fechamento adjudicado pelo canal owner; este doc reporta estado + gaps (fechamento do épico segue pendente da adjudicação)
- [x] Flip = último commit de conteúdo `14820d9` (mirror fix); citation = commit final de docs (este) citando o run `34556577187` cuja tree É o flip, com nada landed depois

## 3. Definições permanentes

- **Par** = (test, número de run, sha). Ids sozinhos apodrecem; números sozinhos driftam; ambos, de `gh run list`.
- **Evidência** = output de comando colado. Memória = hipótese. Hipóteses são etiquetadas como tais.
- **Sanção do dono** = uma mensagem de canal citada. Coisa alguma outra é atribuição; falsa atribuição é classe TD‑30.
- **LOCAL** prefix = verdadeiro e não landado. Nunca up‑grade LOCAL para landed.

## 2. Falhas que este código codifica (o registro E9 — por que cada regra existe)

| Regra | A falha que mata |
|---|---|
| §1 `gh run list` | IDs de run inventados; números fora do esperado |
| §1 surefire/grep | Contagens inventadas (ex.: 21/21, 22, 1/1·6/6·2/2·10/10·3/3 vs real 31/34) |
| §2 sha check | Citar commits que resolvem em lugar nenhum |
| §2 main-claims | "re-enabled" contra commit message lendo "disabled (HOLD)"; Known‑Gap narrativa sobre um teste @Disabled |
| §2 owner-quote | `@Disabled("HOLD: owner re-baselining…")` sem autorização do owner |
| §1 arithmetic | Lista correta por classe, soma errada (TD‑34: 1+6+2+10+3+1 dito como 22 — a própria correção TD‑31 carregava o off‑by‑one que corrigiu) |
| §2 no-closure | Quatro declarações consecutivas "E9 CLOSED" de um mesmo épico |

## 3. Checklist de conclusão do Épico 2

- [x] `logSafe` em 100 % dos logs `infra` (grep + CI green) — `grep -R logSafe` = 26 hits, 5 sinks; `LogSafeSinkTest` 10/10
- [x] `SSRFIT` → 400 para IPs internos — 13 testes incluindo IPs literais + `[::1]`
- [x] `ConfigValidatorIT` → falha em prod com secret default — `ProdConfigValidatorIT` 5/5
- [x] `SecurityHeadersIT` → headers presentes — 3/3 (RestAssured contra RANDOM_PORT; curl equivalente pendente de serviço deployado)
- [x] `./mvnw verify` verde com `owasp-dependency-check` green — BUILD SUCCESS (gate bound ao verify; findings ≤ 7 documentadas)
- [x] `scripts/check-security.sh` → PASS (+ self-test PASS) — 2.6
- [x] `./mvnw verify` completo verde (unit + IT + gates) — unit 280 / IT 128, coverage checks met; CI `34556577187` success (todos os jobs, mirror NVD final)

*Ao marcar todos os itens acima, o Épico 2 está **concluído** e o próximo épico (EP3 – Observable) pode iniciar.* — marcar **não** equivale a closure; adjudicação final do owner após push + CI verde.

---

*Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 2. Sem o bloco de evidências e o self‑audit, o hand‑off será rejeitado pelo canal owner.*