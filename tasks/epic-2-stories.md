# Epic 2 – Stories (Aceitação)

| # | Story | Critérios de Aceitação | Referência Ágil / Âncora |
|---|-------|------------------------|--------------------------|
| **2.1** | **Sanitização em sinks – logSafe** – implementar `logSafe(String)` em todos os `log.warn`/info do `infra/`, cortando `\n`/`\r` (lição 20 do `lessons.md`). | • `grep -R logSafe src/main/java/infra` → 100 % coberto <br>• CI `log-injection` gate PASS <br>• Nenhum log de teste contém `\n` ou `\r` de client‑controlado | Regra 20 do `lessons.md` (injeção de log) |
| **2.2** | **Proteção contra SSRF** – o endpoint de shortening rejeita destinos com IP interno/privado/link‑local; HTTPS‑only + hostname seguro; retornar 400. | • `SsrfProtectionIT` estendida → 400 para IPs literais (`127.0.0.1`, `10.0.0.1`, `172.16.0.1`, `192.168.0.1`, `169.254.169.254`, `[::1]`) <br>• Métrica `security.ssrf.blocked.total` incrementada e visível em `/actuator/prometheus` <br>• Documentação atualizada com exemplos de IPs bloqueados | Regra 6 do `AGENTS.md` (Security & Secrets) |
| **2.3** | **ConfigValidator fail‑fast** – em perfil `prod` o boot aborta se `APP_JWT_SECRET` for o valor padrão ou tiver menos de 32 caracteres (regra vigente do `ProdConfigValidator`). | • `ProdConfigValidatorIT` → boot falha com secret default/curto, passa com secret forte <br>• `APP_JWT_SECRET` definido via env‑var <br>• Documentação do `AGENTS.md` atualizada | Regra 6 do `AGENTS.md` (Security & Secrets) |
| **2.4** | **Headers de segurança HTTP** – via Spring Security nativo (`.headers()` no `SecurityConfig`): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin` em todas as respostas. | • Teste `SecurityHeadersIT` → headers presentes em rotas representativas <br>• Nenhum header duplicado ou sobrescrito | Regra 6 do `AGENTS.md` (Security & Secrets) |
| **2.5** | **Gate OWASP Dependency‑Check** – `dependency-check-maven` **12.2.2** (pin 12.x; keyless 13.x broken — upstream #8715) bloqueia build se CVSS ≥ 7 for introduzida; suppression file versionada vazia (entries futuras com rationale + review date); NVD data cached no CI; key via `-DnvdApiKey` só quando o secret existe. | • `./mvnw dependency-check:check` → falha se novo artefato vulnerável <br>• Job CI `security-check`/dep‑check verde <br>• `./mvnw dependency:tree` listado na PR para revisão manual | Regra 9 do `AGENTS.md` (No Unapproved Dependencies) |

---

**Rastreabilidade rápida:**

| Story | Doc referência | AGENTS.md |
|-------|----------------|-----------|
| 2.1 | `logSafe` presente em `infra` (grep colado) | Regra 2 + Regra 6 |
| 2.2 | `SSRFIT` → 400 para IPs internos | Regra 6 |
| 2.3 | `ConfigValidatorIT` → falha em prod com secret default | Regra 6 |
| 2.4 | Headers HTTP colados de `curl -I` | Regra 6 |
| 2.5 | Gate `owasp-dependency-check` verde | Regra 9 |

--- 

*Próximo passo: criar as tasks técnicas (2.1‑2.5) e a estratégia de teste (epic‑2‑testing.md).*