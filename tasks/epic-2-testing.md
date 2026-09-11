# Epic 2 – Estratégia de Testes

## 2.1 Unit‑tests de log (logSafe)
- **Objetivo:** Confirmar que cada `log.warn`/`info` no `infra/` usa o helper `logSafe`.
- **Ação:** 
  - `grep -R "logSafe" src/main/java/infra` → deve cobrir 100 % dos logs client‑controlados.
  - `./mvnw test -Dtest='*LogSafeTest'` (teste unitário que injeta strings com `\n`/`\r` eAsserta que o log não os contém).
- **Critério aceite:** Testes verdes; cobertura de grep 100 %.

## 2.2 Teste de SSRF (SSRFIT)
- **Objetivo:** Validar que endpoints de shortening bloqueiam IPs internos.
- **Ação:** 
  - `./mvnw test -Dtest=SSRFIT` → verde.
  - Verificar saída do teste: código 400 e mensagem `invalid_request`.
- **Critério aceite:** Teste verde e saída colada no handoff‑DOD.

## 2.3 Teste de ConfigValidator (ConfigValidatorIT)
- **Objetivo:** Garantir que o perfil `prod` recusa secret curto/ Default.
- **Ação:** 
  - `./mvnw test -Dtest=ConfigValidatorIT` → verde.
  - Saída: IllegalStateException com mensagem “invalid JWT secret”.
- **Critério aceite:** Teste verde e saída colada.

## 2.4 Teste de Headers de Segurança (SecurityHeadersIT)
- **Objetivo:** Confirmar que o filtro global injeta os 3 headers HTTP.
- **Ação:** 
  - `./mvnw test -Dtest=SecurityHeadersIT` → verde.
  - Saída de `curl -I` colada (ex.: `X-Content-Type-Options: nosniff` etc.).
- **Critério aceite:** Teste verde + saída colada.

## 2.5 Teste de gate OWASP Dependency‑Check
- **Objetivo:** Validar que o build falha ao introduzir nova dependência vulnerável.
- **Ação:** 
  - Adicionar temporariamente um artefato de teste com vulnerabilidade conhecida (ex.: `junit:junit:2.13` com flag de vuln no pom).
  - Executar `./mvnw verify` → build falha com mensagem de OWASP.
  - Remover artefato e confirmar `./mvnw verify` verde.
- **Critério aceite:** Build falha com nova dep; build verde sem ela.

## 2.6 Integração no CI (GitHub Actions)
- **Objetivo:** Bloquear merge se algum checkpoint de segurança falhar.
- **Ação:** 
  - Adicionar/workflow `security.yml` que roda:
    - `./mvnw verify` (includes o gate OWASP).
    - `scripts/check-security.sh` (verifica logSafe, SSRF config, JWT secret, headers).
  - Falha em qualquer job → pull request não pode ser merged.
- **Critério aceite:** Pipeline vermelho bloqueia merge; pipeline verde permite merge.

## 2.7 Integração com Observability (EP3)
- **Objetivo:** Garantir que métricas de segurança sejam coletadas.
- **Ação:** 
  - Verificar que contadores como `security.ssrf.blocked.total` e `security.headers.applied.total` são incrementados nos testes.
  - Confirmar que as séries aparecem em `/actuator/prometheus`.
- **Critério aceite:** Métricas visíveis e testes verdes.

--- 

**Checklist de conclusão do Épico 2:**

- [ ] `logSafe` em 100 % dos logs `infra` (grep + CI green)
- [ ] `SSRFIT` → 400 para IPs internos
- [ ] `ConfigValidatorIT` → falha em prod com secret default
- [ ] `SecurityHeadersIT` → headers presentes em resposta `curl -I`
- [ ] `./mvnw verify` verde com `owasp-dependency-check` green
- [ ] `scripts/check-security.sh` → PASS
- [ ] `./mvnw verify` completo verde (unit + IT + gates)

*Ao marcar todos os itens acima, o Épico 2 está **concluído** e o próximo épico (EP3 – Observable) pode iniciar.*