# Features

Aegis is built **one feature at a time**. Each feature gets a short design note in this folder
(copied from [TEMPLATE.md](TEMPLATE.md)) that is reviewed **before** code is written, then
implemented, tested and merged on its own.

## Workflow

1. **Design** — write `docs/features/<ID>-<slug>.md` from the template (skill: `solution-design`).
2. **Review** — the design note is approved (status `approved`).
3. **Implement** — inside-out: domain → application → adapters, tests alongside, build verified
   after every change (skill: `implement-feature`).
4. **Review code** — against the design and the checklist (skill: `code-review`).
5. **Merge and promote** — branch `feature/<ID>-<slug>` from `master` → PR into `dev` → PR
   `dev → staging` (deploys staging) → PR `staging → master`. Status → `done` when it reaches
   `master`.

## Backlog

| ID | Feature | Milestone | Depends on | Status |
|---|---|---|---|---|
| F00 | Repository scaffold, toolchain, CI/CD | — | — | done |
| F01 | Lab: Razorpay-compatible mock provider | M0 | F00 | planned |
| F02 | Lab: reference application with flaw switches F1–F10 | M0 | F01 | planned |
| F03 | Lab: attack scripts and results runner | M0 | F02 | planned |
| F04 | Domain: messages, validations, outcomes, verdicts, check chain | M1 | F00 | planned |
| F05 | Persistence: Flyway schema (`validations`, `payments`, `config`), evidence encryption | M1 | F04 | planned |
| F06 | Configuration: connections, applications, secrets, fail-closed validation | M1 | F00 | planned |
| F07 | Provider SPI, registry, contract test suite | M1 | F04 | planned |
| F08 | Razorpay adapter | M1 | F07 | planned |
| F09 | Ingress: transport, signature, Kafka, acknowledgement | M1 | F06, F08 | planned |
| F10 | Checker: inbound checks and verdicts | M1 | F05, F09 | planned |
| F11 | Browser callback endpoint and signed redirect | M1 | F10 | planned |
| F12 | Gateway API: validations, payment requests, status checks | M1 | F05, F08 | planned |
| F13 | Forwarder: mTLS, Aegis signature, optional JWE, retries, ordering | M1 | F10 | planned |
| F14 | Admin API and runtime settings | M1 | F05 | planned |
| F15 | Observability: metrics, alerts, dashboards, log masking | M1 | F09–F13 | planned |
| F16 | Lab run through Aegis; M1 acceptance (T1–T19) | M1 | F03, F09–F15 | planned |

M2–M4 features are added here when M1 is done.
