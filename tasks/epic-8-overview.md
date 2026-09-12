# Epic 8: Deployable – Release Engineering e Deploy Sem Downtime

**Projeto:** url-shortener-service  
**Contexto:** Java 25, Spring Boot 4.1.1, Arquitetura Hexagonal, MongoDB 6.0 (single-node), Redis (single-node + AOF + Stream), Tomcat 11 (virtual threads), on-prem bare metal + nginx + systemd  
**Objetivo:** Tornar o caminho "código validado → produção" **previsível, automatizado e provável**: uma tag de release só existe como artefato se passar em todos os gates; o deploy em bare metal é blue-green com canary e cutover **fail-closed**; o rollback é um comando; o backup é agendado, manifestado e verificado por restore. Este épico **não** muda a plataforma (sem k8s/orquestrador), **não** resolve o SPOF single-node (aceito no EP7) e **não** faz deploy automático por SSH (gate humano é decisão registrada).

---

## Estado do repo (pré-existente, não é novo trabalho)

O repo **já possui os artefatos** — o Épico 8 os **automatiza e contrata**, não reescreve:

- **Docker** multi-stage (non-root, `HEALTHCHECK`, JVM flags container-aware) + `docker-compose.yaml` (mongo+redis com healthchecks e volume).
- **Systemd:** `deploy/url-shortener.service` + template `deploy/url-shortener@.service` (instância N → porta `8080+(N-1)`), com hardening (`NoNewPrivileges`, `ProtectSystem=strict`, `KillMode=mixed`, SIGTERM, `TimeoutStopSec=30`).
- **Proxy:** nginx (`deploy/proxy/nginx.conf`) com upstream weighted + `max_fails=2 fail_timeout=10s`; Caddy (auto-HTTPS) como alternativa; TLS + HSTS nos dois (EP2).
- **Runbook** (`docs/release-runbook.md`): deploy manual (§1), rollback manual (§2), rotação de segredos (§3), incidentes (§5 + playbooks validados no drill do EP7 em §5b), checklist pré-release (§7), TLS (§8), SLOs (§9), baseline (§10), retenção (§11) e **scale-out + canary manual** (§12: flip de pesos 10→30→100 editando o upstream à mão).
- **EP7 (Reliable) já entregou** o que o canary precisa: readiness honesta (liveness ≠ readiness provado com `docker stop` real), graceful shutdown de 30s verificado, playbooks de incidente e DR drill com números colados.
- **EP6 (Escalável):** instâncias stateless, rate limit global em Redis (ADR 0002), L1 por instância (ADR 0003), scale-out 2 instâncias + LB validado sob 2× carga.
- **Backup/restore:** `scripts/backup-mongodb.sh` (mongodump + `metadata.json` + rotação de 30d) e `scripts/restore-mongodb.sh` — hoje **executados pelo operador**, sem timer no repo, sem manifest com row counts, sem restore verificado como contrato.
- **CI** (`.github/workflows/ci.yml`): unit-tests (+ boundary gate + doc-sync), observability (promtool/amtool + metrics frozen), integration-tests (Testcontainers + OWASP no verify), security-check, build (jar). Load test k6 em `workflow_dispatch` (`load-test.yml`).
- **SLOs, burn-rate, dashboards e métricas frozen** (EP3): são os "olhos" do canary.
- **Tags existem** (`v0.13.0` atual) — mas o pom está em `0.0.1-SNAPSHOT`, a tag não dispara nada no CI, e o jar validado pela pipeline **não é promovido a lugar nenhum** (sem GitHub Release, sem asset, sem digest, sem SBOM).

**O que falta de verdade:**

1. **Identidade de release:** o artifact não carrega a semver da tag (jar e imagem saem como `0.0.1-SNAPSHOT`/sem label). Não há caminho de promoção do jar "validado" — quem faz deploy recompila na mão e o bit-for-bit com o que a CI validou é fé.
2. **Deploy blue-green automatizado:** o canary existe no papel (runbook §12.2) mas exige editar nginx à mão + restart com janela de downtime da instância ativa. Sem cutover fail-closed, sem wait de readiness com budget nomeado, sem registro de `last-deploy`.
3. **Smoke de runtime:** nada prova que o stack **está servindo de ponta a ponta** (contrato de negócio: shorten → 302 → 404 → 410) após um deploy. Healthcheck do Dockerfile não basta — ele não exercita o caminho crítico.
4. **Rollback de comando único:** hoje é re-executar o runbook §2 de memória. Sem `last-deploy.txt`, sem one-liner de incidente.
5. **Backup automatizado e verificado:** sem timer no repo (systemd `Persistent=true`), sem manifest com row counts por collection, sem restore que **falha** em divergência, sem restore drill como gate de release.
6. **Workflow de release:** tag `v*` não dispara gates, não roda k6 no artifact, não gera Release com assets (jar + sha256 + SBOM) e notas.

## Por que este épico agora?

- **EP6 (Escalável)** validou a topologia stateless multi-instância + LB — blue-green só faz sentido sobre ela.
- **EP7 (Reliable)** entregou readiness honesta, dreno de 30s e playbooks de incidente — a própria overview do EP7 registrou: *"EP8 (Deployable) precisa de readiness correta, shutdown drenado e runbook de incidente — senão blue-green/canary vira corte"*. Este épico honra essa dependência.
- **EP3 (Observable)** dá os "olhos" do canary: burn-rate, dashboard de SLO, métricas frozen para vigiar durante o flip de peso.
- **EP5 (Performance)** + k6: o gate de release pode **enforçar** p95 < 200ms / erro < 0.1% no artifact antes que a tag seja oficial.
- Sem o EP8, tudo que EP1–EP7 entregou só chega à produção "na mão, de memória, à mercê do operador" — o pilar Deployable é o que fecha o laço.

**Fora de escopo (não fazer neste épico):**

- K8s / orquestrador / multi-host / multi-AZ (plataforma é on-prem bare metal — contexto do ADR 0001).
- Replica set Mongo, Redis Sentinel/Cluster (SPOF aceito e documentado no EP7).
- Terraform/Ansible/Pulumi (provisioning do host é do operador; este épico entrega units + scripts + runbook, não provisiona).
- Backup off-host (rsync/rclone para segundo disco/host) — responsabilidade do operador; alvo de RPO inalterado (último backup).
- Deploy automático via SSH a partir da CI (gate humano no bare metal é decisão registrada no ADR 0008; a CI produz o artifact e o operador executa `deploy.sh`).
- Canary auto-verificado por métricas (gate automático de p95/burn entre bumps) — o canary de hoje = smoke após cada bump + vigilância manual nomeada no Grafana; vira TD se o tráfego exigir.
- Rotação de chave JWT (dívida do EP2) — não tocada aqui.

**Critério de Aceitação (aterrado):**

1. `docs/release-engineering.md` (novo) + ADR 0007 (blue-green em bare metal, runtime conf renderizada, cutover fail-closed) + ADR 0008 (promoção de artifact: o jar construído pela CI é o que é implantado, nunca rebuild local) — todo o fluxo escrito.
2. **Identidade:** jar e imagem carregam a semver da tag (padrão `revision` + flatten); tag `v*` dispara `release.yml` com: gate de CHANGELOG (`[Unreleased]` vazio), `./mvnw verify` completo (todos os gates Maven + bash + promtool/amtool), **gate k6 com os thresholds de SLO no artifact**, **runtime-smoke** de ponta a ponta, **restore drill com RTO medido** (≤ budget) e criação do GitHub Release (jar do build da CI + sha256 + SBOM CycloneDX + notas).
3. `scripts/deploy.sh <tag>`: blue-green zero-downtime (blue :8080 / green :8081, unidades concretas com jar por cor), canary 10/30/100 com dwell 30s e **smoke após cada bump**, wait de readiness com budget nomeado, `last-deploy.txt`, **abort fail-closed** (qualquer falha → cor antiga a 100%, nova cor drenada e parada, exit não-zero com o passo ofensor nomeado). Modos `--check` / `--init` / `--active` / `--self-test`.
4. `scripts/smoke.sh`: uma sonda, pernas de contrato de negócio (liveness, readiness, info, shorten `200` + `X-Request-Id`, redirect `302` + `Location`, desconhecido `404`, expirado `410`, `HEAD` espelha `GET` — fix do EP7), **dois consumidores** (deploy.sh e CI).
5. `scripts/rollback.sh`: lê `last-deploy.txt` → cor anterior volta a 100% **sem rebuild** + one-liner de incidente; `--self-test`.
6. **Backup:** `deploy/systemd/url-shortener-backup.service` + `.timer` (`Persistent=true`, fora da janela do retention purge 02:00 UTC e do rollup 01:10 UTC); manifest com row counts por collection em `backup-mongodb.sh`; `restore-mongodb.sh --verify` re-conta e **sai não-zero em qualquer divergência**; `scripts/ci-restore-drill.sh` (projeto compose isolado, padrão EP5/6/7) roda como **gate de release** com RTO medido.
7. `docs/release-runbook.md`: fluxo novo (§1 deploy via `deploy.sh`, §2 rollback via `scripts/rollback.sh`, checklist pré-release acrescido de "migrations desde o último deploy são expand-only" + "backup com < 26h" + "drill verde na release", seção de post-deploy verification e incidente "deploy falhou").
8. `./mvnw verify` verde com todos os gates + self-test dos novos scripts verdes (`--self-test` no padrão do repo: gate que morde).
9. **Rule zero — zero-from-memory:** todo número, sha ou contagem é colado de output real.

**Rastreabilidade rápida:**

| Story | Doc referência | Aspecto chave |
|-------|----------------|---------------|
| 8.1 | `docs/release-engineering.md` + ADR 0007/0008 | Contrato de release escrito |
| 8.2 | pom (`revision`) + Dockerfile + gate CHANGELOG | Identidade do artifact |
| 8.3 | `scripts/deploy.sh` + units blue/green | Blue-green fail-closed |
| 8.4 | `scripts/smoke.sh` + `scripts/rollback.sh` | Prova de runtime + rollback de linha |
| 8.5 | systemd timer + manifest + `ci-restore-drill.sh` | Backup agendado e verificado |
| 8.6 | `.github/workflows/release.yml` + runbook | Release como gate |

---

*Próximo passo: executar as stories 8.1–8.6 (`epic-8-technical-tasks.md`) e colar evidências no `epic-8-dod.md`.*
