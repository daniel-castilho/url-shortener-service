# Epic 2 – Tasks Técnicas

## 2.1 Implementar `logSafe(String)` em todo o `infra/`
- [ ] Localizar todos os `log.warn(` e `log.info(` em `src/main/java/ca/tyny/urlshortener/infra/` (fonte de log: `LoggerFactory` por classe — não existe `LoggingService` central).
- [ ] Adicionar chamada privada `logSafe(String s) { return s.replace('\n', '_').replace('\r', '_'); }` **em cada classe que loga valor client‑controlado** (sanitizer no sink — lição 20).
- [ ] Substituir cada argumento client‑controlado daquele `log` por `logSafe(arg)`.
- [ ] `DefaultUrlValidator`: logar **host + reason** em vez da URL completa (Rule 6: never log full destination URLs).
- [ ] Confirmar `grep -R logSafe src/main/java/ca/tyny/urlshortener/infra` → todos os sinks client‑controlled cobertos.
- [ ] Executar `./mvnw verify` → gate de log‑injection PASS.
- [ ] Colar output do `grep` e do `./mvnw verify` no handoff‑DOD.

## 2.2 Proteger contra SSRF no shorten
- [ ] A validação já existe (`DefaultUrlValidator`: HTTPS‑only, DNS resolve + blocklist RFC1918/loopback/link‑local/metadata, userinfo reject, `SsrfProtectionIT` 8 testes). Gap do épico: **cobertura de IP literal**.
- [ ] Estender `SsrfProtectionIT` com casos de IP literal: `127.0.0.1`, `10.0.0.1`, `172.16.0.1`, `192.168.0.1`, `169.254.169.254`, `[::1]`.
- [ ] Adicionar métrica `security.ssrf.blocked.total` (namespace do repo) via `MetricsPort` → `MicrometerMetricsAdapter`, exportada em `/actuator/prometheus`.
- [ ] Executar `./mvnw test -Dtest='SsrfProtectionIT'` → verde.
- [ ] Colar output do teste no handoff‑DOD.

## 2.3 ConfigValidator fail‑fast em perfil prod
- [ ] `ProdConfigValidator` já existe (`infra/config/`: null/blank/<32 chars/default → erro). Gap do épico: **IT que prova o fail‑fast**.
- [ ] Criar `ProdConfigValidatorIT`: profile `prod` + secret default/curto → boot falha com mensagem clara; secret ≥ 32 chars → passa.
- [ ] Executar `./mvnw test -Dtest='ProdConfigValidatorIT'` → verde.
- [ ] Colar output do teste no handoff‑DOD.

## 2.4 Adicionar headers de segurança HTTP globalmente
- [ ] Via **Spring Security nativo** (decisão do owner): `.headers()` no `SecurityConfig` — `contentTypeOptions`, `frameOptions deny`, `referrerPolicy strict-origin-when-cross-origin` (sem `OncePerRequestFilter` manual).
- [ ] Teste `SecurityHeadersIT`: RestAssured asserta os 3 headers em rotas representativas (redirect, API, actuator liveness).
- [ ] Executar `./mvnw test -Dtest='SecurityHeadersIT'` → verde.
- [ ] Colar output do teste e do `curl -I` (dev server) no handoff‑DOD.

## 2.5 Configurar gate OWASP Dependency‑Check
- [ ] Adicionar `org.owasp:dependency-check-maven` **12.2.2** (pin do 12.x — keyless na 13.x é broken, upstream #8715; dependabot PRs não recebem secrets; padrão dargent) ao `pom.xml` (aprovado Regra 9).
- [ ] `failBuildOnCVSS=7`; `suppressionFile=owasp-suppressions.xml` versionada **vazia** (cada entrada futura: rationale + review date); data dir `~/.m2/dependency-check-data` com cache no CI; `nvdApiDelay=6000`.
- [ ] Job CI com `NVD_API_KEY` passado via `-DnvdApiKey` **apenas quando o secret existe** (keyless = throttled‑but‑working); retry documentado (tool error falha o job — never silent‑pass).
- [ ] Executar `./mvnw dependency-check:check -DskipTests` → verde (sem CVE ≥ 7 conhecidas).
- [ ] Colar output do gate e do `dependency:tree` no handoff‑DOD.

## 2.6 Auto‑auditoria de segurança (checklist rápido)
- [ ] Criar `scripts/check-security.sh` (+ `--self-test`, padrão dos gates existentes) que verifica:
    - presença de `logSafe` nos sinks client‑controlled de `infra/`
    - os 3 headers de segurança declarados no `SecurityConfig`
    - validação do JWT secret no `ProdConfigValidator` (length + default)
    - ausência de IPs internos hardcoded fora de testes/bloqueio
- [ ] Confirmar saída “PASS” ou listar itens faltantes.
- [ ] Integrar como job GitHub Actions `security-check` que bloqueia merge se falhar.

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