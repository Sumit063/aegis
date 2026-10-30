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
5. **Merge** — CI green, squash merge, automatic deploy. Status → `done`.

## Backlog

| ID | Feature | Milestone | Depends on | Status |
|---|---|---|---|---|
| F00 | Repository scaffold, toolchain, CI/CD | — | — | done |
| F01 | Lab: Razorpay-compatible mock gateway | M0 | F00 | planned |
| F02 | Lab: reference shop with flaw switches F1–F9 | M0 | F01 | planned |
| F03 | Lab: attack scripts and results runner | M0 | F02 | planned |
| F04 | Domain: value objects, payment targets, transactions, state machines | M1 | F00 | planned |
| F05 | Persistence: Flyway schema, JPA entities, repositories | M1 | F04 | planned |
| F06 | Configuration: sources, secrets, roles, fail-closed validation | M1 | F00 | planned |
| F07 | Provider SPI, registry, contract test suite | M1 | F04 | planned |
| F08 | Razorpay provider module | M1 | F07 | planned |
| F09 | Ingress: webhook authentication and claim log (Kafka) | M1 | F06, F08 | planned |
| F10 | Claim consumer: dedupe and record | M1 | F05, F09 | planned |
| F11 | Confirmation: rule chain, transitions, outbox | M1 | F05, F08 | planned |
| F12 | Verify API (checkout route) | M1 | F11 | planned |
| F13 | Delivery worker: signing, retries, per-target ordering, passthrough | M1 | F11 | planned |
| F14 | Recheck worker | M1 | F11 | planned |
| F15 | Admin API: timelines, reconfirm, retry | M1 | F05 | planned |
| F16 | Observability: metrics, alerts, dashboards, log masking | M1 | F09–F13 | planned |
| F17 | Lab run through Aegis; M1 acceptance (T1–T19) | M1 | F03, F09–F16 | planned |

M2–M6 features are added here when M1 is done.
