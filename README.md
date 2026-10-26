# Aegis

**A self-hosted, payment-aware firewall for payment callbacks and webhooks.**

Aegis sits in front of your backend and forwards a payment event only when it is **genuine,
unique, confirmed with the provider, and consistent with the payment's state** — with no change
to your application code.

```
Razorpay / Stripe ──► Aegis ──signed──► Your backend (private)
                       verify · dedupe · confirm · state check · deliver · trace
```

## Why

Forged or mishandled payment callbacks keep causing real losses. In 2026 alone, several public
CVEs let attackers mark unpaid orders as paid by forging callbacks — CVE-2026-11575 (PhonePe
plugin), CVE-2026-9832, CVE-2026-87978, CVE-2026-16738, CVE-2026-33661. Each team re-implements
verification and gets one detail wrong. Aegis does it once, in one place.

## Architecture

```
Provider ─► Ingress ─► Kafka ─► Processor ─► PostgreSQL ─► Deliverer ─► Backend
            verify               dedupe ·                  signed POST,
            then ack             confirm with provider ·   retries, per-payment order
                                 state machine
```

| | |
|---|---|
| Language | Java 25, Spring Boot 4 (virtual threads) |
| Event log | Apache Kafka (KRaft) |
| State & queues | PostgreSQL |
| Observability | Micrometer · Prometheus · Grafana |
| Deploy | Docker Compose on a single Ubuntu server |

## Design

- [High-level design](docs/design/HLD.md) — goals, architecture, guarantees, failure behaviour, scaling
- [Low-level design](docs/design/LLD.md) — flows, state machine, schema, APIs, config
- [Decisions](docs/design/DECISIONS.md) — every technology choice and why

## Milestones

| # | Milestone | Status |
|---|---|---|
| M0 | Exploit Lab — reproduce real callback CVEs in Docker | design done |
| M1 | Integrity core for Razorpay (verify, dedupe, confirm, state machine, deliver, admin) | design done |
| M2 | Stripe adapter, load + chaos tests, published benchmarks | planned |
| M3 | Partner API protection (mTLS/HMAC, rate limits) | planned |
| M4 | Card-testing / risk detection | planned |
| M5 | AI analyst with rule simulation | planned |

Learning-in-public project. Aegis never handles raw card data.
