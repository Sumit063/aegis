# Aegis — Low-Level Design

Scope: M1 (Razorpay). Later milestones add adapters and consumers without changing this core.
Vocabulary: inbound messages are **claims**; outbound events are **notifications**.

## 1. Components

```
Webhook ─► Ingress ─► Kafka ─► ClaimConsumer ─┐
                                              ├─► claims(CONFIRMING) ─► ConfirmWorker ─► transition ─► notifications ─► deliveries ─► DeliveryWorker ─► Backend
Backend ─► VerifyController ── inline ────────┘                              ▲
                                     RecheckWorker ── RECHECK claims ────────┘
```

| Component | Role | Secrets | Touches |
|---|---|---|---|
| `IngressController` | `ingress` | webhook secrets | Kafka (produce) |
| `VerifyController` | `verify` | provider API keys | Provider API, PostgreSQL |
| `ClaimConsumer` | `processor` | — | Kafka (consume), PostgreSQL |
| `ConfirmWorker` | `processor` | provider API keys | Provider API, PostgreSQL |
| `RecheckWorker` | `processor` | — | PostgreSQL |
| `DeliveryWorker` | `deliverer` | backend signing secrets | Backend, PostgreSQL |
| `AdminController` | `admin` | admin token | PostgreSQL |

Every step is **idempotent and crash-safe**. Slow work (provider and backend calls) runs in workers
that claim rows with a **lease** (`locked_until`); a crash lets the lease expire and another worker
retries.

## 2. Domain model

| Entity | One row per | Key states |
|---|---|---|
| **Order** (payment target) | provider order (`order_…`), or (M2) dynamic QR code (`qr_…`) | `OPEN` → `PAID` |
| **Transaction** | provider payment, refund, or (M3) fulfilment | per kind, below |
| **Claim** | inbound message (webhook, verify call, recheck) | `CONFIRMING → ACCEPTED \| STALE \| REJECTED \| IGNORED \| HELD` |
| **Notification** | real state change worth telling the backend | — (immutable, stable ID) |
| **Delivery** | notification × destination | `PENDING → SUCCEEDED \| RETRYING → DEAD` |

**Rule: claims trigger, the provider decides.** A claim never sets state directly. It causes Aegis
to fetch the provider's truth; the claim is then checked against that truth (to detect forgeries),
and the state is moved to what the provider says.

### 2.1 Transaction state machines

**PAYMENT**

| From | Allowed to |
|---|---|
| *(new)* | `AUTHORIZED`, `CAPTURED`, `FAILED` |
| `AUTHORIZED` | `CAPTURED`, `FAILED` |
| `FAILED` | `AUTHORIZED`, `CAPTURED` (late authorisation, provider-confirmed) |
| `CAPTURED` | `PARTIALLY_REFUNDED`, `REFUNDED` |
| `PARTIALLY_REFUNDED` | `PARTIALLY_REFUNDED`, `REFUNDED` |
| `REFUNDED` | — |

**REFUND**: *(new)* → `PENDING` → `PROCESSED` \| `FAILED`. A `PROCESSED` refund adds to its
payment's `refunded_minor` and moves the payment to `PARTIALLY_REFUNDED` / `REFUNDED`.

**FULFILMENT** (M3): *(new)* → `PENDING` → `SUCCEEDED` \| `FAILED`; `FAILED → SUCCEEDED` (late
success, alert); `SUCCEEDED → REVERSED`. Only a provider-confirmed final `FAILED` is reported.

**ORDER**: `OPEN` → `PAID` when its first payment reaches `CAPTURED`. Never leaves `PAID`.

**Payment targets.** The `orders` table holds every "thing a payment is made against", with a
`kind` and a `usage`:

| kind | usage | Behaviour |
|---|---|---|
| `ORDER` | `SINGLE` | As above: `order.paid` once, further captures `order.overpaid` |
| `QR_CODE` (M2) | `SINGLE` (fixed amount) | Identical to an order |
| `QR_CODE` (M2) | `MULTIPLE` | Stays `OPEN` until the gateway closes it (`CLOSED`); every captured payment emits `qr.payment_received` |

The amount check uses the target's fixed amount; a variable-amount QR has none, so only currency
and target membership are checked.

### 2.2 Notifications

| Notification | Emitted when | Subject (unique per type) |
|---|---|---|
| `order.paid` | order `OPEN → PAID` | order |
| `order.overpaid` | a payment is `CAPTURED` on an already `PAID` order | payment |
| `payment.authorized` | payment → `AUTHORIZED` (manual-capture merchants; opt-in) | payment |
| `payment.failed` | payment → `FAILED` **while its order is still `OPEN`** | payment |
| `qr.payment_received` (M2) | payment `CAPTURED` on a multi-use QR | payment |
| `refund.processed` / `refund.failed` | refund → `PROCESSED` / `FAILED` | refund |
| `fulfilment.succeeded` / `.failed` / `.late_success` / `.reversed` (M3) | fulfilment transitions | fulfilment |

`UNIQUE (type, subject_id)` in the database makes "one `order.paid` per order" impossible to break,
even under races between routes.

## 3. Route B — Ingress

`POST /in/{sourceId}`

1. Unknown source → `404`. Body > 256 KB → `413`.
2. `adapter.verifyWebhook(rawBody, headers, secrets)` against **each configured secret** (current,
   previous) in constant time. No match → `401`; metric + sampled log; nothing stored.
3. `adapter.parseWebhook(...)` → `dedupeKey` (provider event ID), `claimType`, `providerTxnId`,
   `providerOrderId`.
4. Produce to `aegis.claims` with key `sourceId:providerOrderId` (or `providerTxnId` when there is
   no order); wait for the broker ack (timeout 2 s).
5. `200`. Kafka failure → `503`.

Kafka message (JSON, `"v": 1`): `claimId` (UUIDv7), `sourceId`, `receivedAt`, `dedupeKey`,
`claimType`, `providerTxnId`, `providerOrderId`, selected `headers`, `bodyBase64`, `remoteIp`.

Topics: `aegis.claims` (12 partitions, 7 d) and `aegis.claims.dlq` (unparseable; alert if non-empty).

## 4. ClaimConsumer

One transaction per message:

```sql
INSERT INTO claim_dedupe (source_id, dedupe_key, claim_id) VALUES (...) ON CONFLICT DO NOTHING;
-- conflict → UPDATE claim_dedupe SET duplicate_count = duplicate_count + 1, last_seen_at = now()
-- new      → INSERT INTO claims (..., origin = 'WEBHOOK', status = 'CONFIRMING', next_attempt_at = now())
```

Commit, **then** commit the Kafka offset. A replay after a crash hits the dedupe row and does nothing.

## 5. Confirmation (shared by ConfirmWorker, VerifyController, RecheckWorker)

`confirm(claim)`:

1. Fetch the transaction (`GET /v1/payments/{id}` or refund) and its order (`GET /v1/orders/{id}`).
   Fetches for the same order within 2 s are coalesced; concurrency per source is capped.
2. Checks:

| Check | Fail → |
|---|---|
| Transaction exists | `REJECTED (not_found)` |
| Payment has a target (order; QR code from M2) | `IGNORED (no_target)` + alert |
| Claim's order ID (if any) equals the payment's order ID | `REJECTED (order_mismatch)` |
| Payment amount and currency equal the order's | `REJECTED (amount_mismatch)` |
| Provider state is not *contradicting* the claim (e.g. claim `captured`, provider `failed`) | `REJECTED (contradicted)` |
| Provider state is not *behind* the claim (claim `captured`, provider still `authorized`) | retry; after 3 tries `REJECTED (unconfirmed)` |
| Refund: belongs to the payment, and total refunded ≤ payment amount | `REJECTED (refund_mismatch)` |

3. **Transition** in one database transaction:
   `SELECT … FROM orders WHERE … FOR UPDATE` (create if absent), then the transaction row
   `FOR UPDATE`; move the transaction to the **provider's** state if the move is allowed; derive the
   order state; insert notifications (`ON CONFLICT (type, subject_id) DO NOTHING`); insert a
   delivery per subscribed destination; record transitions; set claim status
   (`ACCEPTED` if anything changed, else `STALE`).
4. Schedule a recheck if the transaction is left non-final (§7).

Provider error or timeout (3 s) → `confirm_attempts += 1`, retry at 5 s, 15 s, 1 m, 5 m, 15 m, then
every 30 m; after 24 h → `HELD` + alert.

Lock order is always **order, then transaction**, which rules out deadlocks.

## 6. Route A — Verify API

`POST /verify` (internal network only, bearer token)

```json
{ "sourceId": "razorpay-main",
  "orderId": "order_Q7x…",        // from the merchant's OWN database, never from the client
  "paymentId": "pay_Q7x…",        // from the client
  "signature": "…" }              // from the client
```

1. `HMAC_SHA256(orderId + "|" + paymentId, key_secret)` equals `signature` (constant time),
   else `INVALID`.
2. Fast path: order already `PAID` by this payment → answer from the database, no provider call.
3. Otherwise record a claim (`origin = 'VERIFY'`, dedupe key `checkout:{paymentId}`) and run §5
   **inline** with a 3 s provider timeout.
4. Respond:

```json
{ "verdict": "PAID",               // PAID | PENDING | FAILED | INVALID | REJECTED | UNAVAILABLE
  "order": { "providerOrderId": "order_Q7x…", "merchantOrderRef": "rcpt_1042",
             "amountMinor": 49900, "currency": "INR", "state": "PAID" },
  "notificationId": "0193…"        // the order.paid ID — same one the Deliverer sends
}
```

- `UNAVAILABLE` (provider or database unreachable): the backend shows "payment processing"; the
  claim stays `CONFIRMING` and Route B or the ConfirmWorker finishes the job.
- The `order.paid` notification is **always** delivered by the Deliverer too, so a backend crash
  after receiving this response cannot lose the fulfilment signal; the backend deduplicates on
  `notificationId`.
- Idempotent: repeated calls return the same verdict and ID.
- Rate limit: 10 calls per order per minute.

## 7. RecheckWorker

Every 30 s, claims transactions with `next_check_at <= now()` (lease pattern) and inserts a claim
with `origin = 'RECHECK'`; the ConfirmWorker handles it like any other claim.

| Non-final state | Recheck schedule | Deadline → alert |
|---|---|---|
| Payment `AUTHORIZED` | 5 m, 15 m, 1 h, then every 6 h | 5 days |
| Refund `PENDING` | 1 h, then every 6 h | 7 days |
| Fulfilment `PENDING` (M3) | 1 m, 2 m, 5 m, 15 m, 30 m, then hourly | 48 h |

## 8. DeliveryWorker

Claims due deliveries with the lease pattern plus **per-order FIFO**:

```sql
... WHERE d.status IN ('PENDING','RETRYING') AND d.next_attempt_at <= now()
  AND NOT EXISTS (SELECT 1 FROM deliveries e
                  WHERE e.order_key = d.order_key AND e.destination_id = d.destination_id
                    AND e.seq < d.seq AND e.status IN ('PENDING','RETRYING'))
```

- `2xx` → `SUCCEEDED`. `410` → `DEAD`. Other status or timeout (10 s) → `RETRYING`.
- Backoff `min(10 s × 2^attempt, 1 h)` ± 20 % jitter; 20 attempts (~24 h) → `DEAD` + alert.
- Each attempt is written to `delivery_attempts`, and gets a fresh timestamp and signature.

### Request

```
POST <destination.url>
Aegis-Event-Id: <notification id>       ← backend deduplicates on this
Aegis-Timestamp: <unix seconds>
Aegis-Signature: v1=<hex HMAC_SHA256(secret, timestamp + "." + body)>[,v1=<previous secret>]
Aegis-Attempt: <n>
```

**envelope** (default):

```json
{ "id": "0193…", "type": "order.paid", "sourceId": "razorpay-main", "createdAt": "…Z",
  "order": { "providerOrderId": "order_Q7x…", "merchantOrderRef": "rcpt_1042",
             "amountMinor": 49900, "currency": "INR", "state": "PAID" },
  "transaction": { "kind": "PAYMENT", "providerTxnId": "pay_Q7x…", "state": "CAPTURED",
                   "amountMinor": 49900, "currency": "INR", "method": "upi" },
  "verification": { "confirmedWithProvider": true, "confirmedAt": "…Z", "via": "VERIFY" } }
```

`merchantOrderRef` is the order's `receipt` **as returned by the provider API**, never taken from
the claim, so the backend can map the payment to its own order safely.

**passthrough** (legacy apps that expect the provider's own format): each unique webhook that was
confirmed and not contradicted is forwarded **once**, with its original body and headers plus the
`Aegis-*` headers — including webhooks that were `STALE` for Aegis, because the legacy app may not
have heard through any other route. Only harmful ones are suppressed: `payment.failed` for an
order that is already `PAID`. Each forward is recorded as a `passthrough.forward` notification with
the provider event ID as its subject, so `UNIQUE (type, subject_id)` guarantees it is forwarded at
most once ever — a replay after dedupe expiry is not forwarded again.

## 9. Database (PostgreSQL)

```sql
claim_dedupe    (source_id, dedupe_key) PK, claim_id, first_seen_at, last_seen_at, duplicate_count

claims          id uuid PK, source_id, origin (WEBHOOK|VERIFY|RECHECK), dedupe_key, claim_type,
                provider_txn_id, provider_order_id, received_at, raw_body bytea, headers jsonb,
                status, status_reason, provider_snapshot jsonb,
                confirm_attempts, next_attempt_at, locked_until, updated_at
                idx (next_attempt_at) WHERE status = 'CONFIRMING'
                idx (source_id, provider_order_id), (received_at)

orders          id uuid PK, source_id, kind (ORDER|QR_CODE), usage (SINGLE|MULTIPLE),
                provider_order_id, merchant_order_ref,
                amount_minor bigint NULL (NULL = variable-amount QR), currency char(3),
                state (OPEN|PAID|CLOSED),
                paid_by_txn_id, version int, created_at, updated_at
                UNIQUE (source_id, provider_order_id)

transactions    id uuid PK, source_id, kind (PAYMENT|REFUND|FULFILMENT), provider_txn_id,
                order_id, parent_txn_id, amount_minor bigint, refunded_minor bigint,
                currency char(3), state, method, version int,
                next_check_at, check_attempts, check_deadline, locked_until,
                created_at, updated_at
                UNIQUE (source_id, kind, provider_txn_id)
                idx (next_check_at) WHERE next_check_at IS NOT NULL

transitions     id bigserial PK, entity (ORDER|TXN), entity_id, from_state, to_state, claim_id, at

notifications   id uuid PK, type, subject_id, order_id, txn_id, payload jsonb, created_at
                UNIQUE (type, subject_id)

deliveries      id uuid PK, seq bigserial UNIQUE, notification_id, order_key, destination_id,
                status, attempt_count, next_attempt_at, locked_until,
                last_http_status, last_error, created_at, updated_at
                UNIQUE (notification_id, destination_id)
                idx (next_attempt_at) WHERE status IN ('PENDING','RETRYING')
                idx (order_key, destination_id, seq)

delivery_attempts  id bigserial PK, delivery_id, attempt_no, started_at, duration_ms,
                   http_status, error
```

Amounts are `bigint` minor units. IDs are UUIDv7. Timestamps are `timestamptz` (UTC).
Retention (M2): raw bodies 90 d; `claim_dedupe` 30 d (far beyond any provider retry window — an
older replay still meets the state machine and is `STALE`).

## 10. Provider adapter

```java
public interface ProviderAdapter {
    String provider();                                                     // "razorpay"
    boolean verifyWebhook(byte[] rawBody, HttpHeaders headers, List<String> secrets);
    ParsedClaim parseWebhook(byte[] rawBody, HttpHeaders headers);
    boolean verifyCheckout(CheckoutProof proof, Source source);           // Route A; optional
    ProviderTransaction fetchTransaction(TxnKind kind, String providerTxnId, Source source);
    ProviderOrder fetchOrder(String providerOrderId, Source source);
}
```

**Razorpay (M1)**
- Webhook: `X-Razorpay-Signature` = HMAC-SHA256(raw body, webhook secret); event ID from
  `X-Razorpay-Event-Id`.
- Checkout: HMAC-SHA256(`order_id|payment_id`, key secret).
- Truth: `GET /v1/payments/{id}`, `GET /v1/orders/{id}`, refunds via the payment's refunds endpoint
  (basic auth, key ID + key secret).
- Claim types handled: `payment.authorized`, `payment.captured`, `payment.failed`, `order.paid`,
  `refund.processed`, `refund.failed`. Others → `IGNORED` (recorded).
- Recorded but not interpreted (→ `IGNORED`, alert): payments with no target, partial-payment
  orders, disputes/chargebacks (`payment.dispute.*`). Each is a future adapter extension, not a
  core change.

**Razorpay dynamic QR (M2)**: `qr_code.credited` webhooks; truth from the QR code
(`GET /v1/payments/qr_codes/{id}`: fixed amount, usage, status) and the payment. `merchantOrderRef`
comes from the QR code's reference fields as returned by the API.

**Stripe (M2)** maps PaymentIntent → order, Charge → payment.
**Recharge aggregators and BBPS (M3)** implement the same interface with `TxnKind.FULFILMENT`; the
"truth" call is the status-check API.

## 11. Configuration

```yaml
aegis:
  sources:
    - id: razorpay-main
      provider: razorpay
      mode: strict                                  # strict | verify_only
      webhook-secrets: [${RZP_WEBHOOK_SECRET}, ${RZP_WEBHOOK_SECRET_PREV:}]
      api: { key-id: ${RZP_KEY_ID}, key-secret: ${RZP_KEY_SECRET}, max-concurrency: 10 }
      destinations:
        - id: orders
          url: http://orders:8080/payments/aegis
          mode: envelope                            # envelope | passthrough
          signing-secrets: [${ORDERS_SIGNING_SECRET}]
          notifications: [order.paid, order.overpaid, payment.failed, refund.processed]
```

**Fail-closed validation at startup:** unknown provider, duplicate IDs, or any secret needed by an
enabled role that is missing or under 16 characters → the application refuses to start. Empty
optional entries (the `_PREV` slot) are dropped, never treated as a valid empty secret. Validation
is role-aware: an Ingress-only container receives only webhook secrets.

## 12. Admin API (tailnet only, bearer token)

| Endpoint | Purpose |
|---|---|
| `GET /admin/orders/{sourceId}/{providerOrderId}` | Timeline: claims, transitions, notifications, deliveries |
| `GET /admin/claims?status=&since=` | Find rejected / held claims |
| `POST /admin/claims/{id}/reconfirm` | `HELD` → `CONFIRMING` |
| `POST /admin/deliveries/{id}/retry` | `DEAD` → `PENDING` |

## 13. Observability

| Metric | Alert |
|---|---|
| `aegis_ingress_requests_total{source,result}` | spike in `invalid_signature` |
| `aegis_ingress_ack_seconds` | p99 > 50 ms |
| `aegis_verify_requests_total{verdict}`, `aegis_verify_seconds` | p99 > 500 ms; any `INVALID`/`REJECTED` |
| `aegis_claims_total{source,status}` | any `REJECTED` (possible attack) |
| `aegis_confirm_seconds{source,result}` | provider error rate |
| `aegis_confirming_backlog`, `aegis_held_claims` | `HELD` > 0 |
| `aegis_recheck_overdue` | deadline passed |
| `aegis_notifications_total{type}` | any `order.overpaid`, `fulfilment.late_success` |
| `aegis_deliveries_total{destination,result}`, `aegis_delivery_lag_seconds` | `DEAD` > 0 |

JSON logs always carry `claimId`, `sourceId`, `providerOrderId`, `providerTxnId`; personal data in
bodies is masked.

## 14. Code layout

Hexagonal layout; full package map, dependency rules and patterns in
[`docs/engineering/ARCHITECTURE.md`](../engineering/ARCHITECTURE.md).

```
aegis-service/src/main/java/dev/aegis/
  domain/          targets, transactions, claims, notifications, state machines, value objects
  application/     port/in (use cases), port/out (repositories, claim log, outbox), service/
  provider/        spi/ (capabilities, registry, signature schemes), razorpay/, …
  adapter/in/      web/ (Ingress, Verify, Admin controllers), kafka/ (ClaimConsumer), scheduler/ (workers)
  adapter/out/     persistence/ (JPA), kafka/ (claim log), http/ (deliveries)
  config/          properties, roles, fail-closed validation
aegis-service/src/main/resources/db/migration/   Flyway SQL
deploy/            compose.yaml, deploy.sh, runner/
lab/               M0 Exploit Lab modules
```

## 15. Deployment (single Ubuntu host, user `aegis`)

Docker Compose: `postgres`, `kafka` (KRaft, single node), `aegis` (all roles), `prometheus`, `grafana`.

- Ingress `:8080` bound to localhost, published via Tailscale Funnel for provider sandboxes.
- Verify `:8082`, Admin `:8081`, Grafana `:3000` — tailnet only.
- **Single-broker durability:** with one broker, an acknowledged message lives on one disk and
  Kafka acks before fsync. Set `log.flush.interval.messages=1` on this host so an ack means
  "on disk". Multi-host deployments drop this and use RF 3 / `min.insync.replicas=2`.
- **Backups:** nightly `pg_dump` of PostgreSQL to a separate disk, kept 14 days, with a monthly
  restore test. Kafka needs no backup: anything not yet in PostgreSQL is still within the
  providers' retry window, and rechecks recover the rest.
- **Health:** `/actuator/health` per role; Compose restarts unhealthy containers.

## 16. Test catalogue

Each scenario is an automated test (Testcontainers Kafka + PostgreSQL, WireMock for Razorpay and
the backend) and must pass before a release.

| # | Scenario | Expected |
|---|---|---|
| T1 | Forged webhook, bad signature | `401`, nothing stored |
| T2 | Valid signature, payment doesn't exist (leaked secret) | `REJECTED (not_found)`, alert |
| T3 | Real ₹10 payment claimed for another order | `REJECTED (order_mismatch)` |
| T4 | Same webhook delivered 5× | one claim, one transition |
| T5 | Old genuine webhook replayed after dedupe expiry | `STALE`, nothing sent |
| T6 | `payment.failed` (attempt 1) after `order.paid` (attempt 2) | order stays `PAID`, no `payment.failed` sent |
| T7 | Two captured payments on one order | one `order.paid`, one `order.overpaid` |
| T8 | Route A and Route B at the same moment | one `order.paid`, same ID in both answers |
| T9 | Backend crashes after `/verify` response | `order.paid` still delivered; backend fulfils once |
| T10 | Verify with client-supplied wrong `orderId` | `INVALID` |
| T11 | Webhook secret rotation (old + new in flight) | both accepted, then old rejected after removal |
| T12 | Razorpay API down 2 h | claims `CONFIRMING`, nothing forwarded, recovery after |
| T13 | Backend down 3 h | deliveries retried, in order, all succeed after |
| T14 | Kill each role mid-flow | no loss, no duplicate notification |
| T15 | Kafka down | Ingress `503`; provider retry succeeds later |
| T16 | Late authorisation (`FAILED` → `CAPTURED`) | `order.paid` emitted |
| T17 | Passthrough destination, Route A first | legacy app still receives the webhook once |
| T18 | Empty or short secret in config | application refuses to start |
| T19 | Refund claim exceeding the captured amount | `REJECTED (refund_mismatch)` |
| T20 | Single-use QR paid twice (M2) | one `order.paid`, one `order.overpaid` |
| T21 | Multi-use QR, two payments (M2) | two `qr.payment_received`, QR stays `OPEN` |
| T22 | Fulfilment `FAILED` claim while aggregator says `PENDING` (M3) | no `fulfilment.failed`; recheck scheduled |
| T23 | Fulfilment `SUCCEEDED` after `fulfilment.failed` was sent (M3) | `fulfilment.late_success`, alert |
