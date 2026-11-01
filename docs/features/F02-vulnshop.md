# F02 — Lab: reference shop with flaw switches

| | |
|---|---|
| Status | done |
| Milestone | M0 |
| Depends on | F01 |
| Design references | EXPLOIT-LAB §2–§3 · LLD §8 · DECISIONS D19 |

## 1. Goal
A realistic merchant integration where each known flaw (F1–F10) can be switched on alone, so every
exploit can be shown directly and then through Aegis with the shop's business code unchanged.

## 2. Scope
- In: orders created through the gateway (or Aegis), fulfilment on payment news, the direct
  Razorpay integration (webhook + Checkout callback) and the Aegis integration (forwarded messages
  + browser return page).
- Out: UI, persistence (in-memory is enough for the lab), refunds.

## 3. Design
- Module `lab/vulnshop`, package `dev.aegis.lab.shop`; no dependency on Aegis.
- `Shop.apply(PaymentEvent)`: the one business handler. Protections, each removed by one flaw:

| Protection | Removed by |
|---|---|
| Webhook signature with a configured secret; unset secret verifies nothing | F1 (empty key accepted) |
| Every webhook type verified | F2 (`order.paid` skips) |
| Webhook order found by gateway `order_id`, amount compared | F3 (`notes.shop_order`, no amount check) |
| `Host` header ignored | F4 (`localhost` skips) |
| Webhook event IDs remembered | F5 |
| A failure never cancels a paid order | F6 |
| Callback verified by `HMAC(order_id\|payment_id)` | F7 (`status=success` trusted) |
| Callback order found by the signed `order_id` | F8 (order named in the URL) |
| Callback and webhook for one payment fulfil once | F9 |
| Webhooks older than the event-ID memory refused | F10 |

- Both integrations build the same `PaymentEvent`. Directly, the shop verifies the provider's
  signatures itself; behind Aegis it verifies Aegis's `X-Aegis-Signature` (timestamp ≤ 5 min) and
  takes the order from Aegis's `merchantRef`.
- Only configuration differs between the two: `orders-url` and `authorization` (gateway API key, or
  the shop's Aegis application key), and which secrets the shop holds.
- `fulfilments` on each order is the measure of loss: an unpaid order fulfilled, or one paid order
  fulfilled more than once.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `ShopTest` | unit | business protections and F3, F5, F6, F8, F9, F10 |
| `RazorpayControllerTest` | unit | signatures and F1, F2, F4, F7 |
| `AegisControllerTest` | unit | Aegis signature, 5-minute timestamp window, return page |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] EXPLOIT-LAB and the lab README updated
