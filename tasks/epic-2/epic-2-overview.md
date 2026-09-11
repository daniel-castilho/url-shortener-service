# Epic 2: Secure – Segurança by Design

**Projeto:** url-shortener-service  
**Contexto:** Java 25, Spring Boot 4.1.1, Arquitetura Hexagonal, MongoDB, Redis  
**Objetivo:** Garantir que a aplicação não aceite entradas maliciosas, não exponha segredos e esteja livre de vetores de ataque conhecidos (SSRF, injeção de log, headers inseguros, dependências vulneráveis). Todas as correções devem ser evidenciadas com commands colados e testes de CI verdes.

---

## Por que este épico em segundo lugar?

- A manutenibilidade (EP1) fornece a base de pacotes e convenções de logging que o EP2 depende.
- Segurança mal projetada força refatoração laterante em épicos seguintes (Performance, Observability, Deployable).
- As regras de `AGENTS.md` (especialmente a regra 2 sobre shas e counts) só podem ser validadas se o código estiver estável e as métricas (EP3) já estiverem estruturadas.

**Relação com outros épicos:**

| Epico | Dependência direta |
|--------|-------------------|
| EP3 – Observable | Novos logs estruturados e métricas de segurança (ex.: `security.ssrf.blocked.total`). |
| EP4 – Testes | Stories de teste de segurança (injeção de log, SSRF, headers). |
| EP5 – Performance | Garantir que as mitigacões de segurança não degradem a latência p95. |
| EP6 – Scalable | Garantir que as validações de entrada não se tornem gargalo ao escalar. |
| EP7 – Reliable | Circuit‑breaker e bulkhead names alinhados com as exceções de segurança. |
| EP8 – Deployable | Imagens Docker com rótulos de segurança e gate OWASP integrados. |

**Critério de Aceitação Elevado:**

1. `./mvnw verify` → **verde** com todos os gates de segurança (SpotBugs, OWASP Dependency‑Check, CodeQL).
2. `check‑boundaries.sh` → **PASS** (imports `infra.*` ainda proibidos em `core/`).
3. `logSafe(String)` presente em todos os `log.warn`/`info` do `infra/` (grep colado).
4. Teste `SSRFIT` → 400 para IPs internos (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.169.254`).
5. `ConfigValidator` fail‑fast em perfil `prod`; warning se `APP_JWT_SECRET` for o default (teste `ConfigValidatorIT`).
6. Headers HTTP de segurança presentes em `curl -I https://sistema/` (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`).
7. `./mvnw dependency:tree` não traz novas dependências com CVSS ≥ 7 sem aprovação humana (gate CI bloqueia).

**Rastreabilidade rápida:**

| Story | Doc referência | AGENTS.md |
|-------|----------------|-----------|
| 2.1 | `logSafe` presente em `infra` (grep colado) | Regra 2 (ID Generation Standard) + Regra 6 (Security & Secrets) |
| 2.2 | `SSRFIT` → 400 para IPs internos | Regra 6 (Security & Secrets) |
| 2.3 | `ConfigValidatorIT` → falha em prod com secret default | Regra 6 |
| 2.4 | Headers HTTP colados de `curl -I` | Regra 6 |
| 2.4 | Gate `owasp-dependency-check` verde | Regra 9 (No Unapproved Dependencies) |

--- 

*Próximo passo: criar as stories detalhadas (2.1‑2.5) e as tasks técnicas correspondentes.*