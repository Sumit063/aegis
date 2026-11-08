# F18 — Cashfree adapter

| | |
|---|---|
| Status | done |
| Milestone | M2 |
| Depends on | F07 (SPI), F17 (shared HMAC on `Secret`) |
| Design references | LLD §6 · DECISIONS D31 · cashfree.com/docs/payments/online/webhooks/signature-verification, /api-reference/payments/latest (orders, payments, webhooks), /api-reference/webhooks/reliability-and-operations |

## 1. Goal
An Indian gateway whose amounts are decimal major units, behind the same engine: webhooks verified
and checked, orders and payment status through the Gateway API, amounts converted exactly.

## 2. Scope
- In: webhook verification, payment success/failed/user-dropped and refund status webhooks,
  `POST /orders` as the validation, `GET /orders/{order_id}/payments/{cf_payment_id}` as the status
  check, the sandbox/production guard.
- Out (explicitly): payment links, subscriptions, settlements, disputes and charges webhooks
  (authenticated and stored as `OTHER`), offers that change the paid amount (§5), a browser channel.

## 3. Design

### Protocol
| Concern | Cashfree | Aegis |
|---|---|---|
| Validation | `POST /orders` (JSON: `order_id`, decimal `order_amount`, `order_currency`, `customer_details`) | merchant ref = `order_id` (required: Cashfree would otherwise make one up); `order_currency` required (Cashfree defaults it to INR; Aegis never defaults); the reply's `order_id`, `order_amount`, `order_currency` fix the amount |
| Inbound authentication | `x-webhook-signature` = base64 HMAC-SHA256 of `x-webhook-timestamp` + raw body (no separator), with the client secret | every configured client secret (current, previous) tried; constant-time comparison of the base64 text |
| Acknowledgement | 2xx | SPI default |
| Message ID | `x-idempotency-key` header | `idem:<key>`; a missing key falls back to a digest of the body |
| References | `data.order.order_id`, `data.payment.cf_payment_id`; refunds `data.refund.order_id`, `cf_payment_id`, `cf_refund_id` | `providerRef` = order, `paymentRef` = payment attempt, refund `factRef` = `cf_refund_id`. IDs are strings or integers depending on the API version; both read |
| Timestamp | `x-webhook-timestamp`, milliseconds, signed | `sentAt`; freshness 25 h (default retries finish within an hour, configurable policies within a day); skew 5 min |
| Outbound auth | `x-client-id`, `x-client-secret`, `x-api-version` | setting `client-id`, secret `client-secret`, version `2025-01-01` |
| Environments | sandbox `https://sandbox.cashfree.com/pg`, production `https://api.cashfree.com/pg`; credentials look alike | `base-url` required; production must be `api.cashfree.com`, every other environment must not |

### Outcome mapping
| Webhook `type` | Required status | Outcome | Amount |
|---|---|---|---|
| `PAYMENT_SUCCESS_WEBHOOK` | `payment_status: SUCCESS` | SUCCESS | `payment_amount`, `payment_currency` |
| `PAYMENT_FAILED_WEBHOOK` | `FAILED` | FAILED | same |
| `PAYMENT_USER_DROPPED_WEBHOOK` | `USER_DROPPED` | FAILED | same |
| `REFUND_STATUS_WEBHOOK` | `refund_status: SUCCESS` | REFUNDED | `refund_amount`, `refund_currency` |
| `REFUND_STATUS_WEBHOOK` in another status, anything else | — | OTHER | — |

A payment webhook whose status contradicts its type is unparseable, never guessed. Status reply:
`SUCCESS` → SUCCESS; `FAILED`, `USER_DROPPED`, `CANCELLED`, `VOID` → FAILED; `PENDING`,
`NOT_ATTEMPTED` → PENDING; anything else OTHER.

### Exact decimals (D31)
The body is read with `USE_BIG_DECIMAL_FOR_FLOATS`, so `1.80` is the decimal 1.80, never a double.
`minor = amount × 10^digits(currency)` must be a non-negative integer: `1.005` INR is refused, not
rounded to `1.00` or `1.01`; a string amount (`"1.80"`) is refused. The signature is over the raw
bytes, so no reformatting can make `170.00` and `170` differ in verification.

### Decisions (D31)
- **Late success allowed**: dropped or failed UPI and net-banking payments can be confirmed late by
  the bank; only a genuine, signed SUCCESS for the same `cf_payment_id` passes; FAILED after SUCCESS
  is a `DOWNGRADE`.
- **The environment is the host**, because Cashfree's sandbox and live credentials cannot be told
  apart by their shape.
- **No browser channel**: the return URL carries the order unsigned.

## 4. Invariants (AGENTS.md)
1. Every check: the adapter only verifies and parses. 2–6: not affected (outbound only for `/gw`).
7. Fail closed: missing timestamp, signature, IDs, amount or currency → not verified or
   unparseable; sandbox outside production. 8: not affected.
9. Secrets only in headers of `PreparedCall` (never printed). 10. Exact decimal → `long` minor units.

## 5. Tests
| Test | Level | Covers |
|---|---|---|
| `CashfreeAdapterContractTest` | unit | the SPI contract |
| `CashfreeAdapterTest` | unit | signature vectors (timestamp signed, base64, rotated secret), mapping table, contradicting status, exact decimals (`1.8`, `2`, `2.00`, `1.005`, `-1`, `"1.80"`, JPY), IDs as strings or integers, required fields, orders and status calls, path safety, environment guard |
| `CashfreeIT` | integration | order through `/gw/cashfree/validations`; success webhook through `/in/cashfree` forwarded once; a refund passed once; a late success after a dropped payment passes |

## 6. Remaining
- **Assumption (unverified against a sandbox):** a refunded payment's `payment_status` stays
  `SUCCESS`; `payment_amount` equals `order_amount` unless an offer applies. With an offer that
  lowers the paid amount, the payment is blocked as `AMOUNT_MISMATCH` (fail closed, visible to the
  operator); supporting offers needs the offer's documented fields.
- Whether `x-webhook-timestamp` is re-signed on each retry is not documented; the 25 h window does
  not depend on it.
