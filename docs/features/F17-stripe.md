# F17 — Stripe adapter

| | |
|---|---|
| Status | done |
| Milestone | M2 |
| Depends on | F07 (SPI), F09–F12, H6, H7 |
| Design references | LLD §6 · HLD §11 · DECISIONS D30 · docs.stripe.com/webhooks, /api/payment_intents, /api/refunds |

## 1. Goal
A second gateway behind the same engine: Stripe webhooks are verified, checked and forwarded, and
applications create and check PaymentIntents through Aegis, with no change to the domain or to the
Razorpay adapter.

## 2. Scope
- In: webhook verification (`Stripe-Signature`, rolled secrets), PaymentIntent and refund events,
  `POST /v1/payment_intents` as the validation, `GET /v1/payment_intents/{id}` as the status check,
  live/test key guard.
- Out (explicitly): Checkout Sessions, Connect accounts, thin (v2) events, disputes and payouts
  (authenticated and stored as `OTHER`), a browser callback (Stripe has no signed one).

## 3. Design

### Protocol
| Concern | Stripe | Aegis |
|---|---|---|
| Validation | `POST /v1/payment_intents` (form: `amount`, `currency`, `metadata[order_id]`) | merchant ref = `metadata[order_id]`; the reply's `id` (`pi_…`), `amount`, `currency` fix the amount |
| Inbound authentication | `Stripe-Signature: t=…,v1=…[,v1=…]`; `v1` = hex HMAC-SHA256 of `t.body` with the endpoint secret | every `v1` tried against every configured secret (current, previous: Stripe signs once per active secret while rolling); `v0` and unknown schemes ignored |
| Acknowledgement | 2xx | SPI default |
| Message ID | event `id` (`evt_…`), the same across retries | `PaymentMessage.id` |
| References | PaymentIntent `id`; a refund's `payment_intent` | `providerRef` = `paymentRef` = the PaymentIntent |
| Timestamp | `t` is signed anew for each delivery attempt; `created` stays for 3 days of retries | `sentAt` = `t`; freshness = Stripe's 5-minute tolerance; skew 5 min |
| Outbound auth | `Authorization: Bearer sk_…` | secret `secret-key`; `sk_live_`/`rk_live_` only in production, `sk_test_`/`rk_test_` elsewhere |
| Status-check limits | API rate limits only | `Check.Limits.NONE` (as Razorpay) |

### Outcome mapping (webhook `type`, `data.object`)
| Event | Outcome | Amount |
|---|---|---|
| `payment_intent.succeeded` | SUCCESS | `amount_received` (a partial capture is `AMOUNT_MISMATCH`) |
| `payment_intent.payment_failed`, `payment_intent.canceled` | FAILED | `amount` |
| `payment_intent.processing`, `payment_intent.amount_capturable_updated` | PENDING | `amount` |
| `refund.created`, `refund.updated` with `status: succeeded` | REFUNDED, fact = refund `id` | refund `amount` |
| refund events in any other status, `charge.*`, disputes, everything else | OTHER | — |

Both `refund.created` and `refund.updated` may report the same succeeded refund: same refund ID,
so the second is a `DUPLICATE` and the application hears of it once. `charge.refunded` (cumulative,
no refund identity) is `OTHER` so a refund is never counted twice.

Status reply (`GET /v1/payment_intents/{id}`): `succeeded` → SUCCESS (`amount_received`; a refunded
intent stays `succeeded`, a confirmation), `canceled` → FAILED, `requires_payment_method` → FAILED
when `last_payment_error` is set, else PENDING; `processing`, `requires_capture`,
`requires_confirmation`, `requires_action` → PENDING; anything else OTHER.

### Decisions (D30)
- **The PaymentIntent is both validation and payment.** Attempts on it fail and a later one may
  succeed, so `lateSuccessAfterFailure` is true; a stale failure after success is still a
  `DOWNGRADE`. Charges are not used as payment references: a failed attempt (3-D Secure, a
  declined wallet) can have none.
- **`amount_received` for SUCCESS**, not `amount`: the money actually captured is what must match.
- **No browser channel.** The redirect after confirmation is unsigned; `verify` returns false for
  `BROWSER`, so such a request is blocked as `BAD_SIGNATURE`.
- **Path safety.** IDs placed in a provider URL must match `[A-Za-z0-9_]{1,128}`. Applied to
  Razorpay's `payment_id` too: a reference such as `../orders` must never change which provider
  resource Aegis calls with the merchant's key.

### Shared code
- `Secret.hmacSha256Hex(data)` and `Secret.signed(data, presented)` (constant time) replace two
  identical `Hmac` helpers (Razorpay adapter, callback signing); every adapter uses them.

### Data, configuration, failure behaviour
- No migration. A connection: `adapter: stripe`, `secrets: { secret-key: [...], webhook: [current,
  previous] }`, optional `base-url`.
- Failures are the engine's (HLD §9): Stripe retries webhooks for 3 days while Kafka is down; an
  outbound call that fails returns to the application.

## 4. Invariants (AGENTS.md)
1. Every message, every check: the adapter only verifies and parses; the engine runs every check.
2. Ack after Kafka: not affected. 3. Blocked never forwarded: not affected.
4. One row per payment: the PaymentIntent is the payment reference.
5. No calls on its own: outbound only for `/gw` requests. 6. No network in a transaction: not affected.
7. Fail closed: missing signature, timestamp, ID, amount or currency → not verified or unparseable;
   test keys outside production. 8. No money moved: not affected.
9. Secrets: the key is only in the `Authorization` header (`PreparedCall` never prints headers).
10. Amounts: integer minor units; the lowercase currency is upper-cased into an ISO code.

## 5. Tests
| Test | Level | Covers |
|---|---|---|
| `StripeAdapterContractTest` | unit | the SPI contract (signatures, rotation, empty key, tampering, headers) |
| `StripeAdapterTest` | unit | header parsing (several `v1`, `v0` only, missing or bad `t`), mapping table (every row), required fields, `amount_received`, refunds, status replies, key guard, path safety |
| `StripeIT` | integration | PaymentIntent through `/gw/stripe/validations`, signed webhook through `/in/stripe` → forwarded once; partial capture blocked; refund created + updated passed once; status check after refund confirms |
| `RazorpayAdapterTest.statusChecksNeedASafePaymentId` | unit | path safety for Razorpay |

## 5. Done when
- [x] All tests above pass; `./mvnw verify` green
- [x] LLD §6, DECISIONS D30 updated
- [x] Code review checklist passed
