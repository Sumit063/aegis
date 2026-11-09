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
| `GET/POST/PUT /admin/…` | Operators (private, admin key) | `payments/{id}`, `payments?ref=`, `validations?ref=`, `blocked`, `payments/{id}/messages/{n}/forward`, `settings`, `evidence` (sealed values per key; opens the most recent, never shows them) |

Gateway API bodies are **provider-native**: the application sends what it would have sent to the
provider. Aegis adds authentication, signing and encryption, and returns the provider's reply plus
an `X-Aegis-Verdict` header. Public: `/in/**` only. Private (mTLS): `/gw/**`, `/admin/**`.

## 3. Inbound pipeline

```
Ingress (synchronous, before acknowledging)
  1. transport     mTLS / allow-list, if the connection requires it        → 403
  2. decrypt       if the provider encrypts; failure = not verified        → 401
  3. signature     adapter.verify(raw bytes, headers, secrets)             → 401 (still sent to Kafka,
                   flagged, so the Checker stores it as BLOCKED BAD_SIGNATURE)
  4. Kafka         write the message, body sealed with the evidence key
                   (key = connection:payment_ref, so one payment stays in order) → 503 if not stored
  5. acknowledge   adapter.ack(): 2xx or <Ack/>

Bodies over 64 KB → 413. `Cookie` and `Authorization` headers are not kept. Per connection: request
rate → 429, requests in progress → 503 (before the body is read); unverified messages are logged for
evidence only within a quota (60/min), then only counted (D26).
Checker (Kafka consumer, one database transaction per message)
  6. parse         adapter.parse() → message_id, provider_ref, payment_ref, outcome, amount,
                   sent_at, fact_ref (e.g. a refund's ID)
  7. lock          SELECT validation FOR UPDATE: by provider_ref, or (a refund names only its
                   payment) through the payment already linked to a validation
                   then INSERT payment row (provider, payment_ref) ON CONFLICT DO NOTHING; lock it
  8. repeat        message_id already in payment.messages → that entry's duplicates + 1, stop
  9. freshness     sent_at within the provider's window (Razorpay: 25 h)    → REPLAY
                   sent_at beyond the provider's clock skew ahead (default 5 min) → FUTURE_TIMESTAMP
                   no sent_at on a webhook or async response                → MISSING_TIMESTAMP
                   (callbacks and status replies carry none: a callback is bound to its order by
                   its signature and deduplicated by payment; a reply answers Aegis's own call)
     supported     outcome OTHER (an event Aegis does not interpret)        → UNSUPPORTED
 10. validated     a validation exists and was accepted                      → NO_VALIDATION
     amount        message amount/currency = validation (adapters require them wherever the
                   provider's contract carries them; only e.g. a signed callback has none);
                   refunds: their own amount, all refunds ≤ what was paid     → AMOUNT_MISMATCH
 11. paid elsewhere validation PAID by another payment: SUCCESS              → DUPLICATE_PAYMENT
                   (the customer paid twice; alerted, refund it), PENDING/FAILED → DOWNGRADE
 12. transition    explicit table (domain.check.Transitions), e.g. FAILED after SUCCESS →
                   DOWNGRADE, refund before capture → INVALID_TRANSITION (a refund is a fact on a
                   captured payment, never a state), SUCCESS after FAILED only
                   if the provider documents late success (Razorpay does), the same fact again
                   (same outcome and fact_ref) → DUPLICATE; a status-check reply confirming the
                   current state is an answer, not a duplicate
 13. store         append the message to payment.messages with its verdict; if PASSED: set
                   payment.status (lifecycle only: refunds do not change it), set
                   validation.status = PAID on SUCCESS, mark for forwarding
 14. commit DB, then commit the Kafka offset
```

These rules are implemented once, in `domain.check.Gate` (F04). A repeat is detected before
freshness, so a re-delivered old message is counted instead of stored twice.

`OTHER` outcomes (events the adapter does not map, e.g. settlements) only go through steps 1–9 and are
stored as `BLOCKED UNSUPPORTED` (acknowledged to the provider, never forwarded). Unknown connection → 404. An adapter that cannot parse a signed message → stored
`BLOCKED UNPARSEABLE` with an alert.

**Browser callback** (`/in/{connection}/callback`) runs steps 1–13 inline (no Kafka). The customer is
waiting and the browser retries nothing. It then redirects (`303`) to the application's return URL
with `ref` (the validation's merchant reference), `verdict`, `payment` (Aegis's payment ID), `ts`
(epoch seconds) and `signature = hex(HMAC_SHA256(app_secret, ref + "." + verdict + "." + payment +
"." + ts))`. Applications reject results older than a few minutes. The page is display only; the
application acts on the forwarded message. The target is always the configured `return-url`.

## 4. Outbound pipeline (application-initiated only)

```
  1. authenticate  Authorization: Bearer <app key> (+ client-certificate subject if configured)
                   → 401/403; connection belongs to this application                 → 403
  2. parse         adapter.facts() → merchant_ref, amount, provider_ref, payment_ref   → 400
  3. checks        validation whose reference the application chose (NPCI): that reference
                   unused                                                     → DUPLICATE (409);
                   payment request: its payment reference unknown            → DUPLICATE_PAYMENT (409);
                   lock validation, status must be CREATED (not FAILED/PAID),
                   amount equal, no payment of it still in flight             → BLOCKED (409);
                   if allowed, the request is written as in flight in the same transaction
                   status check: within adapter.enquiryLimits()                 → RATE_LIMIT (429)
  4. sign/encrypt  adapter.prepare(request, secrets)
  5. send          to the provider (3 s connect, 10 s answer); no database transaction is open;
                   unreachable → 503
  6. reply         adapter.reply() verifies and reads it; one transaction: validation → one row
                   with request + reply; payment request → reply attached to its entry; status
                   check → the reply goes through Gate.receive and is appended. Return the
                   provider's reply unchanged + X-Aegis-Verdict: PASSED | BLOCKED <REASON>
```

"In flight" = a payment of this validation with a passed payment request and no final answer yet
(`status` empty or `PENDING`). The request's own acknowledgement is not news, so it never sets
`status`; NPCI's deemed (pending) answer still passes and is forwarded. Only an application status check, or a result arriving, clears it. Aegis never decides
on its own that a request has expired.

## 5. Block reasons

| Reason | Where |
|---|---|
| `BAD_SIGNATURE` | inbound 3, outbound 6 |
| `REPLAY` | inbound 9 |
| `FUTURE_TIMESTAMP` | inbound 9 |
| `MISSING_TIMESTAMP` | inbound 9 |
| `NO_VALIDATION` | inbound 9, outbound 3 |
| `AMOUNT_MISMATCH` | inbound 10, outbound 3 |
| `DOWNGRADE` | inbound 11, 12 |
| `DUPLICATE` | inbound 12, outbound 3 (an application-chosen validation reference reused) |
| `DUPLICATE_PAYMENT` | inbound 11 (paid twice), outbound 3 (one in flight, or a payment reference reused) |
| `INVALID_TRANSITION` | inbound 12 |
| `UNSUPPORTED` | inbound 9 |
| `RATE_LIMIT` | outbound 3 |
| `UNPARSEABLE` | inbound 6 |

A repeated message is not stored again; its entry's `duplicates` count goes up.
Messages that fail before they can be trusted (`BAD_SIGNATURE`, `UNPARSEABLE`) get **their own row**
with no validation and no `payment_ref`, so a forged message can never write into, or claim a message
ID inside, a genuine payment's row.

## 6. Provider adapter (SPI)

```java
interface ProviderAdapter {                       // package dev.aegis.provider.spi
  String id();                                    // "razorpay"
  Set<String> requiredSecrets();                  // checked at startup, e.g. [key-secret, webhook]
  Set<String> requiredSettings();                 // default none, e.g. [key-id]
  void validate(Connection c, boolean production);// default no-op, e.g. live keys only in prod
  Duration freshness();                           // replay window
  Duration maxClockSkew();                        // default 5 min: how far ahead a timestamp may be
  boolean lateSuccessAfterFailure();              // default false; Razorpay: true
  Check.Limits enquiryLimits();                   // default none; NPCI: wait 90 s, max 3 in 2 h
  // inbound
  RawMessage decrypt(Channel ch, RawMessage m, Connection c);   // default identity
  boolean verify(Channel ch, RawMessage m, Connection c);       // HMAC, checksum, XML-DSig, …
  PaymentMessage parse(Channel ch, RawMessage m, Connection c, Instant receivedAt);
                                                  // throws UnparseableMessageException, also when a
                                                  // field the provider's contract requires is missing
  Ack ack(Channel ch, RawMessage m);              // default 200; or <Ack/>
  // outbound, only for an application's request
  boolean supports(CallKind kind);                // default false; VALIDATION, PAY_REQUEST, STATUS_CHECK
  CallFacts facts(CallKind k, RawMessage request);              // merchant ref, refs, amount
  PreparedCall prepare(CallKind k, RawMessage request, Connection c);   // auth, signing, encryption
  Reply reply(CallKind k, RawMessage request, int status, RawMessage reply, Connection c, Instant at);
}
```

`Channel` is `SERVER` (webhooks, async responses) or `BROWSER` (Checkout callbacks). `RawMessage`
holds the exact bytes and headers (case-insensitive lookup). Adapters are found with
`java.util.ServiceLoader` (`META-INF/services/dev.aegis.provider.spi.ProviderAdapter`) and
`ProviderRegistry` refuses to start if a connection names a missing adapter, secret or setting.

| Provider | verify | ack | Outcome mapping |
|---|---|---|---|
| Razorpay (M1) | HMAC-SHA256: webhook body with webhook secret; callback `order_id\|payment_id` with key secret | 2xx | `payment.captured`/`order.paid`→SUCCESS, `payment.failed`→FAILED, `payment.authorized`→PENDING, `refund.processed`→REFUNDED (refund ID, amount, currency required), others→OTHER; signed callback→SUCCESS (no amount: bound to the order by its signature). Required: integer `created_at`, payment `id`, integer `amount`, `currency` — never defaulted; an order request needs `receipt`, integer `amount` and `currency` (mandatory in the Orders API). Status reply: `captured`→SUCCESS (also after partial refunds), `refunded`→REFUNDED observation of `amount_refunded` (not a refund fact) |
| Stripe (M2) | `Stripe-Signature: t=…,v1=…`: hex HMAC-SHA256 of `t.body` with the endpoint secret, any `v1` with any configured secret (rolling), other schemes ignored; no browser channel (the redirect is unsigned) | 2xx | `payment_intent.succeeded`→SUCCESS with `amount_received` (a partial capture is `AMOUNT_MISMATCH`), `payment_failed`/`canceled`→FAILED, `processing`/`amount_capturable_updated`→PENDING, `refund.created`/`refund.updated` in `succeeded`→REFUNDED (refund ID, amount, currency), others→OTHER. `sentAt` = `t` (signed per delivery); freshness 5 min; event `id` dedupes. The PaymentIntent is validation and payment (`lateSuccessAfterFailure`). Validation `POST /v1/payment_intents` (form: integer `amount`, `currency`, `metadata[order_id]`); status `GET /v1/payment_intents/{id}`: `succeeded`→SUCCESS, `canceled` or `requires_payment_method` after an error→FAILED, other open states→PENDING. Test keys (`sk_test_`/`rk_test_`) outside production |
| Cashfree (M2) | `x-webhook-signature` = base64 HMAC-SHA256 of `x-webhook-timestamp` (ms) + raw body, no separator, with the client secret (current or previous); no browser channel | 2xx | `PAYMENT_SUCCESS_WEBHOOK`→SUCCESS, `PAYMENT_FAILED_WEBHOOK` and `PAYMENT_USER_DROPPED_WEBHOOK`→FAILED (the `payment_status` must agree with the type), `REFUND_STATUS_WEBHOOK` in `SUCCESS`→REFUNDED (`cf_refund_id`), others→OTHER. Amounts are decimal major units read exactly (`1.80` → 180 paise; `1.005` refused, never rounded). `x-idempotency-key` dedupes; freshness 25 h; late success allowed. Order = validation (`order_id`, `order_amount`, `order_currency` required), `cf_payment_id` = payment. Status `GET /orders/{order_id}/payments/{cf_payment_id}`. `base-url` required: `api.cashfree.com` only in production |
| BBPS (M3) | Enveloped XML-DSig over the whole document, RSA-SHA256, against configured switch certificates (D33); Aegis signs requests with its `signing-key` | `<bbps:Ack/>` | `BillPaymentResponse`: `Successful` + `000`→SUCCESS, `Failure`→FAILED, `Pending` (deemed)→PENDING; reason and code must agree. Validation `BillFetchRequest` → `BillFetchResponse` (`BillerResponse@amount`, minor units, numeric currency); payment `BillPaymentRequest` → `Ack`; status `TxnStatusRequest` → `TxnStatusResponse`. Message ID `Head@msgId`, freshness `Head@ts`; references `refId` (bill) and `txnReferenceId` (payment). Status checks: first ≥ 90 s, ≤ 3 in 2 h (NPCI.md) |
| UPI (M3) | as BBPS | `<upi:Ack/>` | `RespPay`: `PAY` `SUCCESS`→SUCCESS, `FAILURE`→FAILED, `PENDING`/`DEEMED`→PENDING; `REVERSAL` `SUCCESS`→REVERSED (payment `orgTxnId`), other reversals→OTHER. Validation `ReqValAdd` → `RespValAdd` (amount = the application's stated amount, D34); payment `ReqPay` → `Ack`; status `ReqChkTxn` → `RespChkTxn`. Decimal amounts read exactly. Message ID `Head@msgId`; references `Txn@refId` (validation) and `Txn@id` (payment). Same status-check limits |

IDs an application sends that Aegis puts into a provider URL (status checks) must match
`[A-Za-z0-9_]{1,128}` (and `-` where the provider uses it), so a reference can never name another
provider resource.

Status mapping is strict. Only the provider's final-success status maps to `SUCCESS`
(CVE-2026-41432 treated `complete` as `paid`).

## 7. Schema (PostgreSQL)

**One row per validation, one row per payment.** Everything exchanged during that cycle is written
into the row's JSON field.

```sql
CREATE TABLE validations (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider      text        NOT NULL,          -- connection, e.g. 'razorpay'
  merchant_ref  text        NOT NULL,          -- application's own reference (receipt / bill ref)
  provider_ref  text,                          -- order_X / bill-fetch ref; NULL if the call failed
  amount        bigint      CHECK (amount >= 0),  -- minor units, from the provider's reply
  currency      char(3),
  status        text        NOT NULL CHECK (status IN ('CREATED','FAILED','PAID')),
  data          jsonb       NOT NULL,          -- the whole call: request + reply (below)
  created_at    timestamptz NOT NULL,
  updated_at    timestamptz NOT NULL,
  UNIQUE (provider, provider_ref)
);

CREATE TABLE payments (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_id   bigint REFERENCES validations(id),  -- NULL: untrusted or unmatched message
  provider        text        NOT NULL,
  payment_ref     text,                          -- pay_Y / UPI txn id / BBPS txn ref; NULL if untrusted
  status          text        CHECK (status IN ('PENDING','SUCCESS','FAILED','REVERSED','REFUNDED')),
                                                 -- lifecycle: latest PASSED state change (refunds are
                                                 -- facts in messages, V3; REFUNDED only from an
                                                 -- older version, read as SUCCESS); NULL if nothing passed
  messages        jsonb       NOT NULL DEFAULT '[]',  -- every message of this payment (below)
  forward_due_at  timestamptz,                   -- set while a passed message awaits forwarding
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL,
  UNIQUE (provider, payment_ref)
);
CREATE INDEX payments_by_validation ON payments (validation_id);
CREATE INDEX payments_forward_due   ON payments (forward_due_at) WHERE forward_due_at IS NOT NULL;

-- V2: identities the database enforces (D25)
CREATE TABLE payment_messages (provider text, message_id text, payment_id bigint REFERENCES payments
  ON DELETE CASCADE, n int, PRIMARY KEY (provider, message_id));        -- one message, once
CREATE TABLE payment_facts (payment_id bigint REFERENCES payments ON DELETE CASCADE, outcome text,
  fact_ref text DEFAULT '', n int, PRIMARY KEY (payment_id, outcome, fact_ref));  -- one fact, once

CREATE TABLE config (
  key text PRIMARY KEY, value jsonb NOT NULL, version int NOT NULL,
  updated_by text NOT NULL, updated_at timestamptz NOT NULL
);
```

`validations.data`:
```json
{ "request":  { "at": "2026-11-01T10:00:00Z", "headers": {…}, "body": "enc:v1:…" },
  "response": { "at": "2026-11-01T10:00:00.180Z", "httpStatus": 200, "body": "enc:v1:…" } }
```

`payments.messages`: one entry per distinct message, in arrival order.
```json
[ { "n": 1, "type": "CALLBACK", "messageId": "cb:pay_Y", "at": "…", "outcome": "SUCCESS",
    "verdict": "PASSED", "headers": {…}, "body": "enc:v1:…",
    "forward": { "status": "DELIVERED", "attempts": 1, "firstAt": "…", "lastAt": "…", "nextAt": null } },
  { "n": 2, "type": "WEBHOOK", "messageId": "evt_123", "at": "…", "outcome": "SUCCESS",
    "verdict": "BLOCKED", "reason": "DUPLICATE", "duplicates": 3, "headers": {…}, "body": "enc:v1:…" },
  { "n": 3, "type": "STATUS_CHECK", "messageId": "aegis:…", "at": "…", "outcome": "SUCCESS",
    "verdict": "PASSED", "request": "enc:v1:…", "reply": "enc:v1:…" } ]
```
Entry `type`: `PAY_REQUEST` (with its synchronous `reply`, e.g. `<Ack/>`), `STATUS_CHECK` (request and
reply), `CALLBACK`, `WEBHOOK`, `ASYNC_RESPONSE`.

| Rule | How it is enforced |
|---|---|
| One row per payment | `UNIQUE (provider, payment_ref)`; created with `INSERT … ON CONFLICT DO NOTHING` |
| Checks never race | The validation row is locked (`FOR UPDATE`) and the payment row is read and written in the same transaction; all checks read the locked rows |
| Same message twice | `messageId` looked up in the locked row's `messages` |
| Same fact twice | The same outcome and fact reference already passed (`payment_facts`) |
| No downgrade | `validations.status = PAID` |
| No double payment | `validations.status` must be `CREATED` and no payment of it `PENDING` |
| No network call inside a transaction | Outbound: send first, then one transaction to store. The Checker never calls the network. |
| Evidence is private | Every `body`, `request`, `reply` is AES-256-GCM ciphertext (`enc:v1:<key id>:<base64(iv ‖ ciphertext ‖ tag)>`); keys `aegis.evidence.keys`, `current` seals, all open |
| Amount | Lives on the validation only; a payment's messages are compared with it |
| Rows stay small | A payment has a handful of messages; repeats only increase a counter; bodies are capped (64 KB) |
| Retention | 180 days after a record's **last activity** (`updated_at`), never while a delivery is due; untrusted rows 7 days (RUNBOOK §5); batched delete job |

`validations.status` and `payments.status` are summaries for fast checks, not business workflows:
`CREATED → PAID` (or `FAILED` when the provider rejected the validation); a payment's status is its
lifecycle state. Refunds are facts on a captured payment: each identified refund passes once, their
sum never exceeds the paid amount (partial while less, full when equal), and the payment stays
`SUCCESS`, so a provider confirming "captured" after a partial refund is consistent.

## 8. Forwarding to the application

```
POST {application.forward_url}
X-Aegis-Timestamp: 1730455201
X-Aegis-Signature: v1=hex(HMAC_SHA256(app_secret, timestamp + "." + body))
Content-Type: application/json            (or application/jose when JWE is enabled)

{ "id": "4711-1", "verdict": "PASSED",
  "provider": "razorpay", "type": "WEBHOOK", "outcome": "SUCCESS",
  "merchantRef": "SHOP-1042", "providerRef": "order_X", "paymentRef": "pay_Y",
  "amount": 49900, "currency": "INR",
  "original": { "headers": { "X-Razorpay-Signature": "…", "X-Razorpay-Event-Id": "evt_123" },
                "body": "<exact original bytes, base64>" } }
```

- Transport: mTLS (TLS 1.3).
- The application rejects a bad signature, or a timestamp older than 5 minutes.
- `id` = payment row id + message number: stable across retries, so the application ignores repeats.
- Retries: exponential backoff with jitter, starting at 1 s and capped at 10 min, for 72 h. After that the message is
  `FAILED`, an alert fires, and it can be re-forwarded from Admin.
- Order is preserved: the Forwarder claims due payment rows (`FOR UPDATE SKIP LOCKED`, then a
  one-minute lease on `forward_due_at`, committed before sending) and sends their pending messages one
  at a time, in `n` order, stopping at the first that is not delivered. `forward_due_at` is the time
  of the first pending message (its retry time, or now) and is cleared when none remain.
- Payments are delivered in parallel on virtual threads; only 2xx counts as delivered.
- With `jwe-public-key`, the body is a JWE (RSA-OAEP-256, A256GCM, `application/jose`); the signature
  covers the JWE text.

## 9. Example: Razorpay purchase

| # | Event | Rows after | Verdict |
|---|---|---|---|
| 1 | App → `/gw/razorpay/validations` (`POST /v1/orders` ₹499, receipt SHOP-1042) | `validations` #1: order_X, 49900, CREATED, data = request + reply | — |
| 2 | Customer pays in Checkout (Aegis not involved) | | |
| 3 | Browser → `/in/razorpay/callback` (payment_id, order_id, signature) | `payments` #4711 (pay_Y): status SUCCESS, messages [1 CALLBACK]; validation PAID | PASSED, forwarded, browser redirected |
| 4 | Webhook `payment.captured` pay_Y | #4711 messages + [2 WEBHOOK] | BLOCKED `DUPLICATE` (same fact) |
| 5 | Same webhook retried 3× | #4711 entry 2 `duplicates = 3` | not stored again |
| 6 | Late `payment.failed` for earlier attempt pay_A | `payments` #4712 (pay_A): messages [1 WEBHOOK FAILED] | BLOCKED `DOWNGRADE` (validation PAID) |
| 7 | Forged webhook, wrong signature | `payments` #4713: no validation, no payment_ref, messages [1] | BLOCKED `BAD_SIGNATURE`, 401 |

## 10. Example: BBPS bill payment (Aegis at the COU)

| # | Event | Rows after | Verdict |
|---|---|---|---|
| 1 | COU → `/gw/bbps/validations` (bill fetch) → ₹840, ref R1 | `validations` #2: R1, 84000, CREATED | — |
| 2 | COU → `/gw/bbps/payments` (₹840, R1, txn T1) | `payments` #4720 (T1): PENDING, messages [1 PAY_REQUEST + `<Ack/>` reply] | PASSED, sent |
| 3 | User retries; COU sends again (txn T2) | no new payment row; request refused | BLOCKED `DUPLICATE_PAYMENT` (T1 pending), 409 |
| 4 | NPCI → `/in/bbps` async response PENDING (deemed) | #4720 + [2 ASYNC_RESPONSE PENDING] | PASSED, forwarded |
| 5 | COU → `/gw/bbps/status` after 30 s | provider not called | BLOCKED `RATE_LIMIT` (wait ≥ 90 s), 429 |
| 6 | COU → `/gw/bbps/status` after 2 min → SUCCESS | #4720 + [3 STATUS_CHECK], status SUCCESS; validation PAID | PASSED, reply returned |
| 7 | NPCI → `/in/bbps` SUCCESS | #4720 + [4 ASYNC_RESPONSE] | BLOCKED `DUPLICATE` (same fact), still Acked |

## 11. Configuration

Connections and applications are declared per environment in `aegis.yaml` (next to `.env` on the
host, template `deploy/aegis.example.yaml`, mounted read-only). Secret values are `${ENV}`
placeholders, so secrets come only from environment variables.

```yaml
aegis:
  connections:
    razorpay:
      adapter: razorpay
      application: shop
      base-url: https://api.razorpay.com
      settings: { key-id: ${RZP_KEY_ID} }
      secrets:
        key-secret: [ "${RZP_KEY_SECRET}" ]
        webhook: [ "${RZP_WEBHOOK_SECRET}", "${RZP_WEBHOOK_SECRET_PREVIOUS:}" ]   # newest first
  applications:
    shop:
      api-key: ${SHOP_AEGIS_API_KEY}
      signing-secret: ${SHOP_AEGIS_SECRET}
      forward-url: https://shop.internal/aegis/messages
      return-url: https://shop.example/payment/result
      client-cert-subject: CN=shop
      jwe-public-key: ${SHOP_JWE_PUBLIC_KEY:}
```

- Startup fails on a missing or empty secret, a provider secret shorter than 16 characters, an
  application secret shorter than 32, or a connection naming an unknown application. Empty
  "previous" slots are dropped. Signing uses the first secret of a list; verification accepts any.
- Runtime settings (today: pause forwarding per application) live in `config`, versioned with who
  changed them. Each instance keeps an immutable snapshot, reloaded at start and every 5 s.
- The Admin API needs `aegis.admin.api-key` (≥ 32 characters, generated per environment).
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
| T4 | Same webhook 5× | one message entry, `duplicates = 4`, forwarded once |
| T5 | Callback then webhook for the same payment | one forwarded, second `DUPLICATE` |
| T6 | Message older than the freshness window | `REPLAY` |
| T7 | Message for an unknown `provider_ref` | `NO_VALIDATION` |
| T8 | Amount differs from validation | `AMOUNT_MISMATCH` |
| T9 | `FAILED` after `SUCCESS` | `DOWNGRADE` |
| T10 | Second payment request while the first is in flight | `DUPLICATE_PAYMENT` |
| T11 | Status check before the provider's minimum delay / over the limit | `RATE_LIMIT`, provider not called |
| T12 | Application down for 1 h | delivered after recovery, in order |
| T13 | Kafka down at ingress | 503, provider retries, nothing lost |
| T14 | Checker crash between DB commit and offset commit | redelivery found in `messages`, counted as duplicate |
| T15 | Two instances process the callback and webhook concurrently | exactly one PASSED fact |
| T16 | Application request with a wrong client certificate or for another app's connection | 403 |
| T17 | Secret rotation: previous secret still verifies until removed | PASSED |
| T18 | Missing secret at startup | startup fails |
| T19 | Aegis never calls a provider without an application request | no outbound traffic in any inbound-only test |
