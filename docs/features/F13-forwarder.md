# F13 — Forwarder: Aegis signature, optional JWE, retries, ordering

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F10 |
| Design references | LLD §8 · HLD §6 · DECISIONS D4, D8 |

## 1. Goal
Every passed message reaches its application exactly as the provider sent it, with Aegis's verdict,
signed by Aegis, in order, despite outages, without ever being lost.

## 2. Scope
- In: `ForwarderJob` (scheduler), `ForwardUseCase` / `ForwardService`, `ForwardRepository` on
  `payments`, `ForwardHttpClient` (JSON, HMAC signature, optional JWE).
- Out: re-forwarding a FAILED message by hand (Admin, F14); mTLS client certificates (configured at
  the TLS layer, LLD §8).

## 3. Design
- **Claim → send → record.** One short transaction claims due payments with
  `FOR UPDATE SKIP LOCKED` and pushes `forward_due_at` out by a one-minute lease, so other instances
  skip them; it commits before anything is sent (invariant 6). Each attempt is then recorded in its
  own transaction under the row lock.
- **Order:** messages of one payment go strictly by entry number; the first that is not delivered
  stops the rest. `forward_due_at` is always the time of the **first** pending message (its retry
  time, or now), so a payment is never claimed while it must wait.
- **Parallelism:** claimed payments are delivered concurrently on virtual threads
  (`Executors.newVirtualThreadPerTaskExecutor`); one slow application blocks nobody else.
- **Retries:** 1 s × 2^attempts, capped at 10 minutes, jittered to 50–100 %; after 72 hours the
  message is `FAILED` (alerted, re-forwardable from Admin).
- **Wire format (LLD §8):** `{id, verdict, provider, type, outcome, merchantRef, providerRef,
  paymentRef, amount, currency, original: {headers, body (base64 of the exact bytes)}}`; `id` =
  `<payment id>-<entry>` is stable across retries. `X-Aegis-Timestamp` + `X-Aegis-Signature:
  v1=hex(HMAC_SHA256(signing secret, timestamp + "." + body))`. With `jwe-public-key`, the body is a
  JWE (RSA-OAEP-256 + A256GCM, `application/jose`) and the signature covers the JWE.
- Only 2xx counts as delivered.
- **Patterns:** Competing Consumers (`SKIP LOCKED`), Lease, Retry with backoff and jitter,
  Ports & Adapters.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `ForwarderIT` | integration (fake application) | T1/T4: one delivery for five repeats, original bytes, signature and timestamp, DELIVERED, queue cleared; T12: outage → only the first message retried → both delivered in order after recovery; backoff schedule and 72 h give-up |
| `ForwardHttpClientTest` | unit | JWE readable only with the application's private key |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] LLD §8 updated
