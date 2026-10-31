# Aegis — Low-Level Design

Scope: M1 (Razorpay). Later milestones add adapters and consumers without changing this core.
Vocabulary: inbound messages are **claims** (stored in `inbox`); messages to the merchant are
**notifications** (stored in `outbox`); everything that happened is an **event** (stored in `events`).

## 1. Components

```
Webhook ─► Ingress ─► Kafka ─► ClaimConsumer ─┐
                                              ├─► inbox (CONFIRMING) ─► ConfirmWorker ─► orders ─► outbox ─► DeliveryWorker ─► Backend
Backend ─► VerifyController ── inline ────────┘                              ▲   (state)  (to send)
                                     RecheckWorker ── RECHECK claims ────────┘
                     every step above also appends to ─► events (history, same DB transaction)
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
| **Order** (payment target) — table `orders` | provider order (`order_…`), or (M2) dynamic QR code (`qr_…`) | `OPEN` → `PAID` |
| **Transaction** — inside its order (`orders.transactions`) | provider payment, refund, or (M3) fulfilment | per kind, below |
| **Claim** — table `inbox` | inbound message (webhook, verify call, recheck) | `CONFIRMING → ACCEPTED \| STALE \| REJECTED \| IGNORED \| HELD` |
| **Notification** — table `outbox`, one row per destination | real state change worth telling the backend | `PENDING → SUCCEEDED \| RETRYING → DEAD` |
| **Event** — table `events` | anything that happened (append-only) | — |

An order and its transactions form **one aggregate stored in one row**: every change locks just
that row, so there is no lock ordering to get wrong and no partial update.

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

A notification's ID is **deterministic**: `UUIDv5(sourceId | type | subjectId)`. The same change always
produces the same ID, and the outbox primary key `(event_id, destination_id)` makes a second copy
impossible — "one `order.paid` per order" holds even under races between routes. `/verify` can
return the ID without reading it back.

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

One database transaction per Kafka message:

```sql
INSERT INTO inbox (..., origin = 'WEBHOOK', status = 'CONFIRMING', next_attempt_at = now())
ON CONFLICT (source_id, dedupe_key) DO UPDATE SET duplicates = inbox.duplicates + 1
RETURNING (xmax = 0) AS inserted;          -- true: new claim, false: duplicate
INSERT INTO events (type = 'claim.received' | 'claim.duplicate', ...);
```

Commit, **then** commit the Kafka offset. A replay after a crash hits the unique key and only
increments `duplicates`.

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
   `SELECT … FROM orders WHERE … FOR UPDATE` (insert if absent); move the transaction inside
   `orders.transactions` to the **provider's** state if the move is allowed; derive the order state;
   insert one `outbox` row per subscribed destination (`ON CONFLICT (event_id, destination_id) DO
   NOTHING`); append `events` for every change; set the inbox claim status (`ACCEPTED` if anything
   changed, else `STALE`).
4. Schedule a recheck if the transaction is left non-final (§7).

Provider error or timeout (3 s) → `attempts += 1`, retry at 5 s, 15 s, 1 m, 5 m, 15 m, then
every 30 m; after 24 h → `HELD` + alert.

Only one row is ever locked per transition (the order), which rules out deadlocks.

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

Every 30 s, picks orders with `next_check_at <= now()` (the earliest re-check among their unfinished
transactions; lease pattern) and inserts an inbox claim with `origin = 'RECHECK'`; the
ConfirmWorker handles it like any other claim.

| Non-final state | Recheck schedule | Deadline → alert |
|---|---|---|
| Payment `AUTHORIZED` | 5 m, 15 m, 1 h, then every 6 h | 5 days |
| Refund `PENDING` | 1 h, then every 6 h | 7 days |
| Fulfilment `PENDING` (M3) | 1 m, 2 m, 5 m, 15 m, 30 m, then hourly | 48 h |

## 8. DeliveryWorker

Claims due outbox rows with the lease pattern plus **per-order FIFO**:

```sql
... FROM outbox d WHERE d.status IN ('PENDING','RETRYING') AND d.next_attempt_at <= now()
  AND NOT EXISTS (SELECT 1 FROM outbox e
                  WHERE e.order_id = d.order_id AND e.destination_id = d.destination_id
                    AND e.seq < d.seq AND e.status IN ('PENDING','RETRYING'))
```

- `2xx` → `SUCCEEDED`. `410` → `DEAD`. Other status or timeout (10 s) → `RETRYING`.
- Backoff `min(10 s × 2^attempt, 1 h)` ± 20 % jitter; 20 attempts (~24 h) → `DEAD` + alert.
- Each attempt gets a fresh timestamp and signature, and is recorded as a `delivery.attempted`
  event (status, duration, error) in the same transaction that updates the outbox row.

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
order that is already `PAID`. Each forward is an outbox row of type `passthrough.forward` whose
subject is the provider event ID, so its deterministic ID guarantees it is forwarded once — and
because outbox rows are kept longer than inbox rows (§9), a replay after inbox expiry is still not
forwarded again.

## 9. Database (PostgreSQL)

Five tables, each with one job. Why PostgreSQL: DECISIONS D30. Why these five: D31.

| Table | Job | Written by | Read by | Kept |
|---|---|---|---|---|
| `inbox` | Every claim to check: duplicate guard + confirmation work queue + raw evidence | ClaimConsumer, Verify, Recheck | ConfirmWorker | 90 days after final |
| `orders` | Current truth: each order with its payments/refunds inside | Confirmation | Verify, Recheck, Admin | forever (small) |
| `outbox` | What the merchant must be told, per destination, until delivered | Confirmation | DeliveryWorker | 180 days after final |
| `events` | Everything that happened, append-only, for timelines and audit | every step, same transaction | Admin API | 12 months (monthly partitions) |
| `runtime_config` | Settings editable without a deploy | Admin API | every instance (in-memory) | forever |

```sql
-- inbox: one row per claim (webhook, /verify call, recheck). Duplicate guard + work queue.
CREATE TABLE inbox (
  id                 uuid        PRIMARY KEY,                -- UUIDv7
  source_id          text        NOT NULL,                   -- 'razorpay-main'
  dedupe_key         text        NOT NULL,                   -- provider event ID | 'checkout:<pay_id>' | 'recheck:<order>:<n>'
  origin             text        NOT NULL CHECK (origin IN ('WEBHOOK','VERIFY','RECHECK')),
  claim_type         text        NOT NULL,                   -- 'payment.captured', …
  provider_order_id  text,
  provider_txn_id    text,
  raw_body           bytea,                                  -- exact bytes: re-verification, passthrough, evidence
  headers            jsonb,                                  -- only the provider headers that matter
  status             text        NOT NULL CHECK (status IN ('CONFIRMING','ACCEPTED','STALE','REJECTED','IGNORED','HELD')),
  status_reason      text,                                   -- 'order_mismatch', 'not_found', …
  attempts           int         NOT NULL DEFAULT 0,
  next_attempt_at    timestamptz,
  locked_until       timestamptz,                            -- worker lease
  duplicates         int         NOT NULL DEFAULT 0,         -- how often the provider re-sent it
  received_at        timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL,
  UNIQUE (source_id, dedupe_key)
);
CREATE INDEX inbox_due ON inbox (next_attempt_at) WHERE status = 'CONFIRMING';

-- orders: one row per payment target, with its transactions inside (one aggregate, one lock).
CREATE TABLE orders (
  id                 uuid        PRIMARY KEY,
  source_id          text        NOT NULL,
  kind               text        NOT NULL CHECK (kind IN ('ORDER','QR_CODE','RECHARGE')),
  usage              text        NOT NULL CHECK (usage IN ('SINGLE','MULTIPLE')),
  provider_order_id  text        NOT NULL,                   -- order_… | qr_… | recharge request ID
  merchant_order_ref text,                                   -- merchant's receipt, read from the provider API
  amount_minor       bigint      CHECK (amount_minor >= 0),  -- paise; NULL = variable-amount QR
  currency           char(3)     NOT NULL,
  state              text        NOT NULL CHECK (state IN ('OPEN','PAID','CLOSED')),
  transactions       jsonb       NOT NULL DEFAULT '[]',      -- payments, refunds, fulfilments (shape below)
  next_check_at      timestamptz,                            -- earliest re-check among unfinished transactions
  version            int         NOT NULL,                   -- optimistic locking
  created_at         timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL,
  UNIQUE (source_id, kind, provider_order_id)
);
CREATE INDEX orders_recheck_due ON orders (next_check_at) WHERE next_check_at IS NOT NULL;

-- outbox: one row per notification per destination. Delivery queue + "exactly once" guard.
CREATE TABLE outbox (
  event_id           uuid        NOT NULL,                   -- Aegis-Event-Id = UUIDv5(source|type|subject)
  destination_id     text        NOT NULL,                   -- 'orders-backend'
  type               text        NOT NULL,                   -- 'order.paid', 'order.overpaid', 'passthrough.forward', …
  order_id           uuid        NOT NULL REFERENCES orders(id),
  payload            jsonb       NOT NULL,                   -- envelope, or {headers, bodyBase64} for passthrough
  seq                bigint      GENERATED ALWAYS AS IDENTITY, -- delivery order within an order
  status             text        NOT NULL CHECK (status IN ('PENDING','RETRYING','SUCCEEDED','DEAD')),
  attempts           int         NOT NULL DEFAULT 0,
  next_attempt_at    timestamptz NOT NULL,
  locked_until       timestamptz,
  last_http_status   int,
  last_error         text,
  created_at         timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL,
  PRIMARY KEY (event_id, destination_id)
);
CREATE INDEX outbox_due   ON outbox (next_attempt_at) WHERE status IN ('PENDING','RETRYING');
CREATE INDEX outbox_order ON outbox (order_id, destination_id, seq);

-- events: append-only history. Written in the same transaction as the change it describes.
CREATE TABLE events (
  id                 bigint      GENERATED ALWAYS AS IDENTITY,
  occurred_at        timestamptz NOT NULL,
  type               text        NOT NULL,                   -- see event types below
  source_id          text,
  provider_order_id  text,
  provider_txn_id    text,
  inbox_id           uuid,
  actor              text        NOT NULL,                   -- 'system' | 'admin:<name>' | 'verify'
  data               jsonb       NOT NULL,                   -- details: provider answer, from/to state, HTTP status, old/new config
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);                          -- one partition per month
CREATE INDEX events_by_order ON events (source_id, provider_order_id, occurred_at);
CREATE INDEX events_by_txn   ON events (provider_txn_id, occurred_at);
CREATE INDEX events_by_type  ON events (type, occurred_at);

-- runtime_config: settings editable through the Admin API (LLD 11.2). History lives in events.
CREATE TABLE runtime_config (
  key                text        PRIMARY KEY,                -- 'delivery.backoff', 'destination.orders-backend.paused'
  value              jsonb       NOT NULL,
  version            int         NOT NULL,
  updated_by         text        NOT NULL,
  updated_at         timestamptz NOT NULL
);
```

**`orders.transactions` element** (validated by the domain model, mapped by JPA as JSON):

```json
{ "kind": "PAYMENT", "id": "pay_Q7x…", "parentId": null, "state": "CAPTURED",
  "amountMinor": 49900, "refundedMinor": 0, "currency": "INR", "method": "upi",
  "nextCheckAt": null, "checks": 0, "updatedAt": "…Z" }
```

**Event types:** `claim.received`, `claim.duplicate`, `claim.accepted`, `claim.stale`,
`claim.rejected`, `claim.ignored`, `claim.held`, `verify.answered`, `order.created`,
`order.state_changed`, `transaction.state_changed`, `notification.created`, `delivery.attempted`,
`delivery.succeeded`, `delivery.dead`, `recheck.scheduled`, `config.changed`, `admin.action`.
Rejected webhooks (bad signature) are **not** stored — they would let anyone fill the disk; they
are counted in metrics instead.

**Retention** (daily job): inbox rows 90 days after reaching a final status (long past any provider
retry window; an older replay still meets the state machine and is `STALE`); outbox rows 180 days
after `SUCCEEDED`/`DEAD` (longer than inbox, so passthrough replays stay blocked); events by
dropping monthly partitions older than 12 months.

Amounts are `bigint` minor units. IDs are UUIDv7 except deterministic outbox IDs. Timestamps are
`timestamptz` (UTC). Database roles: the application user owns these tables; Admin reads through
a read-only role.

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

### 11.1 Environments and profiles

| Profile | Environment | Branch | Gateway keys | Defaults allowed |
|---|---|---|---|---|
| `local` | Developer machine | any | sandbox | Yes — local containers only |
| `dev` | Development on developer machines | `dev` | sandbox | No |
| `staging` | Pre-production on the Ubuntu host | `staging` | sandbox | No |
| `prod` | Production (host to be added) | `master` | **live** | No |

- `application.yaml` holds shared settings; `application-<profile>.yaml` holds what differs.
  Selected with `SPRING_PROFILES_ACTIVE`; a laptop with none set runs `local`.
- **Secrets are never in profile files.** Each environment's own keys live in that environment's
  `.env` on its host (or a secret manager later) and reach the app as environment variables.
  Profiles only reference them (`${RZP_KEY_SECRET}`) — with no default outside `local`, so a
  missing secret stops startup.
- **Key/environment guard (F06):** `prod` refuses gateway test keys (`rzp_test_…`); `local`, `dev`
  and `staging` refuse live keys (`rzp_live_…`). Prevents test traffic in production and real
  money in test environments.

### 11.2 Runtime configuration (F18)

Some settings must change **without a deployment** (an operator pauses a destination, slows
retries during a backend incident, tightens a rate limit). These live in PostgreSQL, are edited
through the Admin API, and every instance keeps an in-memory copy that is refreshed automatically.

**What is runtime-editable, and what is not**

| Runtime (database, Admin API) | Static (profile + environment variables, needs restart) |
|---|---|
| Retry/backoff schedules, timeouts, recheck schedules | Database, Kafka and network addresses |
| Rate limits, provider concurrency caps | Roles of a process |
| Destination enabled/paused, notification subscriptions | **All secrets and keys** |
| Feature flags, alert thresholds | Strict vs `verify_only` mode (security posture) |
| Log levels | Anything that would weaken verification |

Secrets are never runtime-editable: a compromised admin token must not be able to swap a webhook
secret or point deliveries at an attacker.

**How it works**

```
 Admin API ──PUT /admin/config/{key} (expected version)──► validate ► DB transaction:
                                                             runtime_config (version + 1)
                                                             events: config.changed (old → new, who)
                                                             NOTIFY aegis_config
 every instance:  LISTEN aegis_config ──► reload changed keys ──► swap immutable snapshot
                  + poll every 30 s (missed notifications)  ──► same
 code reads:      RuntimeConfigPort.get(RetryPolicyKey) → in-memory snapshot, no DB call
```

- **Typed keys:** each key is declared once in code (`ConfigKey<T>`: name, type, default,
  validator, description). Unknown keys and invalid values are rejected at the API.
- **Snapshot pattern:** readers see one immutable `RuntimeConfigSnapshot` swapped atomically; a
  request never sees half an update.
- **Observer:** components that need to react (e.g. the delivery worker's concurrency) subscribe
  to key changes.
- **Optimistic concurrency:** edits carry the expected version; a stale edit is rejected (`409`).
- **Audit and rollback:** every change is a `config.changed` event (old value, new value, who, why);
  `POST /admin/config/{key}/rollback?version=n` restores a value from that history.
- **Failure:** if the database is unreachable, instances keep their last snapshot; on a fresh start
  with no database, the service does not start anyway (it needs the database).
- **Per environment:** each environment has its own database, so its own runtime configuration.

## 12. Admin API (tailnet only, bearer token)

| Endpoint | Purpose |
|---|---|
| `GET /admin/orders/{sourceId}/{providerOrderId}` | Timeline from `events`: claims, provider answers, state changes, notifications, delivery attempts |
| `GET /admin/claims?status=&since=` | Find rejected / held claims |
| `POST /admin/claims/{id}/reconfirm` | `HELD` → `CONFIRMING` |
| `POST /admin/outbox/{eventId}/{destinationId}/retry` | `DEAD` → `PENDING` |
| `GET /admin/config` | All runtime keys: value, default, version, description |
| `PUT /admin/config/{key}` | Change a runtime key (body: value + expected version) |
| `GET /admin/config/{key}/history` | Audit trail |
| `POST /admin/config/{key}/rollback?version=n` | Restore an earlier value |

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
  adapter/out/     persistence/ (JPA), kafka/ (claim log), http/ (outbound deliveries)
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
