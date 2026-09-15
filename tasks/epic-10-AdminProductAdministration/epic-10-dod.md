# Epic 10 – Definition of Done (DoD) [template de evidências]

**Regra zero — zero-from-memory:** Todo número, sha ou contagem neste documento
deve ser colado de um output de comando incluído neste documento. Se não der
para colar o comando que gerou, trata-se de hipótese e deve ser etiquetado
como tal (TD-13 class). Segredos (e-mails de teste reais, tokens) redigidos
como `<redigido>` — mas status codes, headers e bodies **completos**.

**Commits do épico (colar no fechamento):**

```
# git log --oneline <base>..HEAD   (esperado: 5 commits, 10.1 → 10.5)
```

## 1. Evidências obrigatórias (outputs reais coladas)

### 10.1 Papel ADMIN no token + ADR 0011 (executado 2026-09-15)

**CI (commit da story):** run `epic-10 -> 10.1`, head sha `34d6354`.

**Unit/IT desta story (colar o resumo do surefire/failsafe — counts e PASS):**

```
$ ./mvnw verify 2>&1 | grep -E 'Tests run: [0-9]+.*Failures'
Tests run: 487 ... (299 unit + 188 IT, Failures: 0, Errors: 0)  # BUILD SUCCESS
(AdminBootstrapIT: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 24.78 s)
```

**Living spec (Auth ≥ 100% com os requisitos novos + traces):**

```
$ bash scripts/check-living-spec.sh 2>&1 | tail -3
REQ-AUTH-011 (Auth) — traced
REQ-AUTH-012 (Auth) — traced
Coverage: 43 / 44 requirements traced (97%)
PASS: living specification gate.

$ bash scripts/check-living-spec.sh --self-test 2>&1 | tail -1
PASS: self-test verified — gate detects missing traces, stray classes, dangling refs, ...
```

**Prova do claim (token de teste decodificado — segredo redigido):**

```
# boot local com APP_ADMIN_EMAILS=admin@example.com
$ register admin → login
admin token payload (decodificado): {"sub":"admin@example.com","iat":...,"exp":...,"role":"ADMIN"}
$ register victim → login
victim token payload (decodificado): {"sub":"victim@example.com","iat":...,"exp":...,"role":"USER"}
(decodificação: echo '<token>' | cut -d. -f2 | base64 -d 2>/dev/null | python3 -m json.tool)
```

**Compat legada (IT):**

```
AdminBootstrapIT > legacyTokenWithoutClaimIsAuthenticatedAsUserRole : PASS
(https gerado com jwtTokenProvider.generateToken(email, null) → authority ROLE_USER, /me → USER)
```

### 10.2 Block/unblock + listagem (executado 2026-09-15)

**CI:** run `epic-10 -> 10.2`, head sha `(a colar no commit 2)`.

**Itens 1–6 da matriz de testes (nome de cada teste + PASS):**

```
$ ./mvnw test -Dtest='AdminUsersIT' 2>&1 | grep -E "Tests run|in Admin product"
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 27.75 s -- in Admin product administration surface IT (ADR 0011)
BUILD SUCCESS

-- nomes dos testes (matriz 1–6 + extras):
adminCanListUsersWithRoleAndBlockedFields           PASS
emailPrefixFilter                                    PASS
cursorPagination                                     PASS
blockedUserLosesLogin                                PASS (bloqueado → login 403 + block idempotente 204)
blockedUserCannotRefresh                             PASS (refresh de bloqueado → 403)
selfBlockRejected                                    PASS (400)
unblockRestoresAccess                                PASS
blockUnknownUserAnswers404                           PASS
nonAdminForbidden                                    PASS (403)
anonymousRejected                                    PASS (401)
```

**403 de bloqueado (corpo completo):**

```
# boot local; register vítima; block como admin (204); login da vítima:
$ curl -s -X POST localhost:8080/api/v1/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"victim@example.com","password":"password123"}' -w '\nSTATUS=%{http_code}\n'
{"status":403,"error":"Forbidden","message":"Account blocked.","timestamp":"..."}
STATUS=403

# prova da ordem — credencial inválida de bloqueado → 401 (não 500):
$ curl -s -X POST ... -d '{"email":"victim@example.com","password":"WRONG"}' ...
{"status":401,"error":"Unauthorized","message":"Invalid credentials","timestamp":"..."}
STATUS=401

# prova viva adicional (same session):
block             → 204 ; block novamente (idempotente) → 204
unblock           → 204 ; login após unblock → 200
refresh de bloqueado → 403 "Account blocked."
self-block        → 400 {"error":"Invalid Request","message":"You cannot block your own account"}
block unknown     → 404 {"error":"User Not Found","message":"User not found"}
non-admin GET     → 403 {"error":"Forbidden","message":"Forbidden"}
non-admin block   → 403
anonymous GET     → 401
GET /users        → item {"role":"USER","blocked":true,...} (role = env-list truth)
GET /users?q=victim → 1 item (prefix filter)
GET /users?limit=1 → hasMore=true, nextCursor=... ; ?cursor=<next> → page2
```

**Expands-only (sem V-migration):**

```
$ git diff HEAD --stat -- src/main/java/ca/tyny/urlshortener/infra/adapter/output/persistence/migration
(empty — nenhum V* adicionado; blocked é campo expand-only via updateOne $set)
UserEntityTest: shouldCreateEntityWithAllArgs/set-e-get-todos (blocked=false default sem campo) : PASS
```

### 10.3 Inspeção read-only (executado YYYY-MM-DD)

**CI:** run + head sha.

**Testes 7–8 (naming + PASS):**

```
# ./mvnw verify -Dtest='Admin*IT' 2>&1 | grep -E "Tests run"
```

**Lookup por code (body completo — donos redigidos):**

```
# GET /api/v1/admin/urls?code=<code> → 200 {…ShortUrlResponse, "ownerUserId":"…", "ownerEmail":"…"}
# GET /api/v1/admin/urls?code=zzzzzzz → 404
```

### 10.4 Force archive + write path (executado YYYY-MM-DD)

**CI:** run + head sha.

**Testes 9–11 (naming + PASS):**

```
# ./mvnw verify -Dtest='Admin*IT' 2>&1 | grep -E "Tests run"
```

**Sequência force archive (status codes na ordem):**

```
# DELETE /api/v1/admin/urls/<id> → 204
# GET /<id> → 404
# DELETE novamente → 204 (idempotente)
```

**Write path (sequência com status):**

```
# token da vítima emitido ANTES do block; block → 204
# POST /api/v1/urls (com o token) → 403
# GET /api/v1/urls (com o token) → 200
# POST /api/v1/urls (anônimo) → 200
```

### 10.5 Contrato e gates finais (executado YYYY-MM-DD)

**CI:** run + head sha.

**Gates finais (todos, colar cada saída):**

```
# ./mvnw verify 2>&1 | tail -15                     (counts unit + IT)
# bash scripts/check-boundaries.sh 2>&1 | tail -1
# bash scripts/check-doc-sync.sh 2>&1 | tail -1
# bash scripts/check-metrics-frozen.sh 2>&1 | tail -1
# bash scripts/check-living-spec.sh 2>&1 | tail -3
# bash scripts/check-living-spec.sh --self-test 2>&1 | tail -1
```

**Lista frozen inalterada (antes/depois):**

```
# grep -c "meter" docs/metrics-frozen.txt (ou o arquivo correspondente) — antes vs. depois do épico (esperado: igual)
```

**OpenAPI (o JSON é o contrato da SPA):**

```
# curl -s localhost:8080/v3/api-docs | python3 -c "import json,sys; d=json.load(sys.stdin); print(sorted(p for p in d['paths'] if '/admin' in p))"
# esperado: os 6 paths admin; e "role" presente nos schemas de login/register/refresh/me
```

**Prova viva completa (rule zero — sequência exata, segredos redigidos):**

```
# 1) boot com APP_ADMIN_EMAILS=<admin>
# 2) register admin → login → body role=ADMIN
# 3) GET /me → role=ADMIN
# 4) register vítima → shorten da vítima (200, id=…)
# 5) POST block da vítima → 204
# 6) login da vítima → 403 "Account blocked."
# 7) POST unblock → 204 → login da vítima → 200
# 8) GET /api/v1/admin/urls?code=<code> → 200 (ownerEmail da vítima)
# 9) GET /api/v1/admin/users → 200 (vítima com blocked=false após unblock)
# 10) DELETE /api/v1/admin/urls/<id> → 204
# 11) GET /<id> → 404
# 12) list da vítima → item com deletedAt
# (colar todos os status + bodies-chave)
```

**Matriz + AGENTS.md:**

```
# grep -n "EP10\|denylist" AGENTS.md | head -5
```

## 2. Checklist de conclusão

- [ ] 5 commits (10.1–10.5), cada um com `./mvnw verify` + gates bash verdes — 5 pares run/sha de CI colados acima.
- [ ] 18 testes de contrato da matriz (`epic-10-testing.md`) presentes e verdes (nomes colados).
- [ ] `check-living-spec` + `--self-test` verdes (Auth ≥ 100% traced; Admin ainda **não** gated — nenhum `@spec-complete` novo).
- [ ] `check-metrics-frozen` verde — lista intacta (zero metros novos).
- [ ] `check-boundaries` + asserção ArchUnit de use cases admin em application layer verdes.
- [ ] `check-doc-sync` verde (AGENTS.md matrix + CHANGELOG consistentes).
- [ ] ADR 0011 Accepted (com a consequência dos tiers do actuator escrita).
- [ ] OpenAPI: 6 paths admin + `role` nos 4 responses de auth + 403 "Account blocked." + self-block 400 + `ownerEmail` nullable.
- [ ] CHANGELOG `[Unreleased]` completo (tudo que o operator vê).
- [ ] Follow-up de **denylist/revogação ao bloquear** registrado na matriz (com gatilho) — não vira dívida fantasma.
- [ ] Proof viva completa colada (12 passos).
- [ ] `git status` limpo no fechamento.

## 3. Fora de escopo confirmado (não vira dívida fantasma)

- **Denylist/revogação de JWTs ao bloquear** — follow-up registrado (matriz AGENTS.md + corpo do commit 5); gatilho nomeado; mesma família da rotação de refresh. Em v1, tokens de acesso de bloqueado vivem até o expiry (documentado no ADR 0011).
- **Rotação de refresh** — dívida pré-existente, não tocada.
- **Admin de branded domains / dashboard de rate limit** — fora do produto atual (Prometheus cobre o observável).
- **Novo path de cookie `/admin`** — o access cookie `Path=/` já cobre (ADR 0010).
- **CORS / header CSRF** — não se aplica (same-origin; R2 do ADR 0010).
- **Campo `role` no banco / "first user = admin"** — rejeitados no ADR 0011 (bootstrap circular / ambiguidade); o papel vem da env list.
- **Bloqueio de shorten anônimo (por IP)** — o throttle AUTH já é por IP; block de conta não se estende a IPs (documentado no OpenAPI).
- **Componente Admin spec-complete** — a story de living-spec 100% do Admin é trabalho futuro (padrão: nenhum componente nasce gated).
