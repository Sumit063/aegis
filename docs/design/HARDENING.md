# Hardening audit

An external review asked for demonstrable safety under malicious input, duplicates, reordering,
concurrency, crashes and dependency failures. This file maps each requirement to the code as built
at the end of M1, the gap, and the batch that closes it. Findings are ranked: **financial
correctness → security → data loss → operations**. Status is updated as batches land.

Batches: **H1** payment facts · **H2** database-enforced identities · **H3** abuse limits ·
**H4** callback and destinations · **H5** operations and benchmark.

## 1. Payment-state transitions

| Requirement | As built (M1) | Gap | Batch |
|---|---|---|---|
| Validation vs payment attempt vs provider transaction | `Validation` (order/bill), `Payment` (one per provider payment ID), entries (messages) | none | — |
| Capture, authorization, failure, refund, partial refund, reversal | `Outcome`: SUCCESS (capture), PENDING (authorization), FAILED, REFUNDED | done: REVERSED added; refunds carry amount and identity | H1 ✓ |
| Older attempt's failure must not undo a newer success | `DOWNGRADE` when the validation is PAID | none (tested, lab F6) | — |
| Duplicate success → no second fulfilment | same payment: `DUPLICATE`; **a second payment succeeding for an already paid validation is forwarded** | done: `DUPLICATE_PAYMENT`, alerted (`PaidTwice`), not forwarded | H1 ✓ |
| Late success not accepted blindly | SUCCESS after FAILED always passes (rank) | done: `Policy.lateSuccessAfterFailure` from the adapter | H1 ✓ |
| Refund does not overwrite the captured fact | entries are append-only history; the status column shows the latest state | done: `wasCaptured()`, `refunded()` from history | H1 ✓ |
| Partial and multiple refunds | **a second refund is blocked as `DUPLICATE`** (same status) | done: each refund a fact; total ≤ paid | H1 ✓ |
| Unknown events never authorize fulfilment | `OTHER` passes and **is forwarded** with outcome OTHER | done: `UNSUPPORTED`, not forwarded | H1 ✓ |
| Invalid transitions recorded with a reason | rank comparison → `DOWNGRADE` only | done: `Transitions`, 42 pairs tested | H1 ✓ |
| Provider specifics stay in adapters | yes (anti-corruption layer) | done | H1 ✓ |

## 2. Identities and deduplication

| Requirement | As built | Gap | Batch |
|---|---|---|---|
| Provider event identity | `X-Razorpay-Event-Id`; fallback `body:<sha256>`; callbacks `cb:<payment id>` | documented (D21) | — |
| Business-fact identity | (payment, status): same status = same fact | done | H1 ✓ |
| Merchant delivery identity | `<payment id>-<entry>`, stable across retries | none | — |
| Never dedupe by amount/order/status alone | message ID first, then fact | done | H1 ✓ |
| Database-enforced uniqueness | one row per payment (`UNIQUE`); message/fact uniqueness under the row lock | done: `payment_messages`, `payment_facts` keys (V2) | H2 ✓ |
| Attacker cannot claim a genuine event ID | unverified messages get `unverified:<uuid>` rows | none (tested) | — |
| Concurrent duplicates across consumers and channels | row locks; `CheckerIT` T15 | done: `IdentityIT` 10 concurrent consumers | H2 ✓ |

## 3. Kafka ingress contract

| Requirement | As built | Gap | Batch |
|---|---|---|---|
| Ack only after durable Kafka write | `send().get()`, `acks=all`, idempotent | none (`FailureIT` pauses Kafka) | — |
| Raw-byte signatures | `RawMessage` keeps exact bytes | none | — |
| Body limit before parsing | 64 KB read cap before anything else | none | — |
| Ambiguous publish timeout | timeout → 503 → provider retries → second copy is a repeat | documented; harmless by message ID | — |
| Offsets after DB commit; crash between | `ack-mode: record`; redelivery = repeat (T14) | none | — |
| Poison messages / dead letter | retry forever (blocks the partition) | done: outage = retry forever; bad record → `aegis.inbound.dlt` after 3, alert | H3 ✓ |
| Invalid signatures: 401 and also published | yes, unbounded | done: 401 + evidence quota 60/min per connection, 7-day retention | H3 ✓ |
| Exactly-once claims | none made; at-least-once + idempotent consumer | — | — |

## 4. PostgreSQL

| Requirement | As built | Gap | Batch |
|---|---|---|---|
| Constraints, locking, no lost updates | `UNIQUE (provider, payment_ref)`, `FOR UPDATE`, one transaction per message | done | H2 ✓ |
| Transition atomic with verdict | same transaction | none | — |
| Unbounded JSON arrays | repeats only count; unverified traffic never joins a genuine row; status checks limited | bounded by design; documented | — |
| Indexes | forward-due partial index, lookups | done | H2 ✓ |
| Migrations fresh and existing | Flyway V1 on staging | V2 backfills existing rows; applied on staging | H2 ✓ |
| Retention of evidence and rejected traffic | 180 days for all | done: 7 days | H3 ✓ |

## 5. Forwarding

| Requirement | As built | Gap | Batch |
|---|---|---|---|
| Durable after restart | queue is `payments.forward_due_at`; leases expire | none (restart = lease expiry) | — |
| Delivery state separate from payment state | in the entry's `forward` object | kept in the row; separate table only if H5 measurements need it | — |
| Backoff with jitter, max, dead letter, replay | 1 s→10 min, 72 h → FAILED, alert, Admin re-forward | none | — |
| Timeouts, bounded concurrency | 3 s / 10 s; one virtual thread per claimed payment, ≤ 50 per pass | none | — |
| No double claim | `SKIP LOCKED` + lease | none | — |
| Ack lost after merchant processed | stable `id`; merchant must be idempotent (documented) | — | — |
| Signed contract, mTLS | HMAC + timestamp, optional JWE; mTLS at proxy | documented | — |

## 6. Provider validation

| Requirement | As built | Gap | Batch |
|---|---|---|---|
| Fail closed on config | startup checks, live/test keys | none | — |
| Webhook secret separate from key secret | yes | none | — |
| Constant-time compare, rotation | yes; contract tests | none | — |
| Freshness only where the provider supports it | Razorpay `created_at`, 25 h | none | — |
| Event allowlist | mapping with default OTHER | done | H1 ✓ |
| Refund amount and identity | not modelled | done | H1 ✓ |

## 7. Browser callback

| Requirement | As built | Gap | Batch |
|---|---|---|---|
| Never trust client fields; signature | HMAC(order\|payment), same Checker path | none (lab F7, F8) | — |
| Redirect target | from configuration only (no open redirect) | none | — |
| Signed result bound and expiring | `HMAC(ref.verdict)`, no expiry, no payment binding | add payment ref and timestamp | H4 |
| Redirect alone never fulfils | documented; shop fulfils from forwards only | none | — |

## 8. Resource exhaustion and tenancy

| Requirement | As built | Gap | Batch |
|---|---|---|---|
| Size limits, timeouts | 64 KB, HTTP client timeouts | none | — |
| Per-connection rate/concurrency limits | none | done: rate 429 + in-progress 503 per connection | H3 ✓ |
| Tenant scoping on the Gateway API | connection must belong to the caller | none (T16) | — |
| Admin separate from runtime | separate key and path; operator scope is global (documented) | — | — |
| SSRF via destinations | URLs come only from operator configuration | startup policy: block metadata/link-local; explicit flag for private networks | H4 |

## 9. Operations

| Requirement | As built | Gap | Batch |
|---|---|---|---|
| Structured, redacted logs | ECS JSON; `ObservabilityIT` | none | — |
| Correlation IDs through HTTP and Kafka | ingress record ID only | MDC + header propagation | H5 |
| Metrics and alerts | verdicts, ack latency, forwards, lag | done: `aegis_dead_letters_total`, `aegis_ingress_limited_total`, alerts | H3 ✓ |
| Readiness reflects dependencies | database only | add Kafka | H5 |
| Backup, restore, replay runbook | not written | write and rehearse | H5 |

## 10. Failure-injection suite (review items 1–26)

Covered today: 1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13, 22, 14, 15 (by message ID), 16, 17, 18, 20 (lease), 21,
23 (Gateway), 25 (leases and Kafka offsets). To add: 19 (documented
merchant idempotency), 24 (H4), 26 (H5).

## 11. Performance

No throughput number is claimed. H5 adds a reproducible benchmark of the full pipeline with
p50/p95/p99, throughput and lag, including duplicate and invalid traffic.
