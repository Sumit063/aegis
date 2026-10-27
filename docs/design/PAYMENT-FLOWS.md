# Payment Flows — Where Aegis Stands

How an online payment actually works, the terms used throughout the design, and the exact points
where Aegis takes part. Razorpay is used as the example; Cashfree, PayU and PhonePe PG follow the
same shape, and Stripe differs only in names (PaymentIntent instead of Order).

## 1. Terms

| Term | Meaning | Who does it |
|---|---|---|
| **Order** | The merchant asks the gateway to expect a payment of a fixed amount. Returns `order_id`. Fixing the amount server-side stops the browser from changing it. | Merchant backend → Razorpay |
| **Checkout** | The payment screen (Razorpay's UI) where the customer picks UPI/card/netbanking and pays. | Razorpay, inside the merchant's app/site |
| **Payment** | One attempt to pay an order. An order can have several (first UPI attempt fails, second succeeds). | Razorpay |
| **Authorize** | The bank has approved and blocked the money. | Bank / card network / NPCI |
| **Capture** | The merchant claims the authorized money. Usually automatic. | Razorpay (auto) or merchant |
| **Verify** | Checking a *signature* to prove a message was produced by Razorpay. Two kinds: **checkout verification** (Route A) and **webhook verification** (Route B). | Merchant backend — or Aegis |
| **Confirm** | Asking Razorpay's API directly what the truth is (`GET /payments/{id}`, `GET /orders/{id}`). Stronger than verify: it does not depend on any secret being set correctly. | Aegis |
| **Fulfil** | Delivering what was paid for: ship the order, credit the wallet, perform the recharge, activate the plan. **The step where money turns into value — and the one attackers target.** | Merchant backend |
| **Settle** | Razorpay transfers the collected money to the merchant's bank account, usually T+1/T+2. | Razorpay → merchant bank |
| **Refund** | Money returned to the customer for a captured payment. | Merchant asks, Razorpay executes |
| **Webhook / callback** | A server-to-server HTTP POST telling the merchant something happened. Same thing, two names. | Razorpay → merchant |

## 2. The full checkout flow

```
 Customer app            Merchant backend            Razorpay               Bank / NPCI
      │                         │                        │                        │
 ① Buy ₹499 ──────────────────► │                        │                        │
      │                         │ ② Create Order ──────► │                        │
      │ ◄──── order_id ──────── │ ◄──── order_id ─────── │                        │
 ③ Open Checkout(order_id) ───────────────────────────► │                        │
      │   customer approves in UPI app / enters OTP      │ ④ authorize ──────────►│
      │   [Checkout: "Waiting for payment…"]             │ ◄──── approved ────────│
      │                                                  │ ⑤ capture (auto)       │
      │ ◄── ⑥ payment_id, order_id, signature ────────── │                        │
 ⑦ send proof to backend ─────► │                        │                        │
      │                         │ ⑧ VERIFY + CONFIRM     │                        │
      │ ◄──── success page ──── │ ⑨ FULFIL               │                        │
      │                         │ ◄── ⑩ webhooks: payment.captured, order.paid ── │
      │                         │                        │                        │
                                  ⑪ T+1/T+2 settlement to merchant's bank
```

The "Waiting for payment…" screen (step ④) belongs to Razorpay's Checkout, which is waiting on the
bank. The merchant is not involved until step ⑥.

## 3. Two routes carry the same news

The merchant learns "order paid" through **two independent routes**:

| | Route A — checkout handler (steps ⑥–⑧) | Route B — webhook (step ⑩) |
|---|---|---|
| Path | Razorpay → customer's device → merchant backend | Razorpay → merchant backend, server to server |
| Speed | Immediate; the customer is waiting on it | Seconds to minutes later |
| Reliability | **Fragile** — app closed, network lost, phone died | Reliable — Razorpay retries for hours |
| Covers | Only the success moment | Success, failures, refunds, late authorisations, disputes |
| Proof | `HMAC_SHA256(order_id + "\|" + payment_id, key_secret)` | `HMAC_SHA256(raw_body, webhook_secret)` |
| Purpose | Show the success page now | Make sure nothing is ever missed |

When Route A breaks, the customer sees "payment processing" until Route B arrives — the source of
most "I paid but nothing happened" tickets.

## 4. What goes wrong at each step

| Step | Common bug | Effect | Aegis protection |
|---|---|---|---|
| ② Order | Amount taken from the client | Customer pays ₹1 for a ₹499 order | Out of scope (merchant logic) — documented |
| ⑦–⑧ Route A | Signature not checked, or `status=success` trusted | Free orders | Verify API checks signature **and** confirms |
| ⑦–⑧ Route A | `order_id` taken from the client, not from the merchant's own record | Pay for a cheap order, claim an expensive one | Verify API requires the merchant's stored `order_id` |
| ⑩ Route B | Webhook signature not checked / empty secret (2026 CVEs) | Anyone can mark orders paid | Ingress signature check + confirm |
| ⑩ Route B | Duplicate or replayed webhook processed again | Wallet credited twice | Dedupe + state machine |
| ⑩ Route B | `payment.failed` for an earlier attempt arrives after success | **Paid order gets cancelled** | Order-level state: a failed attempt never undoes `PAID` |
| ⑥–⑩ | Two attempts both captured on one order | Customer charged twice, refund missed | `order.overpaid` alert event |
| ⑨ Fulfil | Route A **and** Route B both fulfil | Order fulfilled twice | One `order.paid` event with one ID across both routes |

## 5. Where Aegis sits

```
                ┌──────────────── Route B ──────────────────┐
 Razorpay ──webhook──► Aegis Ingress ─► … ─► Aegis Deliverer ──► Merchant backend ──► FULFIL
                                                                    ▲      │
 Customer app ──proof──────────────────────────────────────────────┘      │
                ┌──────────────── Route A ──────────────────┐              │
                │   Merchant backend ──POST /verify──► Aegis Verify API ◄──┘
                └───────────────────────────────────────────┘
```

Both routes end in **one state machine** inside Aegis. Whichever route reports the payment first
moves the order to `PAID` and creates **one `order.paid` event with one ID**. The merchant fulfils
on that event — once.

Aegis **never moves money**: it does not create orders, capture, refund, recharge or settle.
It decides what the merchant may believe.

## 6. Recharges and bill payments

A recharge adds a **second external transaction**: after the customer pays, the merchant must buy
the recharge from an operator, usually through an aggregator or BBPS.

```
 Customer ──► Merchant: "Recharge 98xxxxxx21, ₹299"
              Merchant ⇄ Razorpay: order + checkout (same as §2)  → order.paid
              Merchant ──► Recharge aggregator / BBPS ──► Operator
              Aggregator ──callback──► Merchant: SUCCESS | FAILED | PENDING   (seconds to hours)
              SUCCESS → done · FAILED → refund via Razorpay · PENDING → status check later
```

### How it differs from a checkout

| | Checkout (pizza) | Recharge / bill payment |
|---|---|---|
| External money events per order | One (payment in) | Two (payment in, value out) |
| Fulfilment | Internal, instant | External, slow, often `PENDING` |
| "Failed" means | Customer can retry | **Merchant must refund** |
| Worst forged callback | Fake success → free goods | **Fake FAILED → recharge done *and* money refunded** |
| Missing callback | Rare | Common — needs scheduled **status checks** |
| Late surprise | Late authorisation | Late success *after* the refund went out |
| Callback security | Gateways sign webhooks | Aggregator callbacks often rely on weaker schemes (IP allow-lists, shared tokens) |

### What Aegis does for recharges (M3)

- The **payment side is identical** to checkout — nothing new.
- The **fulfilment side** uses the same engine with a different adapter: verify the aggregator's
  callback, **confirm with the aggregator's status-check API**, apply a fulfilment state machine,
  notify once.
- **Refund only on a confirmed, final `FAILED`.** A forged or premature `FAILED` can never trigger one.
- **Stuck `PENDING`** transactions are re-checked on a schedule, even if no callback ever arrives.
- **Success after refund** raises `fulfilment.late_success` so the merchant can recover the amount.
