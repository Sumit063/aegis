# Aegis — Low-Level Design

Scope: M1 (Razorpay). Later providers are adapters; the engine, APIs and tables don't change.

## 1. Terms

| Term | Meaning |
|---|---|
| **Application** | The system Aegis protects (merchant backend, bill-payment app, COU). |
| **Provider** | Whoever the application pays through: Razorpay, Stripe, NPCI BBPS/UPI, an aggregator. |
| **Connection** | One configured provider account, e.g. `razorpay`. Holds the provider secrets and the application it belongs to. |
| **Validation** | Step ①: the call that fixes the amount (create order, bill fetch, validate address) and its reply. |
| **Payment message** | Anything about step ② and later: payment request, callback, webhook, async response, status-check reply. |
| **Verdict** | Aegis's decision on a message: `PASSED`, or `BLOCKED` with a reason. |
| **Outcome** | What a payment message says, in provider-neutral terms: `SUCCESS`, `FAILED`, `PENDING`, `REFUNDED`, `OTHER`. |

## 2. Endpoints

| Endpoint | Caller | Purpose |
|---|---|---|
| `POST /in/{connection}` | Provider (server to server) | Webhooks and async responses |
| `POST /in/{connection}/callback` | Customer's browser (form POST from Checkout) | Checkout callback; Aegis checks it, then redirects the browser to the application's return URL with a signed result |
| `POST /gw/{connection}/validations` | Application | Validation call, passed through to the provider |
| `POST /gw/{connection}/payments` | Application | Payment request (server-to-server providers: NPCI/BBPS) |
| `POST /gw/{connection}/status` | Application | Status check; the only way a status check ever happens |
| `GET /admin/…` | Operators (private) | Look up by reference, blocked messages, re-forward, settings |

Gateway API bodies are **provider-native**: the application sends what it would have sent to the
provider. Aegis adds authentication, signing and encryption, and returns the provider's reply plus
an `X-Aegis-Verdict` header. Public: `/in/**` only. Private (mTLS): `/gw/**`, `/admin/**`.

## 3. Inbound pipeline

```
Ingress (synchronous, before acknowledging)
  1. transport     mTLS / allow-list, if the connection requires it        → 403
  2. decrypt       if the provider encrypts                                → 400
  3. signature     adapter.verify(raw bytes, headers, secrets)             → 401 (still sent to Kafka,
                   flagged, so the Checker stores it as BLOCKED BAD_SIGNATURE)
  4. Kafka         write raw message (key = provider_ref)                 → 503 if not stored
  5. acknowledge   adapter.ack(): 2xx or <Ack/>
Checker (Kafka consumer, one database transaction per message)
  6. parse         adapter.parse() → message_id, provider_ref, payment_ref, outcome, amount, sent_at
  7. freshness     sent_at within connection.freshness (Razorpay: 25 h)  → REPLAY
  8. duplicate     message_id already stored → duplicates + 1, stop (enforced by the unique key
                   at insert: ON CONFLICT … DO UPDATE SET duplicates = duplicates + 1)
  9. lock          SELECT validation WHERE provider_ref = ? FOR UPDATE   → none: NO_VALIDATION
 10. amount        message amount/currency (when present) = validation   → AMOUNT_MISMATCH
 11. downgrade     outcome FAILED and a PASSED SUCCESS exists on it      → DOWNGRADE
 12. same fact     PASSED (validation, payment_ref, outcome) exists       → DUPLICATE
 13. store         verdict; PASSED inbound → forward_status = PENDING
 14. commit DB, then commit the Kafka offset
```

`OTHER` outcomes (events the adapter does not map, e.g. settlements) only go through steps 1–8, then
pass. Unknown connection → 404. An adapter that cannot parse a signed message → stored
`BLOCKED UNPARSEABLE` with an alert.

**Browser callback** (`/in/{connection}/callback`) runs steps 1–13 inline (no Kafka). The customer is
waiting and the browser retries nothing. It then redirects (`303`) to the application's return URL
with `ref`, `verdict` and an Aegis signature over both.

## 4. Outbound pipeline (application-initiated only)

```
  1. authenticate  mTLS client certificate + app key; connection belongs to this application → 403
  2. parse         adapter.parseRequest() → merchant_ref, amount, provider_ref
  3. checks        payment request: lock validation, must be OK, amount equal,
                   no PASSED SUCCESS and no request still in flight            → BLOCKED (409)
                   status check: within adapter.enquiryLimits()                 → RATE_LIMIT (429)
  4. sign/encrypt  adapter.prepare(request, secrets)
  5. send          to the provider, with timeout; no database transaction is open
  6. reply         verify the reply (signature or TLS), store row, return the reply + verdict
```

"In flight" = a `PASSED` payment request whose `payment_ref` has no `SUCCESS` or `FAILED` result
yet. Only an application status check, or a result arriving, clears it. Aegis never decides
on its own that a request has expired.

## 5. Block reasons

| Reason | Where |
|---|---|
| `BAD_SIGNATURE` | inbound 3, outbound 6 |
| `REPLAY` | inbound 7 |
| `NO_VALIDATION` | inbound 9, outbound 3 |
| `AMOUNT_MISMATCH` | inbound 10, outbound 3 |
| `DOWNGRADE` | inbound 11 |
| `DUPLICATE` | inbound 12 |
| `DUPLICATE_PAYMENT` | outbound 3 |
| `RATE_LIMIT` | outbound 3 |
| `UNPARSEABLE` | inbound 6 |

Message-ID duplicates are not separate rows; they increase `duplicates` on the first row.
`BAD_SIGNATURE` rows are stored under an Aegis-generated `message_id` (`unverified:<uuid>`), so a forged
message can never claim a real message ID and cause the genuine message to be dropped as a duplicate.

## 6. Provider adapter (SPI)

```java
interface ProviderAdapter {
  String id();                                                  // "razorpay"
  boolean verify(RawMessage message, SecretSet secrets);        // HMAC, checksum, XML-DSig, …
  RawMessage decrypt(RawMessage message, SecretSet secrets);    // identity if not encrypted
  ParsedMessage parse(RawMessage message);                      // ids, refs, outcome, amount, sent time
  AckResponse ack(ParsedMessage message);                       // 2xx or <Ack/>
  ParsedRequest parseRequest(OutboundCall call);                // merchant_ref, amount, kind
  PreparedCall prepare(OutboundCall call, SecretSet secrets);   // auth headers, signing, encryption
  ParsedMessage parseReply(OutboundCall call, RawMessage reply);
  EnquiryLimits enquiryLimits();                                // e.g. NPCI: wait 90 s, max 3 in 2 h
  Duration freshness();                                         // replay window
}
```

| Provider | verify | ack | Outcome mapping |
|---|---|---|---|
| Razorpay (M1) | HMAC-SHA256: webhook body with webhook secret; callback `order_id\|payment_id` with key secret | 2xx | `captured`→SUCCESS, `failed`→FAILED, `authorized`→PENDING, refund processed→REFUNDED |
| Stripe (M2) | HMAC-SHA256 over `t.body`, 5-minute tolerance | 2xx | `payment_intent.succeeded`→SUCCESS, … |
| Paytm-style (M2) | checksum with merchant key | 2xx | `TXN_SUCCESS`→SUCCESS, … |
| BBPS / UPI (M3) | XML-DSig with the switch's public certificate | `<Ack/>` | SUCCESS / FAILURE / PENDING (deemed) / reversal |

Status mapping is strict. Only the provider's final-success status maps to `SUCCESS`
(CVE-2026-41432 treated `complete` as `paid`).

## 7. Schema (PostgreSQL)

```sql
CREATE TABLE validations (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider      text        NOT NULL,          -- connection, e.g. 'razorpay'
  merchant_ref  text        NOT NULL,          -- application's own reference (receipt / bill ref)
  provider_ref  text,                          -- order_X / bill-fetch ref; NULL if the call failed
  amount        bigint      CHECK (amount >= 0),  -- minor units, from the provider's reply
  currency      char(3),
  ok            boolean     NOT NULL,          -- provider accepted the validation
  request       bytea       NOT NULL,          -- encrypted
  response      bytea,                         -- encrypted
  created_at    timestamptz NOT NULL,
  UNIQUE (provider, provider_ref)
);

CREATE TABLE payments (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_id   bigint REFERENCES validations(id),  -- NULL only when BLOCKED NO_VALIDATION
  provider        text        NOT NULL,
  type            text        NOT NULL CHECK (type IN
                  ('PAY_REQUEST','STATUS_CHECK','CALLBACK','WEBHOOK','ASYNC_RESPONSE')),
  message_id      text        NOT NULL,          -- provider's message id; Aegis id for outbound
  payment_ref     text,                          -- pay_Y / UPI txn id / BBPS txn ref
  outcome         text        CHECK (outcome IN ('SUCCESS','FAILED','PENDING','REFUNDED','OTHER')),
  amount          bigint,
  currency        char(3),
  body            bytea       NOT NULL,          -- encrypted: received message, or request sent
  reply           bytea,                         -- encrypted: synchronous reply to an outbound call
  headers         jsonb,                         -- includes the provider's signature
  verdict         text        NOT NULL CHECK (verdict IN ('PASSED','BLOCKED')),
  reason          text,                          -- block reason (§5)
  duplicates      int         NOT NULL DEFAULT 0,
  forward_status  text        CHECK (forward_status IN ('PENDING','DELIVERED','FAILED')),
  forward_attempts smallint   NOT NULL DEFAULT 0,
  next_forward_at timestamptz,
  received_at     timestamptz NOT NULL,
  UNIQUE (provider, message_id)
);
-- one forwarded fact per payment and outcome (callback and webhook report the same success)
CREATE UNIQUE INDEX payments_one_fact ON payments (validation_id, payment_ref, outcome)
  WHERE verdict = 'PASSED' AND type IN ('CALLBACK','WEBHOOK','ASYNC_RESPONSE');
CREATE INDEX payments_by_validation ON payments (validation_id);
CREATE INDEX payments_forward_due   ON payments (next_forward_at) WHERE forward_status = 'PENDING';

CREATE TABLE config (
  key text PRIMARY KEY, value jsonb NOT NULL, version int NOT NULL,
  updated_by text NOT NULL, updated_at timestamptz NOT NULL
);
```

| Rule | How it is enforced |
|---|---|
| Same message twice | `UNIQUE (provider, message_id)` |
| Same fact twice | `payments_one_fact` partial unique index |
| Checks on one validation never race | `SELECT … FOR UPDATE` on the validation row, then check and insert in one transaction |
| No network call inside a transaction | Outbound: send first, then one transaction to store. Inbound: the Checker makes no network calls. |
| Evidence is private | `request`, `response`, `body`, `reply` encrypted with AES-256-GCM (key from the environment, key id prefixed for rotation) |
| Retention | 180 days (well beyond any freshness window, so a purged message can never replay); batched delete job |

## 8. Forwarding to the application

```
POST {application.forward_url}
X-Aegis-Timestamp: 1730455201
X-Aegis-Signature: v1=hex(HMAC_SHA256(app_secret, timestamp + "." + body))
Content-Type: application/json            (or application/jose when JWE is enabled)

{ "id": "payments:4711", "verdict": "PASSED",
  "provider": "razorpay", "type": "WEBHOOK", "outcome": "SUCCESS",
  "merchantRef": "SHOP-1042", "providerRef": "order_X", "paymentRef": "pay_Y",
  "amount": 49900, "currency": "INR",
  "original": { "headers": { "X-Razorpay-Signature": "…", "X-Razorpay-Event-Id": "evt_123" },
                "body": "<exact original bytes, base64>" } }
```

- Transport: mTLS (TLS 1.3).
- The application rejects a bad signature, or a timestamp older than 5 minutes.
- `id` is stable across retries, so the application ignores repeats.
- Retries: exponential backoff with jitter, starting at 1 s and capped at 10 min, for 72 h. After that the message is
  `FAILED`, an alert fires, and it can be re-forwarded from Admin.
- Per-validation order is preserved: the Forwarder sends a validation's messages one at a time, in
  `id` order.

## 9. Example: Razorpay purchase

| # | Event | Row | Verdict |
|---|---|---|---|
| 1 | App → `/gw/razorpay/validations` (`POST /v1/orders` ₹499, receipt SHOP-1042) | `validations`: order_X, 49900, ok | — |
| 2 | Customer pays in Checkout (Aegis not involved) | | |
| 3 | Browser → `/in/razorpay/callback` (payment_id, order_id, signature) | `payments` CALLBACK SUCCESS pay_Y | PASSED, forwarded, browser redirected |
| 4 | Webhook `payment.captured` pay_Y | WEBHOOK SUCCESS | BLOCKED `DUPLICATE` (same fact as 3) |
| 5 | Same webhook retried | — | duplicates + 1 on row 4 |
| 6 | Late `payment.failed` for an earlier attempt | WEBHOOK FAILED | BLOCKED `DOWNGRADE` |
| 7 | Forged webhook, wrong signature | WEBHOOK | BLOCKED `BAD_SIGNATURE`, 401 |

## 10. Example: BBPS bill payment (Aegis at the COU)

| # | Event | Row | Verdict |
|---|---|---|---|
| 1 | COU → `/gw/bbps/validations` (bill fetch) → ₹840, ref R1 | `validations`: R1, 84000, ok | — |
| 2 | COU → `/gw/bbps/payments` (₹840, R1) | PAY_REQUEST | PASSED, sent; `<Ack/>` stored as reply |
| 3 | User retries; COU sends again | PAY_REQUEST | BLOCKED `DUPLICATE_PAYMENT` (in flight) |
| 4 | NPCI → `/in/bbps` async response PENDING | ASYNC_RESPONSE PENDING | PASSED, forwarded |
| 5 | COU → `/gw/bbps/status` after 30 s | — | BLOCKED `RATE_LIMIT` (wait ≥ 90 s) |
| 6 | COU → `/gw/bbps/status` after 2 min → SUCCESS | STATUS_CHECK SUCCESS | PASSED, reply returned |
| 7 | NPCI → `/in/bbps` SUCCESS | ASYNC_RESPONSE SUCCESS | PASSED, forwarded |

## 11. Configuration

Connections are defined in the profile YAML; secrets come only from environment variables.

```yaml
aegis:
  connections:
    razorpay:
      adapter: razorpay
      application: shop
      secrets: { api-key-id: ${RZP_KEY_ID}, api-key-secret: ${RZP_KEY_SECRET},
                 webhook: [ "${RZP_WEBHOOK_SECRET}", "${RZP_WEBHOOK_SECRET_PREVIOUS:}" ] }
  applications:
    shop:
      forward-url: https://shop.internal/aegis/messages
      return-url: https://shop.example/payment/result
      client-cert-subject: CN=shop
      signing-secret: ${SHOP_AEGIS_SECRET}
      jwe-public-key: ${SHOP_JWE_PUBLIC_KEY:}
```

- Startup fails on missing, empty or short (< 32 bytes) secrets. Empty "previous" slots are dropped.
- Runtime-tunable values (forwarding backoff, pause per application, log levels) live in `config`.
  Each instance keeps an in-memory snapshot, refreshed by `LISTEN/NOTIFY` plus a 30 s poll.
- Profiles (`local`, `dev`, `staging`, `prod`): see DECISIONS D9.

## 12. Observability

- Metrics: messages by provider, type and verdict; block reasons; acknowledgement latency; forward
  lag; forward failures.
- Alerts: any `BAD_SIGNATURE` burst, any `AMOUNT_MISMATCH` or `DOWNGRADE`, forward `FAILED`, Kafka lag.
- Logs: references only. Never bodies, secrets or customer data.

## 13. Test catalogue

| ID | Scenario | Expected |
|---|---|---|
| T1 | Valid webhook | PASSED, forwarded once |
| T2 | Bad / missing / empty-key signature | 401, BLOCKED `BAD_SIGNATURE` |
| T3 | `Host: localhost` or internal IP on a forged message | still 401 |
| T4 | Same webhook 5× | one row, `duplicates = 4`, forwarded once |
| T5 | Callback then webhook for the same payment | one forwarded, second `DUPLICATE` |
| T6 | Message older than the freshness window | `REPLAY` |
| T7 | Message for an unknown `provider_ref` | `NO_VALIDATION` |
| T8 | Amount differs from validation | `AMOUNT_MISMATCH` |
| T9 | `FAILED` after `SUCCESS` | `DOWNGRADE` |
| T10 | Second payment request while the first is in flight | `DUPLICATE_PAYMENT` |
| T11 | Status check before the provider's minimum delay / over the limit | `RATE_LIMIT`, provider not called |
| T12 | Application down for 1 h | delivered after recovery, in order |
| T13 | Kafka down at ingress | 503, provider retries, nothing lost |
| T14 | Checker crash between DB commit and offset commit | redelivery absorbed by unique keys |
| T15 | Two instances process the callback and webhook concurrently | exactly one PASSED fact |
| T16 | Application request with a wrong client certificate or for another app's connection | 403 |
| T17 | Secret rotation: previous secret still verifies until removed | PASSED |
| T18 | Missing secret at startup | startup fails |
| T19 | Aegis never calls a provider without an application request | no outbound traffic in any inbound-only test |
