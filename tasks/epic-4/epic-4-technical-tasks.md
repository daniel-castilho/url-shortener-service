# Epic 4 – Tasks Técnicas

**Aterragem:** comandos reais (`./mvnw`, não `mvn`); nomes reais de testes/scripts; sem referências
a `MetricsConfig.java`, 12 séries `dargent_*` nem workflow `test.yml` separado (AC do CI preenchido
pelo `ci.yml` existente).

## 4.1 Consolidar estratificação de testes (pirâmide) — `docs/testing-playbook.md` §1–2
- [x] Revisar a estrutura de `src/test/java` (mirrors dos pacotes de produção; nenhuma pasta
      `core/`/`slice/`/`it/` artificial — a estratificação é por convenção de sufixo; mover testes
      seria churn que quebraria ArchUnit/JaCoCo PACKAGE `core.*`).
- [x] Unitários `*Test` puros sem Spring/I/O (`core/model`, `core/service`, `core/idgeneration`,
      `core/validation`).
- [x] Slices `@WebMvcTest` (`UrlControllerTest`, `AuthControllerTest`,
      `UrlControllerRateLimitingTest`, `GlobalExceptionHandlerTest`).
- [x] Integração `*IT` com Testcontainers (MongoDB + Redis) singleton via `BaseIntegrationTest`, sem
      `@DirtiesContext`.
- [x] Executar `./mvnw test` → verde (271 unit, sem Docker, 26.734 s), timing colado no DoD.
- [x] Executar `./mvnw test -Dtest='*IT'` → verde (139 antes do gap → 140 final, 1 par de containers), timing colado no DoD.
- [x] Colar matriz JaCoCo por camada (core 90.6%/81.0%, infra 80.3%/72.0%) no DoD.

## 4.2 Gate de fronteiras (boundary gates)
- [x] Executar `bash scripts/check-boundaries.sh` e registrar saída → PASS (0 violações).
- [x] Executar `bash scripts/check-boundaries.sh --self-test` (planta uma violação temporária e
      asserta que a gate a detecta).
- [x] `ArchUnit` `BoundaryRulesTest` + `BoundaryRulesSelfTestTest` verdes (job Unit Tests do CI).
- [x] `mvnw spotless:check` (bound em `validate`) verde — sem formatação pendente.

## 4.3 SSRF, ConfigValidator e Security Headers (Story 4.3)
- [x] `SsrfProtectionIT` cobre IPs literais IPv4, loopback `127.*`, link‑local, ULA/fd00, IPv6
      literal `[::1]` (8 testes) — aterrado (o template dizia "SSRFIT (13)"; real = 8).
- [x] **Gap desta story (fechado):** teste de regressão para IPv4‑mapped IPv6
      (`[::ffff:169.254.169.254]` no `SsrfProtectionIT` paramétrico) e caso unitário
      `rejectsIpv4MappedMetadataLiteral` no `DefaultUrlValidatorTest` (CIDR existia, 0 testes).
- [x] `ProdConfigValidatorIT` (5/5): secret ausente / curto / default → fail‑fast; forte + config
      completa → passa; non‑prod ignora.
- [x] `SecurityHeadersIT` (3/3): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
      `Referrer-Policy: strict-origin` em rotas representativas.
- [x] Executar `./mvnw test -Dtest='SsrfProtectionIT,ProdConfigValidatorIT,SecurityHeadersIT,DefaultUrlValidatorTest'`
      → 35 verdes (14+5+3+13).
- [x] Colar output no DOD.

## 4.4 Validar métricas "frozen" e health checks (Story 4.4)
- [x] `scripts/check-metrics-frozen.sh` congela as **24 séries de negócio** (docs/slos.md §2) — não
      existe `MetricsConfig`/`dargent_*` neste repo (código real: `MicrometerMetricsAdapter`).
- [x] Executar `check-metrics-frozen.sh` + `--self-test` → PASS.
- [x] Executar `promtool check rules`, `promtool test rules`, `promtool check config` → verdes.
- [x] Executar `amtool check-config` → verde.
- [x] Executar `ProductionLockdownIT` → verde (7/7; liveness/readiness tiered, `show-details:
      when-authorized`).
- [x] Executar `bash scripts/debug-health.sh` → saída legível com ação recomendada (exit 0).
- [x] Colar outputs no DOD.

## 4.5 Rastreabilidade ao `AGENTS.md` e ao `handoff-dod.md` (Story 4.5)
- [x] `scripts/check-doc-sync.sh` PASS valida ponteiros (`AGENTS.md` status de dívida, `lessons ↔
      coding-standards`).
- [x] Rule zero garantida: evidências do DOD têm par `gh run list` + output colado (self-audit [x]).
- [x] Nenhuma hipótese sem etiqueta no DOD (gaps etiquetados, ex.: dívida #26).
- [x] Colar output do `check-doc-sync.sh` no DOD.

## 4.1 (continuação) Integrar no CI (GitHub Actions) — `ci.yml` existente
- [x] Workflow **`ci.yml`** (5 jobs) já roda unit + IT + gates — não duplicar como `test.yml`:
      - `Unit Tests`: unit + ArchUnit + `check-boundaries.sh` (+ self‑test) + `check-doc-sync.sh`
        (+ self‑test).
      - `Observability Gate (Epic 3)`: promtool 3.3.0 / amtool 0.28.1 pinned, check/test rules,
        check config, amtool check‑config, `check-metrics-frozen.sh` (+ self‑test).
      - `Integration Tests`: `*IT` com Testcontainers (failsafe).
      - `Security Gate`: `check-security.sh` (+ self‑test), OWASP Dependency‑Check (NVD mirror).
      - `Build`: jar.
- [x] Evidência CI no DOD: run 34585182724/bfd1253, 5/5 jobs success.
- [x] CI requerido: falha em qualquer job impede merge (workflow verde no flip).

## 4.6 Integração retro‑compatível com EP1–EP3
- [x] `./mvnw verify` conjunto → BUILD SUCCESS (03:09 min; unit 271 + IT 140).
- [x] Métrica `security_ssrf_blocked_total` coberta por `MetricsIT.playbackExportsEpic2BusinessSeries`
      (série frozen; assertiva da scrape inclui todas as séries EP2).
- [x] 24 séries "frozen" sem colisão — docs/slos.md §2 + `check-metrics-frozen.sh` PASS.
- [x] Colar trecho do `./mvnw verify` no DOD.

---

**Checklist de conclusão do Épico 4:**

- [x] Pirâmide de testes documentada (`testing-playbook.md` §1–2) e `./mvnw test`/`./mvnw verify`
      verdes
- [x] Gate de fronteiras (`check-boundaries.sh` + `--self-test` + ArchUnit) → PASS
- [x] `SsrfProtectionIT` (14, com IPv4‑mapped), `ProdConfigValidatorIT` (5), `SecurityHeadersIT` (3)
      → verdes
- [x] Métricas "frozen" (24 séries) + `promtool` + `amtool` → verdes
- [x] `ProductionLockdownIT` (7/7) + `debug-health.sh` → verdes
- [x] `check-doc-sync.sh` → PASS
- [x] `ci.yml` verde (5/5 jobs) no push do flip
- [x] Integração retro‑compatível com EP1–EP3 verde (`./mvnw verify`)
- [x] `./mvnw verify` completo verde (unit 271 + IT 140 + gates)

*Ao marcar todos os itens acima, o Épico 4 está **concluído** e o próximo épico (EP5 – Performance)
pode iniciar.*