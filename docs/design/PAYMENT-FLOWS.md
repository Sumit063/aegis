# Payment Flows — Where Aegis Fits

How online payments, UPI, QR payments, recharges and bill payments actually work, and the exact
points where Aegis takes part. Razorpay is the example gateway; Cashfree, PayU and PhonePe PG
follow the same shape, and Stripe differs mainly in names (PaymentIntent instead of Order).

## 1. Where Aegis sits

Aegis runs **inside the application's own infrastructure**, between the application and its payment
providers. Every payment message passes through it in both directions.

```
 Customer's device         Gateway / NPCI / banks              APPLICATION'S INFRASTRUCTURE
 ─────────────────         ──────────────────────              ─────────────────────────────────
                                                               ┌────────┐    ┌──────────────────┐
 UPI app / browser ──────► move the money ──webhooks─────────► │        │ ──►│ Application      │
        │                           ▲                          │ AEGIS  │ ◄──│ (orders, wallet, │
        └── checkout callback ──────┼────────────────────────► │        │    │  fulfilment)     │
                                    └──── validations, payment ┤        │    └──────────────────┘
                                          requests, status checks (app-initiated)
                                                               └────────┘
```

Aegis is **not** on the customer's phone, **not** inside the gateway, NPCI or any bank, and **not**
in the path the money travels. It checks the **messages about money** and holds the provider
secrets. It never calls a provider unless the application asks.

## 2. Use cases

**Any system that receives a "money moved" message and then hands over value**, at any hop of the
payment chain.

| Who runs Aegis | Messages it protects | What goes wrong without it (real CVE classes) |
|---|---|---|
| E-commerce, food delivery, SaaS, ed-tech | Gateway callbacks and webhooks | Forged "paid" callbacks give free orders (CVE-2026-94360, -103298, -87978); duplicates fulfil twice |
| Wallets and fintech apps | Top-up webhooks | Balance credited twice by retries; forged credits via empty secrets (CVE-2026-41432) |
| Recharge and bill-payment apps (BBPS COU) | Bill fetch, payment request, async responses | Double debit on retry; refund **and** delivery; status checks breaking NPCI limits |
| Billing systems with dynamic QR | QR credit webhooks | Bills marked paid by forged or replayed messages |
| Payment gateways (e.g. Razorpay's own side) | Acquiring-bank callbacks | Same classes, one hop upstream |
| UPI PSPs and banks | NPCI `RespPay` and other async responses | Duplicate responses, replay, signature handling scattered across services |

Not a use case: person-to-person UPI and printed shop QR codes with soundbox alerts. No server acts
on a message, so there is nothing to guard.

## 3. Terms

| Term | Meaning |
|---|---|
| **Order** | The merchant asks the gateway to expect a payment of a fixed amount (`order_id`). Fixing the amount server-side stops the client from changing it. |
| **Checkout** | The gateway's payment screen inside the merchant's app or site. |
| **Payment** | One attempt to pay an order. An order can have several. |
| **Authorize / Capture** | The bank approves and blocks the money / the merchant claims it (usually automatic). |
| **Validation** | The first step of every payment: the authority fixes the amount (create order, bill fetch, validate address). |
| **Verify** | Check a signature — proves the provider produced a message. |
| **Fulfil** | Hand over the value: ship, credit, recharge, unlock. **The step attackers target.** |
| **Settle** | The gateway pays collected money into the merchant's bank, usually T+1/T+2. |
| **Refund** | Money returned to the customer for a captured payment. |
| **Webhook / callback** | A server-to-server HTTP POST announcing an event. Same thing, two names. |
| **VPA** | UPI ID, e.g. `name@bank`. |
| **PSP app / PSP bank** | The UPI app (PhonePe, GPay…) and the bank behind it. |
| **NPCI** | National Payments Corporation of India — runs the UPI switch and Bharat BillPay. |
| **Payment aggregator** | A licensed collector (e.g. Razorpay) that receives money for merchants and settles to them. |
| **COU / BOU / BBPCU** | Bharat BillPay roles: Customer Operating Unit (the app you pay in), Biller Operating Unit (serves the biller), central unit (NPCI Bharat BillPay switch). |
| **Status check** | Asking "what happened to transaction X?" instead of waiting for a callback. |

## 4. Online checkout with UPI (through Razorpay)

```
 Customer app         Merchant backend      Razorpay           NPCI / banks
      │                     │                   │                    │
 ① Pay ₹1,299 ─────────────►│                   │                    │
      │                     │ ② create Order ──►│  (through AEGIS)   │
      │ ◄── order_id ───────│ ◄── order_id ─────│                    │
 ③ Checkout opens the UPI app (intent: pay Razorpay's VPA ₹1,299, ref)                   │
 ④ customer enters UPI PIN in their UPI app ──► PSP bank ──► NPCI ──► customer's bank debits
      │                     │                   │ ◄── ⑤ NPCI: credited to Razorpay's bank │
      │ ◄── ⑥ payment_id, order_id, signature ──│  (Checkout was polling Razorpay)        │
 ⑦ proof ──────────────────►│                   │                    │
      │                     │ ⑧ (callback lands at AEGIS, checked, forwarded)  (Route A)
      │ ◄── success page ───│ ⑨ fulfil          │                    │
      │                     │ ◄── AEGIS ◄── ⑩ webhooks: payment.captured, order.paid (Route B)
                              ⑪ T+1: Razorpay settles to the merchant's bank
```

- UPI is a **push** payment: the customer sends money from their own app. The merchant — and
  Aegis — never see the PIN or bank details.
- Aegis acts in ② (validation passes through it), ⑧ and ⑩. Steps ③–⑥ and ⑪ are untouched.
- Card and netbanking payments follow the same shape; only step ④ differs (card network or bank
  page instead of NPCI).

## 5. Two routes carry the same news

| | Route A — checkout proof (⑥–⑧) | Route B — webhook (⑩) |
|---|---|---|
| Path | Gateway → customer's device → merchant backend | Gateway → merchant backend, server to server |
| Speed | Immediate; the customer is waiting | Seconds to minutes later |
| Reliability | **Fragile** — app closed, network lost, phone died | Reliable — retried for hours |
| Covers | Only the success moment | Success, failures, refunds, late authorisations |
| Proof | `HMAC_SHA256(order_id + "\|" + payment_id, key_secret)` | `HMAC_SHA256(raw_body, webhook_secret)` |
| In Aegis | `/in/{connection}/callback` | `/in/{connection}` → Kafka → Checker |

Both routes go through the same checks. Whichever reports the payment first is forwarded; the other
is recorded as the **same fact** and not forwarded again.

## 6. What goes wrong at each step

| Step | Common bug | Effect | Aegis protection |
|---|---|---|---|
| ② Order | Amount taken from the client | ₹1 paid for a ₹1,299 order | Out of scope (application logic); the validated amount is recorded |
| ⑦–⑧ | Signature not checked, or `status=success` trusted | Free orders | Signature on every callback |
| ⑦–⑧ | `order_id` taken from the client | Pay for a cheap order, claim an expensive one | Callback must match a recorded validation |
| ⑩ | Webhook signature not checked / empty secret | Anyone can mark orders paid | Signature check, fail closed |
| ⑩ | Duplicate or replayed webhook | Wallet credited twice | Message-ID uniqueness + freshness |
| ⑩ | `payment.failed` for attempt 1 arrives after attempt 2 succeeded | **Paid order cancelled** | Blocked as `DOWNGRADE` |
| ⑩ | Amount in the message differs from the order | Underpayment accepted | Blocked as `AMOUNT_MISMATCH` |
| ⑨ | Both routes fulfil | Order fulfilled twice | Same fact forwarded once |

## 7. UPI scan-and-pay

| QR scanned | Merchant server receives a message? | Aegis |
|---|---|---|
| Personal QR (person to person) | No — bank to bank | Not applicable |
| Printed shop QR with soundbox / app alert | No — the shop hears or reads an alert | Not applicable |
| **Dynamic QR** from a billing system or website (e.g. gateway QR Codes API) | **Yes** — e.g. `qr_code.credited` webhook marks the bill paid | **Yes (M2)** |

```
 POS / website ─► gateway: create QR for bill #88 (₹640, single use)
 Customer scans with UPI app ─► NPCI ─► gateway credited
 Gateway ──qr_code.credited──► AEGIS (signature, match QR, amount) ──► POS backend: bill #88 paid
```

Creating a dynamic QR is a **validation**, like creating an order; each credit is a payment message
checked against it.

## 8. Recharges and bill payments

A recharge or bill payment adds a **second external transaction**: after the customer pays, the
merchant must pay the operator or biller.

### 8.1 Direct or through an aggregator

```
 Customer ─► Recharge app: "₹299 for 98xxxxxx21"
   ① payment in:  app collects ₹299 (UPI via a gateway) ──► AEGIS (as §4)
   ② value out:   app ─► operator API or recharge aggregator ─► operator
                  ◄── callback: SUCCESS | FAILED | PENDING ──► AEGIS (M3)
   ③ SUCCESS → done · confirmed FAILED → refund customer · PENDING → status check later
```

### 8.2 Through Bharat BillPay (BBPS)

```
 Customer ─► COU (the app: bank app, wallet, payments app)
              ① collect money from the customer
              ② bill payment request ─► NPCI Bharat BillPay central unit ─► BOU ─► Biller
              ③ ◄── response: SUCCESS | FAILURE | PENDING (asynchronous)
              ④ success → receipt · failure → refund · pending → transaction status check
```

Aegis sits at the **COU**: bill fetch and payment requests go out through it, responses come back
through it. BBPS messages are signed with private keys, so forgery is hard; the value of Aegis there
is signature handling in one place, duplicate responses, double payment requests on retries, and
status checks kept within NPCI's limits.

### 8.3 How it differs from a checkout

| | Checkout | Recharge / bill payment |
|---|---|---|
| External money events per order | One (payment in) | Two (payment in, value out) |
| Fulfilment | Internal, instant | External, slow, often `PENDING` |
| "Failed" means | Customer can retry | **Merchant must refund** |
| Worst forged callback | Fake success → free goods | **Fake FAILED → value delivered *and* money refunded** |
| Missing callback | Rare | Common → scheduled status checks |
| Late surprise | Late authorisation | Success *after* the refund went out |
| Callback security | Gateways sign webhooks | Private aggregators vary; BBPS is signed and private |

### 8.4 What Aegis does here

- The **payment-in side** is identical to checkout.
- The **value-out side** is another provider connection (aggregator or BBPS): the request goes out
  through Aegis and the callback comes back through it, with the same checks.
- Aegis never refunds, recharges or retries on its own; the application decides, and status checks
  happen only when the application asks.
