# F09 — Ingress: signature, Kafka, acknowledgement

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F05, F08 |
| Design references | LLD §3 steps 1–5 · HLD §6, §8, §9 · DECISIONS D6 |

## 1. Goal
Answer a provider within its deadline (Razorpay: 5 s) without depending on the database, and never
acknowledge a message that is not durably stored.

## 2. Scope
- In: `POST /in/{connection}`, signature verification, the Kafka ingress log, the provider's
  acknowledgement, role-based activation.
- Out: the checks that need the database (Checker, F10), browser callbacks (F11), mTLS/allow-lists
  for providers that support them (later; Razorpay does not).

## 3. Design
- **Layers:** `IngressController` (HTTP) → `ReceiveProviderMessageUseCase` / `IngressService` →
  `IngressLogPort` (`IngressLogPublisher`, Kafka). `InboundRequest` (DTO) keeps the exact bytes and the
  headers minus `Cookie`/`Authorization`; bodies over 64 KB get 413.
- **Order of work:** decrypt → verify (any adapter exception = not verified) → seal the body with
  the evidence key → write to Kafka and wait for the broker's acknowledgement → answer.
  - verified → the adapter's `Ack` (Razorpay: 200).
  - forged → logged with `verified=false` (so the Checker stores `BLOCKED BAD_SIGNATURE`), answered
    401 with an empty body. If the log is down, a forgery is still refused, not retried.
  - log down for a genuine message → 503, so the provider retries (T13).
- **Kafka never holds clear text:** the body is sealed before it is written (invariant 8).
- **Ordering key:** `<connection>:<payment ref>` (or provider ref), so messages about one payment
  stay in one partition.
- **Producer:** `acks=all`, idempotent, `max.block.ms`/`delivery.timeout.ms` of 2–3 s so a broker
  outage turns into a fast 503 instead of a missed provider deadline. Topic `aegis.inbound`
  (6 partitions) is created at startup.
- **Roles:** `@ConditionalOnRole(INGRESS)` creates the controller only in processes running that role.
- **Patterns:** Ports & Adapters, Fail closed (`Safely`), Conditional bean per role.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `IngressServiceTest` | unit (fakes) | genuine → sealed record + ack; forged → flagged, no ack (T2); log down → 503 for genuine, 401 for forged (T13); unknown connection; adapter crash = not verified |
| `IngressIT` | integration (HTTP + Kafka) | genuine webhook acknowledged and logged sealed with its key (T1); forged with `X-Forwarded-For: 127.0.0.1` → 401 (T2, T3); 404; 413 |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] LLD §3, DECISIONS D6 updated
