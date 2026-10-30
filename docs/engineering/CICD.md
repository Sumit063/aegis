# CI/CD and Deployment

## 1. Where Aegis runs

| Environment | Where | How it is reached |
|---|---|---|
| **Local** | Developer machine, Docker (Colima or Docker Desktop) | `localhost` |
| **Production (current)** | One Ubuntu host on a private Tailscale network, as the unprivileged `aegis` user, Docker Compose | Tailnet only; provider webhooks via Tailscale Funnel (HTTPS) when enabled |
| **Later** | Multiple hosts or Kubernetes | Same image, same configuration model — see HLD §12 |

The host is **never opened to inbound connections from GitHub**. Deployment is pulled, not pushed.

## 2. Pipeline

```
 developer ──commit──► local hooks: format, commit message ──push──► local hook: full build
                                                                         │
 GitHub ─────────────────────────────────────────────────────────────────▼──────────────
  CI (GitHub-hosted runner, every push and PR)
    1. commit-lint      Conventional Commit messages in the pushed range
    2. build            ./mvnw verify: format check, compile, unit tests, ArchUnit,
                        integration tests (Testcontainers), coverage gate
    3. image (main)     build container image, push ghcr.io/<owner>/aegis:<sha> and :main
  CD (self-hosted runner on the production host, main only, after CI succeeds)
    4. deploy           pull :<sha>, docker compose up -d, wait for /actuator/health
    5. rollback         if unhealthy: redeploy the previous image tag, fail the job
```

## 3. Why this shape

| Decision | Reason | Rejected |
|---|---|---|
| CI on GitHub-hosted runners | Clean, disposable machines; Docker available for Testcontainers | Building on the production host (slow, pollutes it) |
| Images in GitHub Container Registry | Same account as the code; `GITHUB_TOKEN` can push; no extra service | Docker Hub (rate limits, separate credentials) |
| **CD via a self-hosted runner on the host** | The runner connects **outbound** to GitHub; no inbound port, no SSH key or Tailscale key stored in GitHub. Zero-trust friendly. | SSH from GitHub (needs inbound access + stored key); Tailscale GitHub Action (stores a tailnet auth key in GitHub) |
| Immutable tags (`<git sha>`) | Every deploy is traceable and reversible | `latest` (ambiguous, no rollback) |
| Health-gated deploy with automatic rollback | A bad image never stays live | Manual verification |
| Secrets only on the host (`deploy/.env`, mode 600) | Production secrets never enter GitHub | GitHub secrets for runtime credentials |

Security note: the self-hosted runner is used **only** for the deploy job on `main`, never for
pull-request code, and the repository is private. Forks cannot trigger it.

## 4. Runtime layout on the host

```
/home/aegis/
  actions-runner/            GitHub Actions runner (container, restarts automatically)
  aegis/deploy/              compose.yaml, deploy.sh (updated by each deploy)
  aegis/deploy/.env          secrets and image tag (created once, never committed)
  backups/                   nightly PostgreSQL dumps (M1)
```

Containers: `postgres` (17), `kafka` (4.x, KRaft single node), `aegis` (all roles).
Ports bind to `127.0.0.1` only; public webhook ingress is added explicitly via Funnel in M1.

## 5. Quality gates

| Gate | Where | Tool |
|---|---|---|
| Formatting | pre-commit hook, CI | Spotless (Palantir Java Format) |
| Commit message | commit-msg hook, CI | Conventional Commits (`scripts/check-commit-msg.sh`) |
| Full build before push | pre-push hook | `./mvnw verify` |
| Architecture rules | build | ArchUnit |
| Coverage | build | JaCoCo ≥ 80 % lines (domain, application) |
| Toolchain | build | Maven Enforcer: Java 25, Maven ≥ 3.9 |
| Dependency updates | weekly | Dependabot (Maven, Actions, Docker) |

## 6. Commit messages

```
<type>(<optional scope>): <summary in imperative mood>

types: feat fix docs test refactor perf build ci chore revert
example: feat(ingress): verify Razorpay webhook signatures
```

## 7. Branching

Trunk-based: short-lived branches → pull request → CI green → squash merge to `main` → automatic
deploy. Recommended branch protection on `main`: require CI, require linear history.
