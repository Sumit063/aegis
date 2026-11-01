# Aegis

**A zero-trust security gateway for payment messages.**

Aegis sits between an application and its payment providers (gateways, NPCI UPI/BBPS, aggregators).
Every payment message passes through it in both directions. Aegis verifies each one: signature,
freshness, duplicates, match with what the provider validated, and no downgrade of a success. It
records the message and forwards the original, with its verdict, or blocks it. It holds the provider
secrets, and it never calls a provider unless the application asks.

```
 Application ──validation · payment request · status check──► AEGIS ──signed──► Provider
 Application ◄──original message + verdict (mTLS, signed)───── AEGIS ◄──callbacks · webhooks── Provider
```

## Why

Applications hand over value when a message says money moved, and those messages are often trusted
without proper checks. In 2026 alone, public CVEs let attackers mark unpaid orders as paid through
missing, partial or broken signature checks: CVE-2026-94360, CVE-2026-103298, CVE-2026-87978,
CVE-2026-11575, CVE-2026-41432, CVE-2026-33661 and more. Duplicates, replays, out-of-order failures
and double payment requests cause the rest. Aegis does these checks once, the same way, for every
provider.

## What it guarantees

- Nothing reaches the application without passing every check.
- Nothing is acknowledged to a provider before it is stored.
- A fact (payment X succeeded) is forwarded once, whichever channel reports it first.
- No passed message is lost before the application acknowledges it.
- Aegis never initiates a call to a provider.

## Architecture

```
 Provider ──► Ingress ──► Kafka ──► Checker ──► PostgreSQL ──► Forwarder ──► Application
 Application ──► Gateway API ──► Provider
```

| | |
|---|---|
| Language | Java 25, Spring Boot 4 (virtual threads) |
| Ingress buffer | Apache Kafka (KRaft) |
| Records | PostgreSQL: `validations`, `payments` |
| Observability | Micrometer · Prometheus · Grafana |
| Deployment | Docker Compose |

## Design

- [Payment flows](docs/design/PAYMENT-FLOWS.md) — checkout, UPI, QR, recharges and bill payments, and where Aegis sits
- [High-level design](docs/design/HLD.md) — role, problems, checks, zero trust, guarantees, failures
- [Low-level design](docs/design/LLD.md) — endpoints, pipelines, adapter interface, schema, forwarding, tests
- [Decisions](docs/design/DECISIONS.md) — every choice and why
- [Exploit Lab](docs/design/EXPLOIT-LAB.md) — reproducible attacks that prove the protection ([lab/](lab/README.md))

## Engineering

- [Code architecture](docs/engineering/ARCHITECTURE.md) — hexagonal layout, dependency rules, patterns, provider adapters
- [CI/CD, environments and branching](docs/engineering/CICD.md)
- [Features](docs/features/README.md) — backlog and the feature-by-feature workflow
- [AGENTS.md](AGENTS.md) — rules and workflow for AI coding agents and contributors

## Development

Requirements: JDK 25, Docker (Docker Desktop or Colima).

```bash
scripts/setup-dev.sh                                      # git hooks + toolchain check
./mvnw verify                                             # full build: format, tests, architecture, integration, coverage
docker compose -f deploy/compose.yaml -f deploy/compose.local.yaml up -d postgres kafka  # local infra
./mvnw -pl aegis-service spring-boot:run                  # run the service (profile: local)
scripts/check.sh [staging]                                # validate local, or what the server runs
lab/run.sh                                                # Exploit Lab: every attack, results in lab/RESULTS.md
```

## Milestones

| # | Milestone | Status |
|---|---|---|
| M0 | Exploit Lab — reproduce the CVE classes in Docker ([results](lab/RESULTS.md)): 10/10 exploited directly, 10/10 blocked through Aegis | done |
| M1 | Core gateway with Razorpay ([acceptance](docs/features/F16-acceptance.md)) | done |
| M2 | Stripe and Paytm-style adapters; load and failure tests | planned |
| M3 | NPCI-style XML adapters (BBPS, UPI) against a simulator | planned |
| M4 | Partner API protection and rate limits | planned |

Aegis never handles raw card data and never moves money.
