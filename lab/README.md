# Exploit Lab (M0)

Reproducible attacks against a realistic merchant integration, run once **directly** and once
**through Aegis**. Design: [docs/design/EXPLOIT-LAB.md](../docs/design/EXPLOIT-LAB.md).

| Part | What it is | Port |
|---|---|---|
| [`mock-gateway`](mock-gateway) | Razorpay-compatible provider: orders, payments, signed webhooks and Checkout results | 8090 |
| [`vulnshop`](vulnshop) | Reference merchant; each integration flaw F1–F10 is a switch (`shop.flaws`) | 8091 |

Everything here uses fake, lab-only secrets and runs on a private Docker network. Nothing in the
lab talks to a real gateway.

## Mock gateway

| Endpoint | Like Razorpay? | Purpose |
|---|---|---|
| `POST /v1/orders` | yes | create an order (Basic auth `key_id:key_secret`) |
| `GET /v1/orders/{id}`, `/v1/orders/{id}/payments`, `/v1/payments/{id}` | yes | read the gateway's truth |
| `POST /test/checkout` | test-only | the customer pays: returns the Checkout fields (`razorpay_payment_id`, `razorpay_order_id`, `razorpay_signature`) and delivers the signed webhook |
| `POST /test/events/{id}/redeliver` | test-only | the provider retries a webhook |
| `GET /test/events/{id}` | test-only | the exact signed bytes of an event, as an attacker who saw a log would have them |

```bash
./mvnw -pl lab/mock-gateway spring-boot:run      # http://localhost:8090
```

## Vulnshop

| Endpoint | Used when | Purpose |
|---|---|---|
| `POST /orders {"amount"}` | always | place an order; creates the gateway order (directly, or through Aegis) |
| `GET /orders/{id}` | always | the order's state and `fulfilments` (the measure of loss) |
| `POST /webhooks/razorpay` | direct | the gateway's webhook, verified by the shop |
| `POST /payment/callback?order=SHOP-n` | direct | the browser's Checkout result, verified by the shop |
| `POST /aegis/messages` | behind Aegis | messages Aegis forwarded, verified by `X-Aegis-Signature` |
| `GET /aegis/return` | behind Aegis | where Aegis redirects the browser; display only |

```bash
./mvnw -pl lab/vulnshop spring-boot:run -Dspring-boot.run.arguments=--shop.flaws=F5,F9
```
