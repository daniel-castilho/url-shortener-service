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
- [ ] Executar `./mvnw test` → verde (270 unit, sem Docker), timing colado no DoD.
- [ ] Executar `./mvnw test -Dtest='*IT'` → verde (139 IT, 1 par de containers), timing colado no DoD.
- [ ] Colar matriz JaCoCo por camada (report real: `core.*` ≥ 70%, BUNDLE ≥ 60%) no DoD.

## 4.2 Gate de fronteiras (boundary gates)
- [x] Executar `bash scripts/check-boundaries.sh` e registrar saída → PASS (0 violações).
- [x] Executar `bash scripts/check-boundaries.sh --self-test` (planta uma violação temporária e
      asserta que a gate a detecta).
- [x] `ArchUnit` `BoundaryRulesTest` + `BoundaryRulesSelfTestTest` verdes (job Unit Tests do CI).
- [x] `mvnw spotless:check` (bound em `validate`) verde — sem formatação pendente.

## 4.3 SSRF, ConfigValidator e Security Headers (Story 4.3)
- [x] `SsrfProtectionIT` cobre IPs literais IPv4, loopback `127.*`, link‑local, ULA/fd00, IPv6
      literal `[::1]` (8 testes) — aterrado (o template dizia "SSRFIT (13)"; real = 8).
- [ ] **Gap desta story:** adicionar teste de regressão para IPv4‑mapped IPv6
      (`https://[::ffff:169.254.169.254]/`) no `SsrfProtectionIT` e caso unitário no
      `DefaultUrlValidatorTest` (o CIDR `::ffff:169.254.169.254/128` já está no validator mas
      com 0 casos de teste).
- [x] `ProdConfigValidatorIT` (5/5): secret ausente / curto / default → fail‑fast; forte + config
      completa → passa; non‑prod ignora.
- [x] `SecurityHeadersIT` (3/3): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
      `Referrer-Policy: strict-origin` em rotas representativas.
- [ ] Executar `./mvnw test -Dtest='SsrfProtectionIT,ProdConfigValidatorIT,SecurityHeadersIT'`
      → todos verdes (9+5+3 com o novo teste).
- [ ] Colar output no handoff‑DOD.

## 4.4 Validar métricas "frozen" e health checks (Story 4.4)
- [x] `scripts/check-metrics-frozen.sh` congela as **24 séries de negócio** (docs/slos.md §2) — não
      existe `MetricsConfig`/`dargent_*` neste repo (código real: `MicrometerMetricsAdapter`).
- [ ] Executar `check-metrics-frozen.sh` + `--self-test` → PASS.
- [ ] Executar `promtool check rules`, `promtool test rules`, `promtool check config` → verdes.
- [ ] Executar `amtool check-config` → verde.
- [ ] Executar `ProductionLockdownIT` → verde (7/7; liveness/readiness tiered, `show-details:
      when-authorized`).
- [ ] Executar `bash scripts/debug-health.sh` → saída legível com ação recomendada.
- [ ] Colar outputs no handoff‑DOD.

## 4.5 Rastreabilidade ao `AGENTS.md` e ao `handoff-dod.md` (Story 4.5)
- [x] `scripts/check-doc-sync.sh` PASS valida ponteiros (`AGENTS.md` status de dívida, `lessons ↔
      coding-standards`).
- [ ] Garantir que todo número, sha ou contagem no `epic-4-dod.md` tenha par correspondente em
      `gh run list` e output de comando colado (Rule zero).
- [ ] Ausência de hipótese sem etiquetar.
- [ ] Colar output do `check-doc-sync.sh` no handoff‑DOD.

## 4.1 (continuação) Integrar no CI (GitHub Actions) — `ci.yml` existente
- [x] Workflow **`ci.yml`** (5 jobs) já roda unit + IT + gates — não duplicar como `test.yml`:
      - `Unit Tests`: unit + ArchUnit + `check-boundaries.sh` (+ self‑test) + `check-doc-sync.sh`
        (+ self‑test).
      - `Observability Gate (Epic 3)`: promtool 3.3.0 / amtool 0.28.1 pinned, check/test rules,
        check config, amtool check‑config, `check-metrics-frozen.sh` (+ self‑test).
      - `Integration Tests`: `*IT` com Testcontainers (failsafe).
      - `Security Gate`: `check-security.sh` (+ self‑test), OWASP Dependency‑Check (NVD mirror).
      - `Build`: jar.
- [ ] Evidência: `gh run list` + conclusões dos jobs do flip (run sobre a tree do flip), colado no DoD.
- [ ] Falha em qualquer job → PR/merge bloqueado (CI requerido).

## 4.6 Integração retro‑compatível com EP1–EP3
- [ ] `./mvnw verify` conjunto → verde (unit + IT + JaCoCo + SpotBugs + OWASP + gates).
- [ ] Métrica `security_ssrf_blocked_total` (série frozen EP2/EP3) incrementada — coberta por
      `MetricsIT.playbackExportsEpic2BusinessSeries`.
- [ ] 24 séries "frozen" sem colisão com séries de EP2 — congeladas em docs/slos.md §2.
- [ ] Colar trecho do `./mvnw verify` no handoff‑DOD.

---

**Checklist de conclusão do Épico 4:**

- [ ] Pirâmide de testes documentada (`testing-playbook.md` §1–2) e `./mvnw test`/`./mvnw verify`
      verdes
- [ ] Gate de fronteiras (`check-boundaries.sh` + `--self-test` + ArchUnit) → PASS
- [ ] `SsrfProtectionIT` (9, com IPv4‑mapped), `ProdConfigValidatorIT` (5), `SecurityHeadersIT` (3)
      → verdes
- [ ] Métricas "frozen" (24 séries) + `promtool` + `amtool` → verdes
- [ ] `ProductionLockdownIT` + `debug-health.sh` → verdes
- [ ] `check-doc-sync.sh` → PASS
- [ ] `ci.yml` verde (job por job) no push do flip
- [ ] Integração retro‑compatível com EP1–EP3 verde (`./mvnw verify`)
- [ ] `./mvnw verify` completo verde (unit + IT + gates)

*Ao marcar todos os itens acima, o Épico 4 está **concluído** e o próximo épico (EP5 – Performance)
pode iniciar.*