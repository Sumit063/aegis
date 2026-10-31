# CI/CD, Environments and Branching

## 1. Environments

| Environment | Profile | Branch | Where | Ports (HTTP / management) | Gateway keys |
|---|---|---|---|---|---|
| Local | `local` | any | Developer machine, Docker (Colima or Docker Desktop) | 8080 / 8081 | sandbox |
| Dev | `dev` | `dev` | Ubuntu host, compose project `aegis-dev` | 8280 / 8281 | sandbox |
| Staging | `staging` | `staging` | Ubuntu host, compose project `aegis-staging` | 8180 / 8181 | sandbox |
| Production | `prod` | `master` | Ubuntu host, compose project `aegis-prod` | 8080 / 8081 | **live** |

Each server environment is fully isolated: its own PostgreSQL, Kafka, volumes, `.env` with its own
secrets, and its own runtime configuration. They share only the host. All ports bind to
`127.0.0.1`; the host is on a private Tailscale network and public webhook ingress is added
explicitly (Funnel) per environment in M1. Moving an environment to its own host later changes
nothing but where its runner lives.

## 2. Branching

```
 feature/F04-domain-model ──PR──► dev ──PR──► staging ──PR──► master
        ▲  cut from master          │            │              │
        │                           ▼            ▼              ▼
        │                       deploy dev   deploy staging  deploy prod
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
    3. image (push)   ghcr.io/<owner>/aegis:<sha> and :<branch>
  CD  (self-hosted runner on the host, after CI succeeds on a push)
    4. deploy         branch → environment; pull :<sha>; compose up; wait for readiness
    5. rollback       unhealthy → previous tag of that environment; job fails
```

Manual deploys and rollbacks: run the CD workflow by hand with an environment and a SHA.

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
  aegis/dev/        compose.yaml, .env (dev secrets, mode 600)
  aegis/staging/    compose.yaml, .env (staging secrets)
  aegis/prod/       compose.yaml, .env (production secrets)
  backups/          nightly PostgreSQL dumps per environment (M1)
 container aegis-runner   GitHub Actions runner (restarts automatically)
```

Adding a secret for an environment: edit that environment's `.env` on the host, then redeploy
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
| Toolchain | build | Maven Enforcer: Java 25, Maven ≥ 3.9 |
| Dependency updates | weekly | Dependabot (Maven, Actions, Docker) |

## 7. Commit messages

```
<type>(<optional scope>): <summary in imperative mood>
types: feat fix docs test refactor perf build ci chore revert
example: feat(ingress): verify Razorpay webhook signatures
```
