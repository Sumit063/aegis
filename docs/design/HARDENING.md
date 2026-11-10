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
| Retention of evidence and rejected traffic | 180 days for all | done: 7 days (H3); by last activity, never while due (H6) | H3 ✓ H6 ✓ |

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
| Signed result bound and expiring | `HMAC(ref.verdict)`, no expiry, no payment binding | done: `payment` + `ts` signed, 10 min at the shop | H4 ✓ |
| Redirect alone never fulfils | documented; shop fulfils from forwards only | none | — |

## 8. Resource exhaustion and tenancy

| Requirement | As built | Gap | Batch |
|---|---|---|---|
| Size limits, timeouts | 64 KB, HTTP client timeouts | none | — |
| Per-connection rate/concurrency limits | none | done: rate 429 + in-progress 503 per connection | H3 ✓ |
| Tenant scoping on the Gateway API | connection must belong to the caller | none (T16) | — |
| Admin separate from runtime | separate key and path; operator scope is global (documented) | — | — |
| SSRF via destinations | URLs come only from operator configuration | done: `Destinations` at startup and per send; https in prod | H4 ✓ |

## 9. Operations

| Requirement | As built | Gap | Batch |
|---|---|---|---|
| Structured, redacted logs | ECS JSON; `ObservabilityIT` | none | — |
| Correlation IDs through HTTP and Kafka | ingress record ID only | done: `X-Request-Id` → MDC → Kafka record → Checker logs; `X-Aegis-Message-Id` on deliveries | H5 ✓ |
| Metrics and alerts | verdicts, ack latency, forwards, lag | done: `aegis_dead_letters_total`, `aegis_ingress_limited_total`, alerts | H3 ✓ |
| Readiness reflects dependencies | database only | done: readiness = database + Kafka (`FailureIT`) | H5 ✓ |
| Backup, restore, replay runbook | not written | done: [RUNBOOK](../engineering/RUNBOOK.md); guarantees per failure and retention policy (H6); daily dumps in the stack, a weekly automated restore rehearsal and `GET /admin/evidence` (H8: the documented nightly dump had never been installed on the host) | H5 ✓ H6 ✓ H8 ✓ |

## 10. Failure-injection suite (review items 1–26)

Covered today: 1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13, 22, 24, 14, 15 (by message ID), 16, 17, 18, 20 (lease), 21,
23 (Gateway), 25 (leases and Kafka offsets). To add: 19 (documented
merchant idempotency), 26 (H5).

## 11. Performance

Done (H5): [BENCHMARK](../engineering/BENCHMARK.md), reproducible with `java lab/bench/Bench.java`.
It found and fixed a Forwarder pacing limit (end-to-end p99 in a burst 19.3 s → 3.5 s).

## 12. Still open (manual or later)

| Item | Why open | Plan |
|---|---|---|
| Merchant acknowledgement lost after processing (item 19) | outside Aegis: needs the merchant to dedupe by `id` | documented contract (LLD §8, RUNBOOK §2); the lab shop ignores an `id` it already processed (a CI failure-lab run fulfilled 4 of 544 orders twice when a cut delivery was resent) |
| Forwarder crash mid-delivery (item 20) | exercised by the Failure lab (F19: Aegis killed with SIGKILL under traffic, every order fulfilled exactly once), not by a test that stops exactly between send and record | a deterministic hook only if a regression appears |
| Multi-instance limits | per-instance by design (D26); with several Forwarder instances a payment whose next message becomes due mid-pass can be claimed by a second instance, so that message may be delivered twice (same `id`, first deliveries still in order) | a shared limiter only if real traffic needs it; keep the lease across a pass if duplicate deliveries become noisy |
| Asynchronous NPCI answers to validations and status checks | the NPCI-style contract answers bill fetch and status synchronously (NPCI.md §5); NPCI answers them asynchronously | an SPI change for asynchronous answers when a real switch is integrated |
| mTLS to merchants | configured at the TLS proxy, not in Aegis | document a reference proxy config with the first production host |

**Real Razorpay test-mode run (done, 2026-11-09).** Staging receives Razorpay test-mode webhooks
through a Cloudflare tunnel that publishes only `/in/`. An order created through
`/gw/razorpay/validations` and paid in Razorpay Checkout produced `payment.authorized` (PENDING,
passed), `payment.captured` (SUCCESS, passed, validation PAID) and `order.paid` (same fact, blocked
as a duplicate, not forwarded). A probe without a signature was blocked as `BAD_SIGNATURE`. The
restore rehearsal on that data restored both payments and opened all 8 sealed values.

## 13. Post-hardening review (H6)

A second review of `master` after H1–H5. Each finding was checked against the code and tests before
anything changed; "verified" states what the code actually did.

| # | Finding | Verified | Change | Status |
|---|---|---|---|---|
| 1 | Success events without an amount skip the amount check | yes: a payment entity without `amount` gave a null amount and `AMOUNT_MATCHES` skipped it; a missing currency defaulted to `INR`; status replies the same | Razorpay adapter requires an integer `amount` and a currency on every payment entity, refund ID/amount/currency on refunds, payment ID and amount on status replies; nothing defaulted; the SPI `parse` contract states it | H6 ✓ |
| 2 | Freshness: missing and future timestamps | yes: `FRESH` skipped a null `sentAt` and had no upper bound | Razorpay webhooks require an integer `created_at` > 0; `Policy.maxClockSkew` (SPI, default 5 min) → `FUTURE_TIMESTAMP`; both bounds inclusive; repeats still detected before freshness | H6 ✓ |
| 3 | Partial refund vs "captured" status check | yes: a refund set the status to `REFUNDED`; `SUCCESS` after `REFUNDED` was `DOWNGRADE`, so the genuine status reply was blocked; a "refunded" reply would have counted the full amount as a new refund; a re-sent capture after a refund got `DOWNGRADE` instead of `DUPLICATE` | the status is the lifecycle only; refunds are identified facts on SUCCESS with sum ≤ paid; status replies are observations (`amount_refunded`, no fact); V3 migrates stored `REFUNDED` to `SUCCESS` | H6 ✓ |
| 4 | Promises vs failure model | partly: README/HLD said "no passed message is lost", but deliveries stop after 72 h and topics have one replica. Not confirmed: the broker flushes each message before the ack, so power loss is covered | README/HLD narrowed; RUNBOOK §2 lists each failure per profile; `aegis.kafka.replicas` / `min-insync-replicas` for the production profile | H6 ✓ |
| 5 | Retention consistency | yes: purging by `created_at` could delete a payment refunded last week or with a delivery still due; key retirement ignored backups | purge by `updated_at`, skip rows with `forward_due_at`; one policy table (RUNBOOK §5); key retirement rule (RUNBOOK §6) | H6 ✓ |
| + | Found while fixing | the `payments.status` constraint did not allow `REVERSED` | V3 constraint adds REVERSED; REFUNDED stays allowed so a rollback to the previous version still writes, and is read as SUCCESS | H6 ✓ |

Re-check (H7): finding 2 was fixed only in the Razorpay adapter. The domain `FRESH` check still
passed any message without a `sentAt`, so a future adapter omitting the timestamp on a webhook would
have silently lost replay protection. H7 makes it a domain rule: webhooks and async responses
without a send time are `MISSING_TIMESTAMP`. Callbacks and status replies carry no send time by
design and skip freshness; a status reply is never dated by the payment's `created_at` (that is when
the payment was made, and would turn every check after 25 h into a `REPLAY`). Findings 1 and 3 were
re-verified against `master` and already covered by tests (H6).

Still open after H6 (in addition to §12):
- Refund events that arrive **before** the capture they belong to are blocked as `INVALID_TRANSITION`
  and stored; they are not re-evaluated when the capture arrives. A status check through Aegis
  reconciles the total; automatic re-evaluation would need a deferred-message design.
- A status reply without `order_id` cannot be matched to its validation (`NO_VALIDATION`) even when
  the payment is known; Razorpay includes `order_id` for order payments.
- The production durability profile (three brokers) is documented and configurable, not run in CI.
- Unresolved assumption: Razorpay's payment entity stays `captured` after partial refunds and becomes
  `refunded` only when fully refunded, with `amount_refunded` cumulative (Razorpay payments and refunds
  API documentation); the adapter relies on it.

### M2 provider assumptions (F17, F18)

Built from the providers' public documentation and tested against recorded-shape fixtures, not yet
against a live sandbox:
- Stripe: a refunded PaymentIntent stays `succeeded`; `refund.created`/`refund.updated` carry the
  same refund ID; `t` is re-signed per delivery (documented).
- Cashfree: a refunded payment's `payment_status` stays `SUCCESS`; `payment_amount` equals
  `order_amount` unless an offer applies (then `AMOUNT_MISMATCH`, fail closed); whether
  `x-webhook-timestamp` is re-signed per retry is not documented (the 25 h window does not depend
  on it); late success after `USER_DROPPED` is allowed by design (D31).

## 14. Code review of M1 and H1–H5 (2026-11-08)

The ingress, checker, gateway, callback and forwarder paths and their persistence were read against
the `code-review` checklist. Findings, each verified in the code:

| Finding | Severity | Status |
|---|---|---|
| The Checker judged freshness against its own clock when it reached a message, so a Kafka backlog after an outage could turn genuine messages into `REPLAY` (with a 5-minute provider window, within minutes) | major | fixed (F17): judged at Aegis's arrival time; `CheckerIT.freshnessIsJudgedAtArrivalNotAfterABacklog` |
| A status check's payment ID went into the provider URL as given (`../orders` reached another resource with the merchant's key) | major | fixed (F17) for Razorpay; Stripe and Cashfree validate IDs from the start |
| Two identical HMAC helpers | minor | merged into `Secret` (F17) |
| Several Forwarder instances can deliver a message twice (above, §12) | minor | documented; the application dedupes by `id` (RUNBOOK §2) |

Checked and found sound: acknowledgement only after the Kafka write (forgeries refused, never
stored over quota); no provider call inside a transaction (gateway reserves, sends, then records in
separate transactions); payment rows created with `ON CONFLICT DO NOTHING` and read `FOR UPDATE`;
validation rows locked before checks; secrets only in `PreparedCall` headers, which never print.

## 15. M3: NPCI-style providers (2026-11-10)

BBPS at the COU and UPI at the payer's PSP (F20–F24), against the contract in
[NPCI.md](NPCI.md) and the lab's independent switch simulator.

| Test | Result |
|---|---|
| XML security (`XmlTest`, `XmlSignatureTest`) | DOCTYPE, XXE and entity expansion refused before parsing; wrapping, ID references, extra transforms, RSA-SHA1, HMAC key confusion and `KeyInfo` keys all fail; a run without the profile check fails the reference and transform tests (the check is load-bearing) |
| Adapters (`Bbps*Test`, `Upi*Test`) | shared contract for both; every outcome, contradiction, amount format, reference rule and reply binding |
| Integration (`BbpsIT`, `UpiIT`) | LLD §10 row by row; deemed, reversal and a new payment after it; untrusted replies `BAD_SIGNATURE`; reused references refused |
| Simulator (`NpciSwitchTest`) | its own signing verifies Aegis's requests and its attack messages fail its own verifier, except the genuinely signed stale and replayed ones |
| NPCI lab (`lab/npci.sh`, CI) | 6 legitimate flows told exactly as they happened; 16 attacks and misuses stopped (N1–N14); 300 concurrent payments settled once ([NPCI.md](../../lab/NPCI.md)) |
| Failure lab with bill payments (CI) | Aegis, Kafka, PostgreSQL SIGKILLed under checkout and BBPS traffic: 644/644 orders and 1,012/1,012 bills settled exactly once ([FAILURES.md](../../lab/FAILURES.md)) |

Findings, each reproduced by a failing test before the fix:

| Finding | Severity | Status |
|---|---|---|
| The ingress rate limiter refilled from the wall clock: a step back (NTP, a resumed VM) drained a connection's budget, so genuine messages got 429 for as long as the step | major | fixed: elapsed time clamped at zero (`TokenBucketTest`); found by `BbpsIT` moving its test clock back |
| An application-chosen validation reference used twice was sent to the switch, answered, then failed to be recorded (500, reply lost) | minor | fixed: refused before the call, 409 `DUPLICATE` (`BbpsIT`, lab N13) |
| A payment request reusing a known payment reference was filed under that payment, possibly another bill's | major | fixed: 409 `DUPLICATE_PAYMENT`, never sent (`BbpsIT`, lab N14) |
| The lab's simulator had no XML signature code of its own to cross-check Aegis's | — | by design: written independently on the JDK API (D34) |

Assumptions and limits, stated in NPCI.md §5: validation and status answers are synchronous in the
contract (NPCI answers them asynchronously, §12); the UPI validation's amount is the payer's own
instruction (D34); a configured switch certificate is trusted as such (no PKI path validation); mTLS
belongs to the TLS proxy. Not run against NPCI's own UAT environment, which requires membership.

Code review of F20–F24 against the `code-review` checklist: every NPCI message takes the same
verify → Kafka → check path; the `Ack` is sent only after the Kafka write; unknown statuses are
`OTHER` (stored, never news); no key material reaches logs or exceptions; no provider call inside a
transaction. The JDK compares XML digests with `Arrays.equals`, which is fine here: the digest is of
public content, and the RSA verification is what authenticates. Minor, accepted: the `Ack`
timestamp is the system clock, not the injected one; a status reply that fails verification is
answered `BAD_SIGNATURE` but not stored as evidence (as for unparseable replies); two *concurrent*
fetches with the same new bill reference can both pass the reuse check, and the second then fails
closed (500) at the database's unique constraint.

## 16. M4: partner API (2026-11-10)

Partners calling an application's own payment API through Aegis (F25–F27, LLD §14, D35, D36).

| Test | Result |
|---|---|
| Domain and signatures (`PartnerGateTest`, `PartnerSignatureTest`) | freshness both ways and at the edges; every non-plain path (dot segments, encoded slashes, `//`, spaces, backslashes) has no operation; every repeat case; any change to method, path, time, ID or body fails the signature |
| Service (`PartnerServiceTest`) | rate store down → 503 (fail closed); application never reached → ID released, retry runs; no answer → 504 for good; an answer that cannot be recorded still reaches the partner; a database that cannot claim stops the request before the application |
| Rates (`LocalRateLimiterTest`, `RedisRateLimiterIT`) | burst, steady rate, exact retry-after, independent keys, a clock stepping back; two instances, 100 concurrent requests, one allowance (T29); Redis paused → refused in < 2 s, then recovered (T30) |
| Integration (`PartnerIT`, `PartnerRedisIT`) | T20–T28, T30 against real PostgreSQL, Kafka and Redis: upstream signature, credentials not passed on, 20 concurrent copies executed once, reuse 422, rates per partner and operation, rotation, error answers replayed, Admin shows no bodies, query never kept in clear, metrics, retention |
| Partner lab (`lab/partners.sh`, CI) | without Aegis a replay, an altered amount and a retry storm all execute (D1–D3); through two Aegis instances every attack is stopped before the shop (P1–P12), Redis and shop failures never cause a second execution (X1–X3), 500 recharges sent twice at once executed exactly once ([PARTNERS.md](../../lab/PARTNERS.md)) |
| Benchmark (`lab/bench/PartnerBench.java`) | 1,712 requests/s at concurrency 32, p99 48 ms, across two instances; retry storm (every request twice at once) 2,209 requests/s, each executed once (BENCHMARK) |

Findings, each reproduced by a failing test before the fix:

| Finding | Severity | Status |
|---|---|---|
| The public-endpoint limiter (H3) keyed its maps by the connection name in the URL, so invented names (`/in/<random>`) grew them without bound | major | fixed: names that are not configured share one key (`?`, `partner:?`) |
| The acknowledgement timer published client-side quantiles only, so the `SlowAcknowledgement` alert and the dashboard's p99 panel had no `_bucket` series and could never show anything | major | fixed: histogram buckets published (`ObservabilityIT` asserts them) |
| A partner request's query string (which can carry a phone number) was kept in clear in `partner_requests.path` | minor | fixed: only the path is kept in clear; the query is inside the sealed body's request hash |
| NPCI lab N12 read the desk before the asynchronous SUCCESS was forwarded (flaky in CI) | — | fixed: waits for the forward |

Assumptions and limits:
- No amount or value quotas per partner: they need the application's request format (a partner
  adapter). Rates count requests, not money.
- A request ID is unique per partner for the retention period (30 days); a request re-signed with
  an older ID after that runs again.
- A partner's clock must be within ±5 minutes; Aegis's answers to partners are the application's
  own, carried over TLS, not signed by Aegis.
- One Redis per environment, no replication: losing it refuses partner requests until it is back
  (fail closed, alerted); allowances start full after a restart.
- Only the application's `Content-Type` is passed back; other answer headers are not.
- A request ID still `IN_PROGRESS` after a crash is reported `OUTCOME_UNKNOWN` once the answer
  timeout passes; its row is not rewritten.
- Alerts are evaluated by Prometheus on every server; delivering them (Alertmanager, a pager) is
  not part of the stack yet.

Code review of F25–F27 against the `code-review` checklist: no partner request reaches storage or
the application before its signature verifies; nothing is written to the database before the
partner is authenticated; no network call inside a transaction; secrets, bodies and queries never
in logs (request IDs only after authentication); the claim is one statement, so instances cannot
both win; Redis is used by the partner API only. Accepted: an unknown partner name is refused
without computing an HMAC (partner names are not secrets).

