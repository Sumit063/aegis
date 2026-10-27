# Aegis — High-Level Design

Read [PAYMENT-FLOWS.md](PAYMENT-FLOWS.md) first for the payment flow and the terms used here.

## 1. Problem

A merchant fulfils an order (ships goods, credits a wallet, performs a recharge) when it believes a
payment succeeded. That belief arrives through two routes — the customer's device (checkout
handler) and the gateway's webhooks — and both are commonly mishandled: missing or empty secrets,
unverified code paths, payments not bound to orders, replays, duplicates, a failed attempt
cancelling a paid order, and the same order fulfilled by both routes.
(2026 examples: CVE-2026-11575, CVE-2026-9832, CVE-2026-87978, CVE-2026-16738, CVE-2026-33661.)

**Aegis is a self-hosted service that decides what the merchant may believe about money.** It
verifies and confirms every claim with the provider, merges both routes into one state machine,
and tells the merchant to fulfil **once per order**.

## 2. Goals

| # | Goal | Measured by |
|---|---|---|
| G1 | **No forged or unconfirmed claim is ever reported as success** | Exploit Lab: every reproduced CVE blocked |
| G2 | **No accepted event is ever lost** | Crash tests: events acknowledged == events stored |
| G3 | **One fulfilment signal per order**, whichever route reports first | Race tests: Route A + Route B + duplicates → one `order.paid` ID |
| G4 | **No illegal state change**; a failed attempt never undoes a paid order | State-machine tests |
| G5 | **Every order is traceable** in one query | Admin API timeline |
| G6 | **Fast where someone waits** | Webhook ack p99 < 50 ms · Verify API p99 < 500 ms · webhook end-to-end p99 < 2 s |
| G7 | **Scales by adding processes, not by redesign** | 500 events/s sustained, 2,000/s burst on one server |

## 3. Non-goals

- **Never moves money.** Aegis does not create orders, capture, refund, recharge or settle; the
  merchant does. Aegis decides what the merchant should believe and when to act.
- **Never handles raw card data** — stays out of PCI-DSS scope.
- Not a payment orchestrator, not a WAF / DDoS shield (runs behind one if needed).
- Does not fix the merchant's own order logic (e.g. amount taken from the client at order creation).
- No multi-tenant SaaS and no web UI in v1 (admin API + Grafana).

## 4. Core idea: claim → confirm → transition → notify once

Every input is a **claim** ("payment pay_X captured", "recharge R failed"). Aegis handles every
claim the same way, whatever its route or provider:

1. **Verify** the claim's signature (proves who sent it).
2. **Confirm** with the provider's API (proves it is true, for this order and amount).
3. **Transition** the order/transaction through a state machine (rejects impossible moves).
4. **Notify once** — emit one event per real state change, with a stable ID.

Payments, refunds and (from M3) recharge fulfilments are all **transactions** with their own state
machine, so new flows plug in as adapters without changing the core.

## 5. Architecture

```
 Razorpay ──webhook──► Ingress ──► Kafka ──► Processor ◄──confirm──► Razorpay API
                     (public)              dedupe · confirm ·            ▲
                     verify, ack           transition                    │ confirm
                                               │                         │
 Merchant ──POST /verify (Route A)──► Verify API ──── same engine ───────┘
 backend ◄── verdict + order.paid ID ─┘    │
    ▲                                      ▼
    │                                  PostgreSQL ◄──── Admin API (tailnet only)
    │                         orders · transactions · events · deliveries
    │                                      │
    └────── signed POST, retries ───── Deliverer
```

| Component | Exposed to | Job |
|---|---|---|
| **Ingress** | Internet (providers) | Verify webhook signature, write to Kafka, then ack |
| **Verify API** | Merchant backend only | Route A: verify checkout proof, confirm, transition, answer synchronously |
| **Processor** | — | Consume Kafka, dedupe, confirm, transition; re-check stuck transactions |
| **Deliverer** | — | Send events to the merchant backend, signed, with retries, in order per order |
| **Admin API** | Tailnet only | Timelines, search, manual retry |

All are **one Spring Boot application** started with different roles
(`AEGIS_ROLES=ingress,verify,processor,deliverer,admin`): deployed together today, scaled
separately later.

## 6. Flows

### 6.1 Route B — webhook
1. **Receive** at `/in/{source}`, verify signature in constant time. Invalid → `401`, nothing stored.
2. **Persist, then ack.** Write to Kafka (`acks=all`); only then `200`. Kafka unavailable → `503`
   so the provider retries. *Nothing is acknowledged unless it is stored.*
3. **Dedupe** on the provider's event ID. Seen → count and stop.
4. **Confirm** — fetch payment **and** order from the provider; check status, amount, currency,
   and that the payment belongs to the order. Mismatch → `REJECTED` + alert. Provider down → retry;
   never forwarded unconfirmed.
5. **Transition** under a row lock. Impossible or outdated move → recorded, not forwarded.
6. **Notify** — a real change creates one event (e.g. `order.paid`) and a delivery.

### 6.2 Route A — checkout verification
1. The customer's app sends `payment_id` + `signature` to the merchant backend.
2. The backend calls `POST /verify` with those **and its own stored `order_id`** (never the client's).
3. Aegis verifies the checkout signature, confirms with the provider, and runs the **same
   transition** as 6.1 under the same lock.
4. Responds with the order state and, if `PAID`, the **`order.paid` event ID** — the same ID Route B
   would produce. The event is also delivered through the Deliverer, so a backend crash right after
   the response cannot lose the fulfilment signal.

### 6.3 Both routes together
Whichever route arrives first moves the order to `PAID` and creates `order.paid`. The other route
finds the order already `PAID` and becomes a no-op (`STALE`). The backend fulfils on `order.paid`,
deduplicated by its ID — **exactly one fulfilment per order**.

### 6.4 Re-checking stuck transactions
A transaction left in a non-final state (payment `AUTHORIZED`, recharge `PENDING`) is re-confirmed
with the provider on a backoff schedule, even if no further callback arrives. Still unresolved
after its deadline → alert.

### 6.5 Recharge fulfilment (M3)
Same engine, different adapter: aggregator callbacks are verified, confirmed via the aggregator's
status-check API, and run through the fulfilment state machine. A refund signal
(`fulfilment.failed`) is emitted only for a **confirmed, final** failure. See PAYMENT-FLOWS §6.

## 7. What the merchant backend must do

| Rule | Why |
|---|---|
| Accept callbacks **only from Aegis** (network isolation + Aegis signature) | Otherwise attackers bypass Aegis |
| **Fulfil only on `order.paid`** (or a `/verify` response with state `PAID`) | One trigger, both routes |
| **Deduplicate on the event ID**, and keep a unique constraint "one fulfilment per order" | Delivery is at-least-once |
| **Never cancel an order on `payment.failed`** — it means one attempt failed | The customer may retry and succeed |
| On `order.overpaid`, refund the extra payment | Two attempts captured on one order |
| Pass its own stored `order_id` to `/verify` | Stops "pay cheap, claim expensive" |

## 8. Guarantees (stated precisely)

- **Delivery is at-least-once.** With the backend deduplicating on event ID, the effect is once.
- **Ordering is per order**, not global.
- **Strict mode (default):** nothing is reported as success without provider confirmation.
  `verify_only` mode exists for providers without a confirmation API and is documented as weaker.
- **One `order.paid` per order, forever.** A second captured payment produces `order.overpaid`,
  never a second `order.paid`.

## 9. Failure behaviour

| Failure | Behaviour | Outcome |
|---|---|---|
| Kafka down | Ingress returns `503`; providers retry for hours | No loss |
| PostgreSQL down | Ingress keeps accepting into Kafka; Verify API returns `503` | No loss; backend shows "processing" and Route B completes later |
| Provider API down | Claims wait in `CONFIRMING`, retried with backoff, `HELD` + alert after 24 h | Nothing unconfirmed reported as success |
| Verify API down/slow | Backend shows "payment processing"; Route B delivers `order.paid` when it can | Slower, not wrong |
| Merchant backend down | Deliveries retried ~24 h, then `DEAD` + alert; manual replay | No loss |
| Aegis crash mid-step | Every step idempotent; offsets committed after DB commit; leases expire and are retried | No double processing |
| Forged-request flood | Rejected at Ingress before storage | Storage and real payments unaffected |
| Aegis fully down | **Payments still succeed** (Aegis is not in the money path); notifications wait for provider retries | Delay only |

## 10. Security model

- **Fail-closed config:** a missing or weak secret prevents startup.
- **Secret rotation without downtime:** two webhook secrets (current + previous) accepted during rotation.
- **Least privilege:** Ingress holds only webhook secrets — no database, no provider API keys.
  Verify/Processor hold API keys; Deliverer holds backend signing secrets.
- **Verify API and Admin API are never public** (internal network / tailnet, token-authenticated).
- **Personal data:** raw bodies (may contain email, phone, UPI IDs) kept 90 days, masked in logs.

## 11. Latency, capacity and cost

- **Customers rarely wait on Aegis.** Route B is asynchronous. Route A adds one provider API call
  (~150–400 ms), which a careful backend makes anyway.
- **Aegis sees only payment events** (1–5 per payment), a tiny fraction of the backend's traffic:
  1 M payments/day ≈ 350 events/s at a 10× peak — two small instances plus Kafka and PostgreSQL that
  most companies already run.
- **The real throughput limit is the provider's API rate limit**, since every claim is confirmed.
  Confirmations for the same order are coalesced, and concurrency per provider is capped.
- **Aegis down never blocks a payment** — only delays notifications.

## 12. Scaling path

| Load | Change |
|---|---|
| Today (1 server) | All roles on one host, single Kafka broker, 12 partitions |
| More webhooks | More Ingress replicas behind a load balancer (stateless) |
| More processing | More Processors, up to the partition count |
| More deliveries | More Deliverers (`SKIP LOCKED`, no coordination) |
| PostgreSQL pressure | Monthly partitioning of `claims`, read replica for Admin, retention jobs |
| Multi-host | Kafka 3 brokers (RF 3, `min.insync.replicas=2`); PostgreSQL primary + standby |

No step changes data formats or component boundaries.

## 13. Milestones

| # | Deliverable | Done when |
|---|---|---|
| M0 | **Exploit Lab** — real vulnerable integrations in Docker + exploit scripts | Each exploit marks an order paid without Aegis |
| M1 | **Razorpay core** — Ingress, Verify API, Processor (with re-checks), Deliverer, Admin, metrics | All M0 exploits blocked; G1–G5 tests green |
| M2 | Stripe adapter; load, crash and chaos tests; published benchmarks | G6, G7 met and documented |
| M3 | **Recharge / bill-payment fulfilment** — aggregator adapter (simulated aggregator) | Forged/premature `FAILED` never yields a refund signal |
| M4 | Partner API protection — mTLS/HMAC partners, rate limits (adds Redis) | |
| M5 | Risk engine — card-testing detection from payment metadata | |
| M6 | AI analyst — explains failures, drafts rules, simulates them on history | |

Details: [LLD.md](LLD.md) · Choices: [DECISIONS.md](DECISIONS.md)
