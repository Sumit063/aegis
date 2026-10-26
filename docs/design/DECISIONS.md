# Aegis — Technology Decisions

Each choice: what, why, and what we rejected. Change a decision only by editing this file first.

## Stack

| Area | Choice | Why |
|---|---|---|
| Language | **Java 25 (LTS)** | The team's strongest language, so effort goes into design, not syntax. Virtual threads make blocking I/O (provider API calls, backend POSTs) cheap at high concurrency. |
| Framework | **Spring Boot 4** (Spring MVC on virtual threads) | Familiar, production-proven; MVC + virtual threads is simpler to debug than reactive WebFlux at the same throughput for this I/O pattern. |
| Build | Maven, single module | One deployable; packages separate the components. Split into modules only if a real need appears. |
| Event log | **Apache Kafka 4.x (KRaft)** | See D1. |
| System of record | **PostgreSQL 17** | See D2. |
| Cache / counters | **None in M1. Redis from M3** | Nothing in M1 needs it; dedupe is a DB unique constraint. Added only when rate limits and risk windows arrive. |
| Kafka client | Spring for Apache Kafka | Idempotent producer, manual offset commit after DB commit. |
| DB access | Spring `JdbcClient` (plain SQL) | Explicit SQL and explicit row locks (`FOR UPDATE`, `SKIP LOCKED`). JPA rejected: it hides locking and queries, which are the heart of this system. |
| Migrations | Flyway | Versioned SQL files, runs at startup. |
| HTTP client | JDK `HttpClient` | Built in, timeouts per request, works with virtual threads. |
| Config | YAML file in repo + secrets from env | Reviewable, validated at startup, fail-closed. |
| Logs / metrics | JSON logs · Micrometer + Prometheus + Grafana | Standard; tracing (OpenTelemetry) added in M2. |
| Tests | JUnit 5 + Testcontainers (Kafka, Postgres) + WireMock (provider/backend) | Tests run against real Kafka and Postgres, not mocks. |
| Deployment | Docker Compose on the Ubuntu server | One host today. Kubernetes is a non-goal until there is more than one host. |
| Public URL (dev) | Tailscale Funnel | Gives provider sandboxes a public HTTPS URL without opening ports. |

## D1 — Kafka as the ingestion log

**Decision:** Ingress writes every verified event to Kafka and acks the provider after Kafka confirms.

**Why**
- **Ingress stays independent of the database.** Postgres can be slow, migrating or down; events
  keep being accepted. The public component needs no DB credentials.
- **Absorbs bursts.** After a provider outage, retries arrive in waves; Kafka buffers them.
- **Replay and fan-out.** The same stream later feeds the risk engine (M4) and analytics/AI (M5)
  as independent consumer groups, without touching the core path.
- **Per-key ordering.** Key = `source:provider_payment_id`, so one payment's events stay in one partition.

**Settings:** idempotent producer, `acks=all`; 12 partitions; 7-day retention.
Single broker today (RF 1); multi-host = RF 3, `min.insync.replicas=2`.

## D2 — PostgreSQL holds state and scheduled work

**Decision:** Payment state, event history and the delivery queue live in Postgres.

**Why**
- State changes need **transactions and row locks** (one payment, one truth).
- Retries need **scheduling** ("try again at 10:42"). Kafka cannot delay a single message;
  a `next_attempt_at` column with `FOR UPDATE SKIP LOCKED` does it simply and safely.
- One place to answer "what happened to payment X".

## D3 — No RabbitMQ

RabbitMQ is good at task queues with per-message delays, but here it would be a **second broker**
duplicating what Kafka (log) + Postgres (scheduled work) already cover, with no replay and weaker
per-key ordering. One fewer moving part to run, monitor and reason about.

## D4 — Confirm with the provider before forwarding

Signature checks prove *who sent* the message; they do not prove the payment is real for *this
order and amount*, and they fail completely if the secret leaks or is misconfigured. Fetching the
payment from the provider API closes all of these. Cost: one API call per event, off the ack path.

## D5 — Correctness by locking, not by ordering

Kafka ordering helps, but correctness never depends on it. Each state change locks the payment
row and checks the transition, and confirmation reflects the provider's *current* truth. Redeliveries,
rebalances and replays therefore cannot produce a wrong state.

## D6 — Two forwarding modes

- **envelope** (default for new integrations): Aegis sends its own normalised JSON, signed by Aegis.
- **passthrough**: original body and headers forwarded unchanged, for existing apps (e.g. the
  WooCommerce plugins in the Exploit Lab) that expect the provider's exact format.

## D7 — Dedupe table separate from events

Deduplication uses its own small table keyed by `(source, provider_event_id)`. If `events` is later
partitioned by month, a unique constraint across partitions would have to include the partition
key, which would break deduplication. Keeping it separate avoids that redesign.

## D8 — Money and IDs

- Amounts are integers in minor units (paise/cents) + ISO currency. Never floats.
- IDs are UUIDv7 (time-ordered → good index locality, sortable).
- All timestamps are `timestamptz` in UTC.
