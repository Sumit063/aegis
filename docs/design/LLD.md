# Aegis — Low-Level Design

Scope: M1 (Razorpay, integrity core). Later milestones extend this without changing it.

## 1. Components and flow

```
Ingress ──Kafka──► EventConsumer ──► events(CONFIRMING) ──► ConfirmWorker ──► payments + deliveries ──► DeliveryWorker ──► Backend
 verify             dedupe + insert                       fetch from provider      state machine          signed POST, retry
```

| Component | Role flag | Holds secrets | Touches |
|---|---|---|---|
| `IngressController` | `ingress` | provider webhook secrets | Kafka (produce) |
| `EventConsumer` | `processor` | — | Kafka (consume), Postgres |
| `ConfirmWorker` | `processor` | provider API keys | Provider API, Postgres |
| `DeliveryWorker` | `deliverer` | backend signing secrets | Backend, Postgres |
| `AdminController` | `admin` | admin token | Postgres |

Each step is **crash-safe and idempotent**: the consumer only inserts; slow work (provider calls,
backend calls) happens in workers that claim rows with a **lease**, so a crash just lets the lease
expire and another worker retries.

## 2. Ingress

`POST /in/{sourceId}`

1. Unknown source → `404`.
2. Read raw body, max 256 KB → else `413`.
3. `adapter.verifySignature(rawBody, headers, source)` — constant-time compare → else `401`.
4. `adapter.parse(...)` → `providerEventId`, `eventType`, `providerPaymentId`.
5. Produce to `aegis.events.received`, key `sourceId:providerPaymentId`, wait for broker ack.
6. Return `200`. Kafka error/timeout (2 s) → `503` (provider will retry).

Rejected requests are **not stored** (prevents storage floods) — only metrics + a sampled log line.

**Kafka message** (JSON, versioned):

```json
{ "v": 1, "ingestId": "uuidv7", "sourceId": "razorpay-main", "receivedAt": "…Z",
  "providerEventId": "evt_…", "eventType": "payment.captured", "providerPaymentId": "pay_…",
  "headers": { "x-razorpay-event-id": "…" }, "bodyBase64": "…", "remoteIp": "…" }
```

Topics: `aegis.events.received` (12 partitions, 7 d) and `aegis.events.received.dlq`
(messages the consumer cannot parse — should stay empty; alerts if not).

## 3. EventConsumer (dedupe + record)

Per message, one transaction:

```sql
INSERT INTO event_dedupe (source_id, provider_event_id, event_id)
VALUES (:src, :peid, :newId) ON CONFLICT DO NOTHING;
-- 0 rows → duplicate: UPDATE event_dedupe SET duplicate_count = duplicate_count + 1, last_seen_at = now(); done
-- 1 row  → INSERT INTO events (..., status = 'CONFIRMING', next_attempt_at = now())
```

Commit, **then** commit the Kafka offset. A crash between the two replays the message, and the
dedupe insert makes the replay a no-op.

## 4. ConfirmWorker

Loop every 250 ms (virtual threads, batch 50):

```sql
UPDATE events SET locked_until = now() + interval '30 seconds'
WHERE id IN (SELECT id FROM events
             WHERE status = 'CONFIRMING' AND next_attempt_at <= now()
               AND (locked_until IS NULL OR locked_until < now())
             ORDER BY next_attempt_at LIMIT 50 FOR UPDATE SKIP LOCKED)
RETURNING *;
```

For each claimed event (outside any transaction):
1. `adapter.fetchPayment(providerPaymentId)` → provider truth (status, amount, currency, orderId).
   Concurrency per source capped (`max_concurrency`) to respect provider rate limits.
2. Evaluate the claim (§5).
3. One transaction: lock the payment row, apply the outcome, insert deliveries, set event status.

Provider error/timeout (3 s) → `confirm_attempts += 1`, `next_attempt_at` = backoff
(5 s, 15 s, 1 m, 5 m, 15 m, then every 30 m). Still unconfirmed after 24 h → `HELD` + alert.

## 5. Claim evaluation and state machine

The webhook *claims* a state; the provider API gives the *truth*.

| Check | Fail → |
|---|---|
| Payment exists at provider | `REJECTED (not_found)` |
| Amount, currency, orderId match the claim | `REJECTED (mismatch)` |
| Provider's current status is reachable from the claimed state | `REJECTED (contradicted)` — e.g. claims `captured`, provider says `failed` |

If all pass, apply to the stored payment (`SELECT … FOR UPDATE`, created if absent):

| From | Allowed to |
|---|---|
| *(new)* | `AUTHORIZED`, `CAPTURED`, `FAILED` |
| `AUTHORIZED` | `CAPTURED`, `FAILED` |
| `FAILED` | `AUTHORIZED`, `CAPTURED` — late authorisation, only because the provider confirms it |
| `CAPTURED` | `PARTIALLY_REFUNDED`, `REFUNDED` |
| `PARTIALLY_REFUNDED` | `PARTIALLY_REFUNDED` (another refund), `REFUNDED` |
| `REFUNDED` | — (terminal) |

- Legal forward transition → update payment (`version += 1`), record transition,
  event `ACCEPTED`, create a delivery per subscribed destination.
- Payment already at or past the claimed state → event `STALE`, not forwarded.
- Anything not in the table (e.g. `CAPTURED → FAILED`) → never applied.

Razorpay mapping (M1): `payment.authorized`, `payment.captured`, `payment.failed`,
`refund.processed`. Other event types → `IGNORED` (recorded, not forwarded) until supported.

**Event statuses:** `CONFIRMING → ACCEPTED | STALE | REJECTED | IGNORED | HELD`

## 6. DeliveryWorker

Claims due deliveries with the same lease pattern, plus **per-payment FIFO**:

```sql
... WHERE d.status IN ('PENDING','RETRYING') AND d.next_attempt_at <= now()
  AND NOT EXISTS (SELECT 1 FROM deliveries e
                  WHERE e.payment_id = d.payment_id AND e.destination_id = d.destination_id
                    AND e.seq < d.seq AND e.status IN ('PENDING','RETRYING'))
```

- `2xx` → `SUCCEEDED`. `410` → `DEAD`. Anything else / timeout (10 s) → `RETRYING`.
- Backoff: `min(10 s × 2^attempt, 1 h)` ± 20 % jitter; after 20 attempts (~24 h) → `DEAD` + alert.
- Every attempt is written to `delivery_attempts`.

### Forwarded request

```
POST <destination.url>
Aegis-Event-Id: <uuid>          ← backend dedupes on this
Aegis-Timestamp: <unix seconds>
Aegis-Signature: v1=<hex HMAC-SHA256(secret, timestamp + "." + body)>
Aegis-Attempt: <n>
```

**envelope** body:

```json
{ "id": "…", "type": "payment.captured", "sourceId": "razorpay-main",
  "payment": { "providerPaymentId": "pay_…", "providerOrderId": "order_…",
               "amountMinor": 49900, "currency": "INR", "state": "CAPTURED" },
  "verification": { "signature": "valid", "confirmedWithProvider": true, "confirmedAt": "…Z" },
  "providerEvent": { …original JSON… } }
```

**passthrough**: original body and provider headers unchanged, plus the `Aegis-*` headers.

Backend rule: reject if timestamp older than 5 min or signature invalid; ignore repeated `Aegis-Event-Id`.

## 7. Database (PostgreSQL)

```sql
event_dedupe   (source_id, provider_event_id) PK, event_id, first_seen_at,
               last_seen_at, duplicate_count

events         id uuid PK, source_id, provider_event_id, event_type,
               provider_payment_id, received_at, raw_body bytea, headers jsonb,
               status, status_reason, provider_snapshot jsonb,
               confirm_attempts, next_attempt_at, locked_until, updated_at
               idx: (next_attempt_at) WHERE status = 'CONFIRMING'
               idx: (source_id, provider_payment_id), (received_at)

payments       id uuid PK, source_id, provider_payment_id, provider_order_id,
               amount_minor bigint, refunded_minor bigint, currency char(3),
               state, version int, created_at, updated_at
               UNIQUE (source_id, provider_payment_id)

payment_transitions  id bigserial PK, payment_id, event_id, from_state, to_state, at

deliveries     id uuid PK, seq bigserial UNIQUE, event_id, payment_id, destination_id,
               status, attempt_count, next_attempt_at, locked_until,
               last_http_status, last_error, created_at, updated_at
               UNIQUE (event_id, destination_id)
               idx: (next_attempt_at) WHERE status IN ('PENDING','RETRYING')
               idx: (payment_id, destination_id, seq)

delivery_attempts    id bigserial PK, delivery_id, attempt_no, started_at,
                     duration_ms, http_status, error
```

Retention (M2): raw bodies 90 d, `event_dedupe` 30 d (longer than any provider retry window).

## 8. Provider adapter

```java
public interface ProviderAdapter {
    String provider();                                            // "razorpay"
    boolean verifySignature(byte[] rawBody, HttpHeaders headers, Source source);
    ParsedEvent parse(byte[] rawBody, HttpHeaders headers);       // ids, type, claimed state/amount
    ProviderPayment fetchPayment(String providerPaymentId, Source source);
}
```

Razorpay: signature = HMAC-SHA256(raw body, webhook secret) in `X-Razorpay-Signature`;
event ID from `X-Razorpay-Event-Id`; truth from `GET /v1/payments/{id}` (basic auth key ID/secret).
Stripe (M2) plugs in as a second implementation; nothing else changes.

## 9. Configuration

```yaml
aegis:
  sources:
    - id: razorpay-main
      provider: razorpay
      mode: strict                       # strict | verify_only
      webhook-secret: ${RZP_WEBHOOK_SECRET}
      api: { key-id: ${RZP_KEY_ID}, key-secret: ${RZP_KEY_SECRET}, max-concurrency: 10 }
      destinations:
        - id: orders
          url: http://orders:8080/payments/callback
          mode: envelope                 # envelope | passthrough
          signing-secret: ${ORDERS_SIGNING_SECRET}
          events: [payment.captured, payment.failed, refund.processed]
```

Startup validation (**fail-closed**): unknown provider, duplicate IDs, or a secret needed by an
enabled role that is missing or shorter than 16 characters → the application refuses to start.
Validation is role-aware, so the ingress container is given only webhook secrets.

## 10. Admin API (Tailscale interface only, bearer token)

| Endpoint | Purpose |
|---|---|
| `GET /admin/payments/{sourceId}/{providerPaymentId}` | Full timeline: events, transitions, deliveries |
| `GET /admin/events?status=&since=` | Find rejected / held events |
| `POST /admin/events/{id}/reconfirm` | `HELD` → `CONFIRMING` |
| `POST /admin/deliveries/{id}/retry` | `DEAD` → `PENDING` |

## 11. Observability

| Metric | Why |
|---|---|
| `aegis_ingress_requests_total{source,result}` | Signature failures = attack signal |
| `aegis_ingress_ack_seconds` | G6 |
| `aegis_events_total{source,status}` | `REJECTED` > 0 alerts immediately |
| `aegis_confirm_seconds{source,result}` | Provider health |
| `aegis_confirming_backlog`, `aegis_held_events` | Stuck confirmations |
| `aegis_deliveries_total{destination,result}`, `aegis_delivery_lag_seconds` | Backend health; `DEAD` > 0 alerts |

Logs are JSON and always carry `ingestId`, `eventId`, `sourceId`, `providerPaymentId`.

## 12. Code layout

```
src/main/java/dev/aegis/
  config/     YAML binding, fail-closed validation
  ingress/    IngressController
  provider/   ProviderAdapter, razorpay/
  processor/  EventConsumer
  confirm/    ConfirmWorker, ClaimEvaluator
  payment/    PaymentStateMachine
  delivery/   DeliveryWorker, RequestSigner, Backoff
  admin/      AdminController
  store/      repositories (JdbcClient, plain SQL)
src/main/resources/db/migration/   Flyway SQL
deploy/       compose.yaml, prometheus, grafana
lab/          M0 Exploit Lab
```

## 13. Deployment (one Ubuntu server, user `aegis`)

Docker Compose: `postgres`, `kafka` (KRaft, single node), `aegis` (all roles), `prometheus`, `grafana`.
Ingress `:8080` bound to localhost and published via **Tailscale Funnel** for provider sandboxes.
Admin `:8081` and Grafana `:3000` reachable on the tailnet only.

## 14. Testing

| Level | What |
|---|---|
| Unit | Signature verification vectors, state machine table, claim evaluation, backoff |
| Integration | Testcontainers (Kafka + Postgres) + WireMock (Razorpay API, backend) |
| Failure | Kill processes mid-flow, Kafka/Postgres down, provider 5xx — assert G2/G3 invariants |
| Security | M0 Exploit Lab replayed against Aegis on every release |
| Load (M2) | k6 at 500 ev/s sustained, 2,000 ev/s burst |
