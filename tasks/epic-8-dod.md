# Epic 8 – Definition of Done (DoD) [template de evidências]

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

Preencher durante a execução. Não inventar valores antes de rodar.

## 1. Evidências obrigatórias (outputs reais coladas)

### 8.1 Contrato de release + ADRs (executado YYYY-MM-DD)

$ git log --oneline -- docs/adr/ docs/release-engineering.mdCOLAR

- Arquivos: `docs/release-engineering.md`, `docs/adr/0007-blue-green-bare-metal.md`, `docs/adr/0008-artifact-promotion.md`.
- Contrato de cutover (colar do ADR 0007): _fail-closed: qualquer passo falha → cor antiga a 100%, exit ≠ 0 com o passo nomeado_.
- Consequência aceita (colar do ADR 0007): _frota pós-cutover = 1 ativa + 1 idle; capacidade 2× = instância extra fora do plano de deploy_.

### 8.2 Identidade do artifact (executado YYYY-MM-DD)

$ ./mvnw -q help:evaluate -Dexpression=project.version -Drevision=0.14.0 -DforceStdoutCOLAR

$ ./mvnw clean package -DskipTests -Drevision=0.14.0 && ls -l target/*.jarCOLAR (nome do jar)

$ ./mvnw verifyCOLAR (resumo: tests + "All coverage checks have been met" + BUILD SUCCESS — prova de que o flatten não quebrou gate)

Gate CHANGELOG — verde:COLAR (step da release.yml na tag real)
Gate CHANGELOG — vermelho (intencional, branch descartável com Unreleased sujo):COLAR

Contratos de HTTP lidos do código (não inventados):

- `ShortenResponse` (`infra/adapter/input/rest/dto/ShortenResponse.java`): `record ShortenResponse(String id, String shortUrl)`
- `POST /api/v1/urls` → **200** (`@ApiResponse(responseCode = "200")` em `UrlController`)
- `GET /{id}` → **302** válido / **404** desconhecido / **410** expirado (README "Current State" + `ReadPathIT`)
- `HEAD` espelha `GET` (fix EP7: `ReadPathIT#headMirrorsGetOnRedirectPath`)
- `X-Request-Id`: echoed na resposta + MDC (`RequestCorrelationFilter`)

### 8.3 Blue-green fail-closed (executado YYYY-MM-DD)

$ scripts/deploy.sh --self-testCOLAR (asserções de peso + prova do abort: porta morta, cor antiga a 100%, exit ≠ 0, passo nomeado)

$ scripts/deploy.sh --init && cat deploy/runtime/nginx.confCOLAR (blue 100 / green down)

Exercício local — pesos do runtime conf em cada bump (colar os trechos `server 127.0.0.1:808x ...`):

| Bump | blue weight | green weight | smoke | dwell |
|------|-------------|--------------|-------|-------|
| 10   |             |              |       | 30s   |
| 30   |             |              |       | 30s   |
| 100  |             |              |       | —     |

$ cat deploy/runtime/last-deploy.txtCOLAR (previous/current/tag/at)

Zero-downtime do cutover (loop de redirect durante os flips; seed do EP7 ou da smoke):

$ for i in $(seq 1 300); do curl -s -o /dev/null -w '%{http_code}\n' http://localhost:<porta-front>/<seed>; sleep 0.1; done | sort | uniq -cCOLAR (esperado: só 302; nenhum 5xx/connection-refused)

### 8.4 Smoke + rollback (executado YYYY-MM-DD)

$ scripts/smoke.sh http://localhost:<porta-front>COLAR (8 pernas: leg 1/8 … leg 8/8 + "SMOKE PASS")

$ scripts/rollback.sh --self-testCOLAR (render da cor anterior asserido)

Exercício local (deploy fake → rollback):

$ scripts/rollback.shCOLAR (one-liner de incidente: `rollback <current>→<previous> reverted to <previous> at <iso> (deploy <TAG> cut over, panicked)`)
$ cat deploy/runtime/nginx.confCOLAR (anterior 100 / atual down)

Smoke contra porta morta (perna nomeada no exit):

$ scripts/smoke.sh http://localhost:<porta-morta>; echo "exit=$?"COLAR

CI `runtime-smoke` (job da `release.yml`, run do primeiro tag):COLAR (smoke + `verify-graceful-shutdown.sh` — zero connection-refused)

### 8.5 Backup agendado e verificado (executado YYYY-MM-DD)

$ scripts/backup-mongodb.sh /tmp/epic8-backupCOLAR (path + tamanho)
$ cat /tmp/epic8-backup/*/manifest.jsonCOLAR (row counts reais por collection)

$ scripts/restore-mongodb.sh --verify <dir>COLAR (tabela de comparação verde)

Negative test (manifest corrompido → exit ≠ 0):

$ sed -i 's/"short_urls": <n>/"short_urls": <n-1>/' <dir>/manifest.json && scripts/restore-mongodb.sh --verify <dir>; echo "exit=$?"COLAR (tabela de divergência + exit ≠ 0)

$ scripts/ci-restore-drill.shCOLAR (seeds pré=302 / pós=404; RTO medido vs `RTO_BUDGET_S=300`)

RTO medido do drill: _colar_ (wall-clock backup→restore→verify)

Host de homologação (evidence de host, não de CI):

$ sudo systemctl list-timers url-shortener-backup.timerCOLAR
$ ls -lt /var/backups/url-shortener | head -3COLAR

### 8.6 Release como gate (executado YYYY-MM-DD)

Tag: `vX.Y.Z` — workflow `release.yml` run #___ (link colado) — jobs: gates / k6-gate / runtime-smoke / restore-drill / release → todos `success`.

$ gh release view vX.Y.Z --json name,assets -q '.assets[].name'COLAR (assets: jar, SHA256SUMS, SBOM)
$ gh release download vX.Y.Z --pattern '*.jar' -D /tmp/epic8-release && sha256sum /tmp/epic8-release/*.jarCOLAR

**O mesmo sha256 que o deploy verifica:**

$ scripts/deploy.sh vX.Y.ZCOLAR (linha do sha256 verificado no download + one-liner final + `--active` = cor nova)

k6-gate no artifact (thresholds dos scripts: `p95 < 200ms`, `http_req_failed < 0.1%`):COLAR (summary do k6 + exit 0)

Trivy HIGH/CRITICAL + non-root gate (job release):COLAR ("OK: image runs non-root" + table limpo/exit 0)

Runbook: seções atualizadas (colar o TOC novo):

$ grep -E '^#{1,3} ' docs/release-runbook.mdCOLAR

Fechamento de docs:

$ git log --oneline -1 -- AGENTS.md README.mdCOLAR (item EP8 na matriz de debt + linha Deployable no Current State)
$ ./scripts/check-doc-sync.shCOLAR (PASS)

### 8.7 Gates finais (executado YYYY-MM-DD)

```
$ ./scripts/check-metrics-frozen.sh  && ./scripts/check-metrics-frozen.sh --self-test
$ ./scripts/check-boundaries.sh && ./scripts/check-boundaries.sh --self-test
$ ./scripts/check-doc-sync.sh && ./scripts/check-doc-sync.sh --self-test
$ ./scripts/check-security.sh && ./scripts/check-security.sh --self-test
$ promtool check rules ... && promtool test rules rules_tests.yml && amtool check-config alertmanager.yml
$ scripts/deploy.sh --self-test && scripts/rollback.sh --self-test && scripts/smoke.sh <base>
$ ./mvnw verify
```
COLAR (todos PASS/verde + BUILD SUCCESS)

Sha de fechamento do Épico: `______` (gates 8.7); commits do épico: `______` (8.2), `______` (8.3/8.4), `______` (8.5), `______` (8.6). CI verde no push final (jobs Unit, Integration, Build, Security Gate, Observability Gate — todos `success`).

## 2. Checklist de conclusão

- [ ] `docs/release-engineering.md` + ADR 0007 + ADR 0008
- [ ] Versioning `revision` + flatten + gate CHANGELOG (red/green)
- [ ] `deploy.sh` blue-green fail-closed + units blue/green + self-test + exercício local
- [ ] `smoke.sh` (8 pernas, 2 consumidores) + `rollback.sh` + self-test
- [ ] Timer de backup + manifest + restore `--verify` (negative test) + `ci-restore-drill.sh` com RTO
- [ ] `release.yml` verde no primeiro tag + Release com assets (jar do build da CI)
- [ ] Runbook atualizado (deploy/rollback/checklist/post-deploy/incidente)
- [ ] `./mvnw verify` verde
- [ ] Nenhuma cifra neste arquivo sem comando acima

## 3. Fora de escopo confirmado (não vira dívida fantasma)

- K8s / orquestrador / multi-host — não feito; bare metal é a plataforma (ADR 0007).
- Replica set / Redis Sentinel — não feito; SPOF aceito no EP7.
- Backup off-host — não feito; TD nomeado (alvo de RPO inalterado: último backup).
- Canary auto-verificado por métricas (gate automático entre bumps) — não feito; canary = smoke + vigilância manual nomeada (Grafana/burn-rate).
- Deploy automático via SSH da CI — rejeitado no ADR 0008 (gate humano no bare metal).
- Rotação de chave JWT (dívida EP2) — não resolvida aqui.

---

*Quando a seção 2 estiver 100% marcada e a seção 1 tiver outputs reais, o Épico 8 está **concluído**.*
