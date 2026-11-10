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
| F11 | Browser callback and signed redirect | M1 | F10 | done |
| F12 | Gateway API: validations, payment requests, status checks | M1 | F05, F08 | done |
| F13 | Forwarder: Aegis signature, optional JWE, retries, ordering | M1 | F10 | done |
| F14 | Admin API, runtime settings, retention | M1 | F05, F13 | done |
| F15 | Observability: metrics, alerts, dashboards, log safety | M1 | F09–F14 | done |
| F16 | Lab through Aegis; M1 acceptance (T1–T19) | M1 | F03, F09–F15 | done |

## Hardening (after M1)

From an external review of correctness, abuse resistance and recovery; each is one branch.

| ID | Hardening | Status |
|---|---|---|
| H1 | Payment facts: explicit transitions, fact identity (partial refunds, reversals), unsupported events not forwarded | done |
| H2 | Database-enforced identities: message IDs and business facts unique by constraint | done |
| H3 | Abuse limits: bounded forgery quarantine, per-connection rate and concurrency limits, poison records to a dead-letter topic | done |
| H4 | Callback and destinations: expiring, payment-bound signed results; destination URL policy (SSRF) | done |
| H5 | Operations: correlation IDs through HTTP and Kafka, Kafka in readiness, recovery runbook, pipeline benchmark | done |
| H6 | Post-hardening review: required provider fields, clock skew, refunds as facts, exact guarantees, retention by activity | done |
| H7 | Timestamp requirements by message type, enforced in the domain | done |
| H8 | Backups in the stack, `GET /admin/evidence`, weekly automated restore rehearsal | done |

## M2 — more providers, load and failure tests

| ID | Feature | Status |
|---|---|---|
| F17 | Stripe adapter: `Stripe-Signature`, PaymentIntents and refunds; freshness judged at arrival | done |
| F18 | Cashfree adapter: timestamped HMAC, exact decimal amounts, orders and payment status | done |
| F19 | Failure lab: kill and restart Aegis, Kafka and PostgreSQL under load; every payment delivered | done |

M2 is done: both adapters pass the shared contract and end-to-end tests, the Failure lab runs in CI,
and the benchmark was re-run (BENCHMARK).

## M3 — NPCI-style XML providers (BBPS, UPI)

Contract: [NPCI.md](../design/NPCI.md) · decisions D33, D34.

| ID | Feature | Status |
|---|---|---|
| F20 | XML signatures (one whole-document profile) and secure XML parsing in the SPI; untrusted replies are `BAD_SIGNATURE` | done |
| F21 | BBPS adapter at the COU: bill fetch, payment, async response, status checks | done |
| F22 | UPI adapter at the payer PSP: `ReqValAdd`, `ReqPay`, `RespPay` (deemed, reversal), `ReqChkTxn` | done |
| F23 | NPCI switch simulator (lab): independent signing, scripted outcomes, redelivery, attack messages | done |
| F24 | NPCI lab: 16 attacks and misuses, legitimate traffic, load, failures under NPCI traffic, CI job | done |

M3 is done: both adapters pass the shared contract and end-to-end tests, the NPCI lab and the
failure lab with bill payments run in CI, and the review findings are fixed (HARDENING §15).

## M4 — partner API protection and rate limits

Design: HLD §3.2 · LLD §14 · decisions D35, D36.

| ID | Feature | Status |
|---|---|---|
| F25 | Partner API: signed requests, freshness, scopes, rates, one execution per request ID, proxy, Admin lookup | done |
| F26 | Shared partner rates in Redis (GCRA script), fail closed, in the deploy stack | done |
| F27 | Partner lab (direct vs. two Aegis instances), CI job, benchmark; Prometheus and Grafana on every server | done |

M4 is done: the partner API passes its unit, integration and lab tests (T20–T30), the Partner lab runs
in CI, the benchmark was run (BENCHMARK), metrics and alerts run on every server, and the review
findings are fixed (HARDENING §16).

