# F01 — Lab: mock gateway

| | |
|---|---|
| Status | done |
| Milestone | M0 |
| Depends on | F00 |
| Design references | EXPLOIT-LAB §2 · DECISIONS D19 |

## 1. Goal
A Razorpay-compatible provider the lab fully controls. It holds the real state of every order and
payment, signs exactly like Razorpay, and can create situations a real sandbox cannot (a payment
without an order, a payment made last month, a captured event in an attacker's hands).

## 2. Scope
- In: `POST /v1/orders`, `GET /v1/orders/{id}`, `GET /v1/orders/{id}/payments`, `GET /v1/payments/{id}`
  with HTTP Basic API-key authentication and Razorpay's error format; signed webhooks
  (`payment.captured`, `payment.failed`); the Checkout callback signature.
- Test-only (`/test/**`): pay as the customer, re-deliver an event, read an event's exact bytes.
- Out: refunds, QR codes, settlements, automatic webhook retries (re-delivery is explicit, so tests
  are deterministic).

## 3. Design
- Module `lab/mock-gateway`, package `dev.aegis.lab.gateway`; no dependency on Aegis.
- `Ledger`: in-memory orders and payments, owned by one account. An order payment must be for the
  order's exact amount and an order is paid once, as with Razorpay.
- `Webhooks`: builds the event JSON, signs the exact bytes with the account's webhook secret
  (`X-Razorpay-Signature`, hex HMAC-SHA256), sets `X-Razorpay-Event-Id`, delivers, and keeps every
  event for re-delivery.
- Checkout signature: `hex(HMAC-SHA256(key_secret, order_id + "|" + payment_id))`, only for a
  captured order payment.
- Accounts are a map keyed by name (`mock.accounts.<name>.*`), so the lab compose file can point one
  account's webhook at the shop and another at Aegis.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `MockGatewayTest` | Spring Boot + JDK HTTP server as the merchant | auth and error format; Checkout signature; webhook signature over the exact body and event ID; amount must match; paid once; failed payment; re-delivery and captured bytes |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] Lab README and EXPLOIT-LAB updated
