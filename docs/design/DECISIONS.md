# Aegis — Decisions

Each decision: what, why, and what was rejected. Change a decision only by editing this file first.

## Stack

| Area | Choice | Why |
|---|---|---|
| Language | **Java 25 (LTS)** | Mature, widely known; virtual threads make blocking I/O (provider API calls, backend POSTs) cheap at high concurrency. |
| Framework | **Spring Boot 4** (Spring MVC on virtual threads) | Production-proven; MVC + virtual threads is simpler to debug than reactive WebFlux at the same throughput for this I/O pattern. |
| Build | Maven, single module | One deployable; packages separate the components. |
| Event log | **Apache Kafka 4.x (KRaft)** | D1 |
| System of record | **PostgreSQL 17** | D2 |
| Cache / counters | **None until M4; then Redis** | Nothing before partner rate limits needs it. |
| Kafka client | Spring for Apache Kafka | Idempotent producer; manual offset commit after DB commit. |
| DB access | Spring `JdbcClient` (plain SQL) | Explicit SQL and explicit locks. JPA rejected: it hides locking and queries, the heart of this system. |
| Migrations | Flyway | Versioned SQL. |
| HTTP client | JDK `HttpClient` | Built in, per-request timeouts. |
| Config | YAML in repo + secrets from env | Reviewable; validated at startup; fail-closed. |
| Observability | JSON logs · Micrometer · Prometheus · Grafana | OpenTelemetry tracing from M2. |
| Tests | JUnit 5 · Testcontainers (Kafka, PostgreSQL) · WireMock | Real infrastructure in tests. |
| Deployment | Docker Compose, single host | Kubernetes only once there is more than one host. |
| Public URL (dev) | Tailscale Funnel | Public HTTPS for provider sandboxes without opening ports. |

## Architecture decisions

### D1 — Kafka as the ingestion log
Ingress writes each verified webhook to Kafka and acknowledges the provider only after Kafka confirms.
- Ingress stays independent of the database and needs no DB credentials.
- Absorbs retry storms after provider outages.
- Later consumers (risk engine, analytics, AI) read the same stream without touching the core.
- Settings: idempotent producer, `acks=all`, 12 partitions, 7-day retention, key = order.
- Single broker: `log.flush.interval.messages=1` so an ack means "on disk". Multi-host: RF 3,
  `min.insync.replicas=2`.

### D2 — PostgreSQL holds state and scheduled work
State changes need transactions and row locks; retries need per-row scheduling
(`next_attempt_at` + `FOR UPDATE SKIP LOCKED`), which Kafka cannot do per message.

### D3 — No RabbitMQ
It would be a second broker duplicating Kafka (log) and PostgreSQL (scheduled work), without replay.

### D4 — Claims trigger, the provider decides
No inbound message sets state directly. Every claim — webhook, verify call, recheck — makes Aegis
fetch the provider's truth; the claim is checked against it (to catch forgeries) and state moves to
what the provider says. Signatures prove *who sent* a message; confirmation proves it is *true*, and
still works when a secret is leaked or empty.

### D5 — Correctness by locking, not by ordering
Messages can be redelivered, replayed or reordered. Every change locks the order, then the
transaction (fixed lock order → no deadlocks) and checks the state machine. Kafka ordering is a
performance aid, never a correctness requirement.

### D6 — The order is the unit of fulfilment
Payments are attempts; an order can have several. Fulfilment is decided at order level:
`order.paid` on the first capture; `order.overpaid` on any further capture; `payment.failed` is sent
only while the order is still open.

### D7 — Both routes converge on one notification
Route A (`/verify`) and Route B (webhooks) run the same transition under the same locks. The
resulting `order.paid` has one ID, returned by `/verify` **and** delivered by the Deliverer, so the
backend fulfils once — even if it crashes right after the `/verify` response.

### D8 — `/verify` takes the merchant's own order ID
The client supplies only `payment_id` and `signature`. The `order_id` must come from the merchant's
database; the checkout signature binds payment to order, so a payment for a different order fails.

### D9 — One engine for payments, refunds and fulfilments
All are **transactions** with a kind-specific state machine and a provider adapter. Recharge and
bill-payment fulfilment (M3) is a new adapter, not a new system.

### D10 — Aegis never moves money
No order creation, capture, refund, recharge or settlement. Aegis decides what the merchant should
believe; the merchant acts. This keeps Aegis out of the money path: if it is down, payments still
succeed and only notifications wait.

### D11 — Re-check stuck transactions
Non-final transactions (payment `AUTHORIZED`, refund `PENDING`, fulfilment `PENDING`) are
re-confirmed on a schedule even if no callback arrives — the missing-callback case is common for
recharges.

### D12 — "Once" is enforced by the database
Notifications have `UNIQUE (type, subject_id)`. No race, retry or replay can create a second
`order.paid` for an order.

### D13 — Two forwarding modes
- **envelope** (default): Aegis's normalised JSON, signed by Aegis.
- **passthrough**: original provider webhooks for legacy apps. Every unique, confirmed,
  non-contradicted webhook is forwarded once ever — including ones that were `STALE` for Aegis,
  since the legacy app may have no other route. Only `payment.failed` for a paid order is suppressed.

### D14 — Dedupe table separate from claims
If `claims` is later partitioned by month, a cross-partition unique key would need the partition
column and would break deduplication. A separate table avoids that redesign.

### D15 — Secrets: fail-closed, rotatable, least privilege
- Missing or < 16-character secret for an enabled role → startup fails. Empty optional slots are
  dropped, never treated as a valid empty secret.
- Lists of secrets (current + previous) allow rotation without downtime, both for provider webhook
  secrets and Aegis's own signing secrets.
- Ingress holds only webhook secrets. `/verify` and Admin are never exposed publicly.

### D16 — At-least-once delivery, stated honestly
Exactly-once delivery over a network is impossible. Aegis guarantees no loss and a stable ID per
notification; the backend deduplicates on it.

### D17 — Money, IDs and time
Integer minor units + ISO currency (never floats); UUIDv7 IDs; `timestamptz` in UTC.

### D18 — Orders generalised as payment targets
The `orders` table carries `kind` (`ORDER`, `QR_CODE`) and `usage` (`SINGLE`, `MULTIPLE`) from
the first migration. Dynamic QR payments (M2) and other targets are adapter work, not schema
changes. Unsupported targets are recorded and alerted on, never silently dropped.

### D19 — Zero trust for payment messages
No message, network location or component is trusted by default: every claim is verified and
confirmed, the backend is not exposed, each role has least privilege, and failure means "hold",
never "forward". See HLD §10.

### D20 — Exploit Lab uses a reference merchant and a mock gateway
Real vulnerable plugin versions are not always still published, and a real sandbox cannot simulate
a leaked secret or a contradicting provider state. A reference merchant with one switch per flaw,
plus a Razorpay-compatible mock gateway, makes every exploit reproducible; real plugins are used
additionally where available.

## Design review log

Issues found while reviewing the design, before any code:

| # | Issue | Fix |
|---|---|---|
| R1 | Only webhooks covered; the checkout route (Route A) has its own bugs and races | Verify API (D7, D8) |
| R2 | A late `payment.failed` for attempt 1 could cancel an order paid by attempt 2 | Order-level fulfilment (D6) |
| R3 | Two captured attempts on one order would both look like "paid" | `order.overpaid` (D6) |
| R4 | Route A and Route B could each trigger fulfilment | One `order.paid` ID, DB-enforced (D7, D12) |
| R5 | Backend crash after `/verify` response could lose the fulfilment signal | `order.paid` always delivered too (D7) |
| R6 | Client-supplied `order_id` allows paying for a cheap order and claiming an expensive one | Merchant's stored order ID (D8) |
| R7 | Webhook secret rotation would reject valid webhooks or require downtime | Secret lists (D15) |
| R8 | Single Kafka broker acks before fsync; power loss could drop acknowledged events | Flush per message on single host (D1) |
| R9 | Passthrough apps could starve when Route A won the race and webhooks became `STALE` | Forward-once rule for passthrough (D13) |
| R10 | Missing callbacks would leave transactions pending forever | Scheduled re-checks (D11) |
| R11 | Confirm calls are bounded by provider API rate limits | Coalescing per order + per-provider concurrency cap |
| R12 | Merchant-to-order mapping trusted claim data | `merchantOrderRef` taken from the provider API (`receipt`) |
| R13 | Dynamic QR payments have no order and would be ignored | Payment targets (D18), QR support in M2 |
| R14 | Refund claims were not checked against the captured amount | `refund_mismatch` check |
| R15 | No backup or restore plan for PostgreSQL | Nightly dump, 14-day retention, monthly restore test |
| R16 | Disputes/chargebacks undefined | Recorded and alerted, not interpreted; future adapter work |
| R17 | Exploit Lab depended on vulnerable versions still being downloadable | Reference merchant + mock gateway (D20) |
