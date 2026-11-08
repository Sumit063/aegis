# CI/CD, Environments and Branching

## 1. Environments

| Environment | Profile | Branch | Where | Ports (HTTP / management) | Gateway keys |
|---|---|---|---|---|---|
| Local / dev | `local` (laptop) · `dev` | `feature/*`, `dev` | Developer Mac, Docker via Colima | 8080 / 8081 | sandbox |
| **Staging** | `staging` | `staging` | **Ubuntu host**, compose project `aegis-staging`, automatic deploy | 8180 / 8181 | sandbox |
| Production | `prod` | `master` | No host yet. CI builds and publishes the image; the CD job and the GitHub `production` environment are added when a host exists | 8080 / 8081 | **live** |

GitHub shows only environments that have a machine: today that is `staging`.

The staging host is isolated per environment by design (own PostgreSQL, Kafka, volumes, `.env`
secrets, runtime configuration), so adding production later — on the same host or a new one — is
one `deploy.sh prod <sha>` and one line in the CD workflow. All ports bind to `127.0.0.1`; the host
is on a private Tailscale network and public webhook ingress is added explicitly (Funnel).

## 2. Branching

```
 feature/F04-domain-model ──PR──► dev ──PR──► staging ──PR──► master
        ▲  cut from master          │            │              │
        │                           ▼            ▼              ▼
        │                       CI only     deploy staging  image ready for prod
 hotfix/<slug> ──PR──► master, then merged back into staging and dev
```

| Branch | Purpose | Merges in from | Protected |
|---|---|---|---|
| `master` | Production; always releasable | `staging`, `hotfix/*` | yes |
| `staging` | Pre-production verification | `dev`, back-merges from `master` | yes |
| `dev` | Integration of finished features | `feature/*`, back-merges | yes |
| `feature/<ID>-<slug>` | One backlog feature | cut from `master` | no |
| `hotfix/<slug>` | Urgent production fix | cut from `master` | no |

Promoting whole branches (`dev → staging → master`) instead of merging each feature into every
branch keeps the environment branches identical in content, just at different points in time.

## 3. Pipeline

```
 commit ─► local hooks: format, secrets, commit message ─► push ─► pre-push: ./mvnw verify
                                                                     │
 GitHub ─────────────────────────────────────────────────────────────▼──────────────────────
  CI  (GitHub-hosted, every push to master/staging/dev and every pull request)
    1. commit-lint    Conventional Commits in the pushed range
    2. build          ./mvnw verify: format, compile, unit, ArchUnit, integration, coverage
    2b. lab           lab/run.sh: every exploit in Docker (EXPLOIT-LAB)
    3. image (push)   ghcr.io/<owner>/aegis:<sha> and :<branch>
  CD  (self-hosted runner on the host, after CI succeeds on a push)
    4. deploy         staging → Ubuntu host; pull :<sha>; compose up; wait for readiness
    5. rollback       unhealthy → previous tag of that environment; job fails
    6. check          deploy/check.sh; report on the run's summary page; job fails on any ✗
  Restore rehearsal  (self-hosted, weekly and on demand)
    7. rehearse       deploy/rehearse-restore.sh staging: newest dump → throwaway copy → evidence opens
```

Manual deploys and rollbacks: run the CD workflow by hand with an environment and a SHA.

### Validating after a merge

| After merging into | Wait for | Then run | Passes when |
|---|---|---|---|
| a feature PR | the PR's CI checks | — | build and test green |
| `dev` | CI on `dev` | `scripts/check.sh` (with the service running locally) | your machine runs the merged code |
| `staging` | CD (a few minutes after CI) | `scripts/check.sh staging` | the server runs exactly the tip of `origin/staging` |

`scripts/check.sh` prints one line per check and exits non-zero on any failure:

```
Aegis · staging
  ✓ postgres: running healthy        containers up, health checks passing
  ✓ kafka: running healthy
  ✓ aegis: running
  ✓ alive                            the process responds
  ✓ ready (database reachable)       it can serve traffic
  ✓ profile: staging                 started with the right environment's settings
  ✓ version: 4eb09ee… (expected)     the image built from the merged commit
  ✓ roles: ingress,checker,…         which parts of Aegis run in this process
✓ staging is healthy
```

A `version … expected …` failure right after a merge usually means CD is still running; the
first line of the output links the last CD run. The same report is on that run's summary page.

## 4. Why this shape

| Decision | Reason | Rejected |
|---|---|---|
| CI on GitHub-hosted runners | Clean machines; Docker available for Testcontainers | Building on the production host |
| Images in GitHub Container Registry | Same account as the code; `GITHUB_TOKEN` can push and pull | Docker Hub (rate limits, extra credentials) |
| **CD via a self-hosted runner on the host** | The runner connects **outbound** to GitHub; no inbound port, no SSH or Tailscale key stored in GitHub | SSH from GitHub; Tailscale GitHub Action |
| Immutable tags (`<git sha>`) | Every deploy traceable and reversible | `latest` |
| Health-gated deploy with automatic rollback | A bad image never stays live | Manual checks |
| Secrets only on the host, per environment | Production secrets never enter GitHub; environments can't read each other's | GitHub secrets for runtime credentials |

The self-hosted runner only runs the deploy job for pushes to environment branches — never
pull-request code — and the repository is private, so forks cannot reach it.

## 5. Host layout

```
/home/aegis/
  aegis/staging/    compose.yaml, .env (staging secrets, mode 600), aegis.yaml (connections, applications),
                    backups/ (daily pg_dump, newest 14, written by the backup container)
  aegis/prod/       (when production moves here or to its own host)
 container aegis-runner   GitHub Actions runner (restarts automatically)
```

Secrets Aegis itself needs (database password, `AEGIS_EVIDENCE_KEY`, `AEGIS_ADMIN_API_KEY`) are generated by `deploy.sh`
the first time they are missing. Adding a secret for an environment: edit that environment's `.env` on the host, then redeploy
(run CD manually with the current SHA). Runtime-tunable settings are changed through the Admin API
instead (LLD §11.2).

## 6. Quality gates

| Gate | Where | Tool |
|---|---|---|
| Formatting | pre-commit, CI | Spotless (Palantir Java Format) |
| Secrets in diffs | pre-commit | pattern check (`.githooks/pre-commit`) |
| Commit message | commit-msg, CI | Conventional Commits (`scripts/check-commit-msg.sh`) |
| Full build before push | pre-push | `./mvnw verify` |
| Architecture rules | build | ArchUnit |
| Profile safety | build | `ProfilesTest`: no default credentials outside `local` |
| Coverage | build | JaCoCo ≥ 80 % lines (domain, application) |
| Exploits | CI job `Exploit Lab` | `lab/run.sh`: F1–F10 exploited directly, blocked with the flaw off, blocked through Aegis |
| Toolchain | build | Maven Enforcer: Java 25, Maven ≥ 3.9 |
| Dependency updates | weekly | Dependabot (Maven, Actions, Docker) |

## 7. Commit messages

```
<type>(<optional scope>): <summary in imperative mood>
types: feat fix docs test refactor perf build ci chore revert
example: feat(ingress): verify Razorpay webhook signatures
```
