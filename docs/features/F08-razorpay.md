# F08 — Razorpay adapter

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F07 |
| Design references | LLD §6, §9 · PAYMENT-FLOWS · DECISIONS D21 |

## 1. Goal
Razorpay as the first real provider: its webhooks, Checkout callbacks, orders and payment status,
expressed in Aegis's provider-neutral terms.

## 2. Scope
- In: webhook and callback verification, parsing, strict status mapping, order creation and
  payment status through the Gateway API, live/test key enforcement.
- Out: refunds API, payment links, QR codes, subscriptions (later, same adapter).

## 3. Design
| Part | Razorpay | Aegis |
|---|---|---|
| Webhook signature | `X-Razorpay-Signature` = hex HMAC-SHA256(body, webhook secret) | any configured webhook secret (rotation) |
| Webhook identity | `X-Razorpay-Event-Id` | message ID; missing → `body:<sha256>` so equal bytes are equal messages |
| Callback signature | `razorpay_signature` = HMAC(`order_id\|payment_id`, key secret) | binds the payment to its order (stops F8) |
| Callback identity | `razorpay_payment_id` | `cb:<payment id>`, outcome SUCCESS, no amount or time |
| Failed checkout | unsigned `error[...]` form | `BAD_SIGNATURE`; the failure arrives by webhook |
| Status | `captured`, `order.paid` → SUCCESS · `failed` → FAILED · `authorized` → PENDING · `refund.processed` → REFUNDED · rest → OTHER | strict: an authorized payment can still be auto-refunded |
| Refund amount | the refunded part | not compared with the order amount |
| Freshness | retries for 24 h | 25 h |
| Orders | `POST /v1/orders` (Basic key ID:key secret), `receipt` = merchant reference | validation |
| Status check | `GET /v1/payments/{id}` | application's `{"payment_id"}` |
| Keys | `rzp_live_…` / `rzp_test_…` | live only in `prod`, test everywhere else (startup check) |

Plain Java (Jackson for JSON); registered with `ServiceLoader`.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `RazorpayAdapterContractTest` | contract | all 22 shared cases on webhook and callback |
| `RazorpayAdapterTest` | unit | strict mapping table; references, amount, time; refunds; event-ID fallback; callback; swapped-order callback (F8); unsigned failure form; orders with Basic auth; status check; live/test keys |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] LLD §6, DECISIONS D21 updated
