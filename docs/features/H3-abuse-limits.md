# H3 — Abuse limits

| | |
|---|---|
| Status | done |
| Depends on | H2 |
| Design references | HARDENING §3, §8, §9 · LLD §3 · DECISIONS D26 |

## 1. Goal
Hostile or broken traffic costs a bounded amount of Kafka, database, disk and threads, and can never
stop the processing of genuine messages.

## 2. Gaps closed
1. Every forged message was written to Kafka and stored for 180 days (unbounded).
2. No per-connection limit on public endpoints.
3. A record that could never be processed was retried forever, blocking its partition.

## 3. Design
| Control | Where | Behaviour |
|---|---|---|
| Request rate per connection | `IngressLimitFilter` (before the body is read) | token bucket; 429 + `Retry-After` |
| Requests in progress per connection | same | semaphore; 503 |
| Forgery evidence quota | `IngressService` | 401 always; logged as evidence up to 60/min per connection, then only counted |
| Untrusted retention | `RetentionService` | 7 days (genuine records: 180) |
| Poison records | `CheckerErrorHandling` | outage (connection/transient SQL) → retry forever; anything else → 3 attempts → `aegis.inbound.dlt` |
| Telemetry | Micrometer + alerts | `aegis_ingress_limited_total`, `aegis_dead_letters_total`; `IngressLimited`, `DeadLetters` |

Configurable: `aegis.limits.requests-per-minute` (6000), `burst` (500), `concurrent` (64),
`forgeries-per-minute` (60). Limits are per instance (D26).

## 4. Tests
| Test | Covers |
|---|---|
| `IngressServiceTest.keepsOnlyABoundedSampleOfForgeries` | 100 forgeries → 3 kept (quota 3); genuine unaffected |
| `LimitsIT` | 40 requests at burst 10 → ≥ 29 refused 429; another connection unaffected |
| `FailureIT.aPoisonRecordIsDeadLetteredAndTheRestFlows` | unreadable record → DLT; the next genuine payment is processed |
| `AdminIT.retentionDeletesOnlyOldRecords` | untrusted rows older than 7 days purged |
