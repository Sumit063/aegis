# Aegis — High-Level Design

## 1. Problem

When a payment provider (Razorpay, Stripe, …) tells a merchant "payment X is captured", the
merchant ships goods, credits wallets or activates plans. That callback is where money turns into
value, and teams keep getting it wrong: missing/empty signature secrets, unverified code branches,
no binding of payment to order/amount, replays, duplicates and out-of-order events.
(2026 examples: CVE-2026-11575, CVE-2026-9832, CVE-2026-87978, CVE-2026-16738, CVE-2026-33661.)

**Aegis is a self-hosted gateway that sits in front of the merchant backend and only lets through
payment events that are genuine, unique, confirmed with the provider, and consistent with the
payment's state — with no change to the backend's code.**

## 2. Goals

| # | Goal | Measured by |
|---|---|---|
| G1 | **No forged event is ever forwarded** | Exploit Lab: every reproduced CVE blocked |
| G2 | **No accepted event is ever lost** | Chaos tests: events acked == events stored, after crashes |
| G3 | **Each event takes effect once** at the backend | Duplicate/retry tests: one state change per event |
| G4 | **Backend never sees an illegal state change** (e.g. `failed` after `captured`) | State-machine tests |
| G5 | **Every event is traceable** — "what happened to payment X?" in one query | Admin API timeline |
| G6 | **Cheap on the hot path** | Ingress ack p99 < 50 ms; end-to-end p99 < 2 s (happy path) |
| G7 | **Scales by adding processes, not by redesign** | Load test: 500 ev/s sustained, 2,000 ev/s burst on one server |

## 3. Non-goals

- **Never handles raw card data.** Card entry stays with the provider; Aegis stays out of PCI-DSS scope.
- Not a payment orchestrator (does not create or route payments).
- Not a WAF / DDoS shield — runs *behind* Cloudflare or similar if needed.
- No multi-tenant SaaS, no web UI in v1 (admin API + Grafana only).

## 4. Where it sits

```
 Razorpay / Stripe ──HTTPS──► Aegis ──signed HTTPS──► Merchant backend
                                │                      (reachable ONLY from Aegis)
                                └─► confirms with provider API
```

The backend must not be reachable from the internet on its callback routes; otherwise attackers
simply go around Aegis.

## 5. Architecture

```
                 ┌────────────┐   produce (acks=all)   ┌──────────────────────┐
 Provider ──────►│  Ingress   │───────────────────────►│ Kafka                │
   HTTPS         │ (stateless)│   then 200 OK          │ aegis.events.received│
                 └────────────┘                        └──────────┬───────────┘
                  verify signature                                │ consume
                  no DB access                                    ▼
                                                      ┌──────────────────────┐   fetch payment
                                                      │  Processor           │◄─────────────► Provider API
                                                      │ dedupe → confirm →   │
                                                      │ state machine        │
                                                      └──────────┬───────────┘
                                                                 │ writes
                                                                 ▼
 Admin API (tailnet only) ◄──────────────────────────► ┌──────────────────────┐
  timeline · search · replay                           │ PostgreSQL           │
                                                       │ events · payments ·  │
                                                       │ deliveries           │
                                                       └──────────┬───────────┘
                                                                  │ poll due deliveries
                                                                  ▼
                                                      ┌──────────────────────┐
                                                      │  Deliverer           │──signed POST──► Backend
                                                      │ retries · backoff ·  │
                                                      │ per-payment FIFO     │
                                                      └──────────────────────┘
 Later consumers of Kafka: risk engine (M4), analytics / AI analyst (M5)
```

All four components are **one Spring Boot application** started with different roles
(`AEGIS_ROLES=ingress,processor,deliverer,admin`), so they can be deployed together on one
server today and scaled independently later.

## 6. Event lifecycle

1. **Receive** — Ingress reads the raw body (≤ 256 KB), finds the source from the URL
   (`/in/{source}`), verifies the provider signature in constant time.
   Bad signature → `401`, metric + log, nothing stored.
2. **Persist, then ack** — Ingress writes the event to Kafka (`acks=all`) and only then returns
   `200`. If Kafka is unavailable it returns `503` so the provider retries. *Nothing is acked
   that is not durably stored* (G2).
3. **Dedupe** — Processor records `(source, provider_event_id)`. Seen before → count it, stop (G3).
4. **Confirm** — Processor fetches the payment from the provider API and checks status, amount,
   currency and order ID against the claim. Mismatch → `rejected` + alert (G1).
   Provider API down → retry later; event is **held, never forwarded unconfirmed**.
5. **Apply state** — Processor locks the payment row and applies the transition. Illegal or stale
   transition → not forwarded, recorded (G4).
6. **Deliver** — Deliverer POSTs to the backend, signed by Aegis, with retries. Deliveries for the
   same payment go out strictly in order.
7. **Trace** — every step is recorded against the event and payment (G5).

## 7. Guarantees (stated precisely)

- **Delivery is at-least-once.** Exactly-once *effect* requires the backend to dedupe on the
  `Aegis-Event-Id` header. Aegis makes this easy (stable ID, per-payment order) but cannot do it
  for the backend.
- **Ordering is per payment**, not global.
- **Strict mode (default):** unconfirmed events are never forwarded. A source can opt into
  `verify_only` mode when its provider has no confirmation API — documented as weaker.

## 8. Failure behaviour

| Failure | Behaviour |
|---|---|
| Kafka down | Ingress returns `503`; providers retry (hours). No loss. |
| PostgreSQL down | Ingress keeps accepting; events wait in Kafka; processor resumes. |
| Provider API down | Events held in `confirming`, retried with backoff, alert after threshold. |
| Backend down | Deliveries retried with backoff for ~24 h, then `dead` + alert; manual replay. |
| Process crash mid-step | Kafka offsets committed only after DB commit; delivery leases expire and are retried. Every step is idempotent. |
| Forged-request flood | Rejected at ingress before any storage; per-IP rate limit (M3). |

## 9. Security model

- **Fail-closed config:** a source with a missing or weak secret stops Aegis from starting.
- **Least privilege:** the internet-facing ingress has **no database credentials** and no provider
  API keys — only signing secrets. The processor holds API keys; the deliverer holds backend secrets.
- **Backend trust:** forwarded requests carry an Aegis HMAC signature (or mTLS); the backend
  accepts callbacks only from Aegis.
- **Admin API** listens on the Tailscale interface only, with a token.
- Secrets come from environment / mounted files, never from the repo.

## 10. Scaling path

| Load | Change |
|---|---|
| Today (1 server) | All roles on one host, Kafka single node, 12 partitions |
| More ingress | Add ingress replicas behind a load balancer (stateless) |
| More processing | Add processors up to the partition count (12) |
| More deliveries | Add deliverers (`SKIP LOCKED`, no coordination needed) |
| Postgres pressure | Monthly partitioning of `events`, read replica for admin, retention jobs |
| Multi-host | Kafka 3 brokers (RF 3, min ISR 2); Postgres primary + standby |

No step requires changing data formats or component boundaries.

## 11. Milestones

| # | Deliverable | Done when |
|---|---|---|
| M0 | **Exploit Lab** — real vulnerable plugins in Docker + exploit scripts | Each exploit marks an order paid without Aegis |
| M1 | **Integrity core** for Razorpay: ingress, processor, confirm, state machine, deliverer, admin API, metrics | All M0 exploits blocked; G1–G5 tests green |
| M2 | Stripe adapter, load + chaos tests, retention, published benchmarks | G6, G7 met and documented |
| M3 | Partner API protection: mTLS/HMAC partners, rate limits (adds Redis) | |
| M4 | Risk engine: card-testing detection from payment metadata (Kafka consumer) | |
| M5 | AI analyst: explains failures, drafts rules, simulates them on history | |

Detailed design: [LLD.md](LLD.md) · Technology choices: [DECISIONS.md](DECISIONS.md)
