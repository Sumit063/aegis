# Aegis

**A self-hosted service that decides what a merchant may believe about money.**

Aegis sits between payment providers and the merchant backend. It verifies every payment claim,
confirms it with the provider, merges the checkout route and the webhook route into one state
machine, and tells the backend to fulfil **once per order** — with minimal changes to existing code.

```
 Razorpay ──webhooks──► Aegis ──signed notifications──► Merchant backend ──► fulfil once
 Merchant backend ──/verify (checkout proof)──► Aegis
```

## Why

Fulfilment (shipping, wallet credit, recharge) happens when a merchant believes a payment
succeeded, and that belief is often wrong: forged or unverified callbacks, payments not bound to
orders, replays and duplicates, a failed attempt cancelling a paid order, or the same order
fulfilled by both the checkout and the webhook route. In 2026 alone, several public CVEs let
attackers mark unpaid orders as paid — CVE-2026-11575, CVE-2026-9832, CVE-2026-87978,
CVE-2026-16738, CVE-2026-33661.

## What it guarantees

- Nothing is reported as paid unless the provider confirms it, for that order and amount.
- No accepted event is lost.
- One `order.paid` per order, whichever route reports first.
- No impossible state change; a failed attempt never undoes a paid order.
- Every order has a full, queryable timeline.

## Architecture

```
Webhook ─► Ingress ─► Kafka ─► Processor ◄─confirm─► Provider API
                                  │
Backend ─► Verify API ── same engine ──► PostgreSQL ─► Deliverer ─► Backend
```

| | |
|---|---|
| Language | Java 25, Spring Boot 4 (virtual threads) |
| Event log | Apache Kafka (KRaft) |
| State & scheduled work | PostgreSQL |
| Observability | Micrometer · Prometheus · Grafana |
| Deployment | Docker Compose |

## Design

- [Payment flows](docs/design/PAYMENT-FLOWS.md) — where Aegis sits, use cases, checkout, UPI, QR, recharges and bill payments
- [High-level design](docs/design/HLD.md) — goals, architecture, flows, zero-trust security model, failures, scaling
- [Low-level design](docs/design/LLD.md) — state machines, APIs, schema, config, test catalogue
- [Decisions](docs/design/DECISIONS.md) — every choice, why, and the design review log
- [Exploit Lab](docs/design/EXPLOIT-LAB.md) — reproducible attacks that prove the protection

## Milestones

| # | Milestone | Status |
|---|---|---|
| M0 | Exploit Lab — reproduce real callback CVEs in Docker | design done |
| M1 | Razorpay core — webhooks, Verify API, re-checks, delivery, admin | design done |
| M2 | Dynamic QR payments, Stripe adapter, load and chaos tests, benchmarks | planned |
| M3 | Recharge / bill-payment fulfilment callbacks | planned |
| M4 | Partner API protection (mTLS/HMAC, rate limits) | planned |
| M5 | Card-testing / risk detection | planned |
| M6 | AI analyst with rule simulation | planned |

Aegis never handles raw card data and never moves money.
