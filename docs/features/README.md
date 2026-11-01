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

Built in this order; each row is one branch and one PR.

| ID | Feature | Milestone | Depends on | Status |
|---|---|---|---|---|
| F00 | Repository scaffold, toolchain, CI/CD, layered package structure | — | — | done |
| F04 | Domain: messages, validations, payments, verdicts, checks, `Gate` | M1 | F00 | done |
| F01 | Lab: Razorpay-compatible mock gateway | M0 | F00 | done |
| F02 | Lab: reference shop with flaw switches F1–F10 | M0 | F01 | done |
| F03 | Lab: attack scripts, `lab/run.sh`, results, CI job | M0 | F02 | done |
| F06 | Configuration: connections, applications, secrets, fail-closed validation | M1 | F00 | done |
| F07 | Provider SPI, registry, contract test suite | M1 | F04, F06 | done |
| F08 | Razorpay adapter | M1 | F07 | done |
| F05 | Persistence: Flyway schema, entities, repositories, evidence encryption | M1 | F04, F06 | done |
| F09 | Ingress: signature, Kafka, acknowledgement | M1 | F05, F08 | done |
| F10 | Checker: inbound checks and verdicts | M1 | F09 | done |
| F11 | Browser callback and signed redirect | M1 | F10 | planned |
| F12 | Gateway API: validations, payment requests, status checks | M1 | F05, F08 | planned |
| F13 | Forwarder: Aegis signature, optional JWE, retries, ordering | M1 | F10 | planned |
| F14 | Admin API and runtime settings | M1 | F05 | planned |
| F15 | Observability: metrics, alerts, log masking | M1 | F09–F13 | planned |
| F16 | Lab through Aegis; M1 acceptance (T1–T19) | M1 | F03, F09–F15 | planned |

M2–M4 features are added here when M1 is done.
