# F10 — Checker: inbound checks and verdicts

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F09 |
| Design references | LLD §3 steps 6–14, §5, §9 · DECISIONS D5, D6 |

## 1. Goal
Every message that reached the ingress log gets a verdict and is stored, exactly once, with all
checks that need the records, race-free across any number of instances.

## 2. Scope
- In: `CheckerListener` (Kafka), `CheckMessageUseCase` / `CheckService`, retry-forever error
  handling, consumer settings.
- Out: forwarding (F13; the Checker only marks passed messages `PENDING`), browser callbacks (F11
  reuses this service inline).

## 3. Design
- **One transaction per record**, in this order: open the sealed body → untrusted (`BAD_SIGNATURE`)
  or unparseable (`UNPARSEABLE`) messages get a row of their own → lock the validation by provider
  reference (`FOR UPDATE`) → lock or create the payment row → `Gate.receive` (the domain decides)
  → update the validation if it became `PAID` → save the entry with its evidence.
- **Lock order** is always validation, then payment, so concurrent checks never deadlock.
- **Offsets** are committed per record after the transaction (`ack-mode: record`); a redelivery is
  found in the row and counted as a repeat (T14).
- **Failures** (e.g. PostgreSQL down): `DefaultErrorHandler` retries the record with exponential
  backoff up to 30 s, forever; the partition pauses and messages wait in Kafka (HLD §9). Nothing is
  skipped.
- **Scale:** `concurrency: 3` consumers per instance over 6 partitions; more instances share them.
- **Patterns:** Idempotent Consumer, Competing Consumers, Pessimistic locking, Ports & Adapters.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `CheckerIT` | integration (HTTP, Kafka, PostgreSQL) | T1 end to end; LLD §9 rows 3–6 (callback passes, webhook `DUPLICATE`, retries counted, late failure `DOWNGRADE`); T2, T6, T7, T8, unparseable; T14 redelivery; T15 concurrent channels → one fact |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] LLD unchanged (implemented as designed)
