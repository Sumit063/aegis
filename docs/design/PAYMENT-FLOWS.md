# Payment Flows — Where Aegis Fits

How online payments, UPI, QR payments, recharges and bill payments actually work, and the exact
points where Aegis takes part. Razorpay is the example gateway; Cashfree, PayU and PhonePe PG
follow the same shape, and Stripe differs mainly in names (PaymentIntent instead of Order).

## 1. Where Aegis sits

Aegis runs **inside the merchant's own infrastructure** — the business that sells something and
receives the money — next to its backend.

```
 Customer's device         Gateway / NPCI / banks             MERCHANT'S INFRASTRUCTURE
 ─────────────────         ──────────────────────             ──────────────────────────────────
                                                               ┌────────┐    ┌──────────────────┐
 UPI app / browser ──────► move the money ──webhooks─────────► │ AEGIS  │ ──►│ Merchant backend │
        │                                                      │        │ ◄──│ (orders, wallet, │
        └── checkout proof ──► merchant backend ──/verify─────►│        │    │  fulfilment)     │
                                                               └────────┘    └──────────────────┘
```

Aegis is **not** on the customer's phone, **not** inside the gateway, NPCI or any bank, and **not**
in the path the money travels. Money moves exactly as it does without Aegis. Aegis guards the
**messages that tell the merchant money moved**, and decides what the merchant may believe.

## 2. Use cases

**Any business whose server receives a "money moved" message and then hands over value.**

| Business | Hands over value when… | Risk without Aegis |
|---|---|---|
| E-commerce, food delivery | payment succeeds → ship / cook | Free orders from forged callbacks; double fulfilment |
| Wallets, fintech apps | payment succeeds → credit balance | Balance credited twice; forged credits |
| SaaS, ed-tech, subscriptions | payment succeeds → unlock plan | Plans unlocked without payment |
| Recharge and bill-payment apps | payment succeeds → pay operator/biller; fulfilment fails → refund | Refund **and** recharge; stuck pending transactions |
| Billing systems with dynamic QR (restaurants, fuel, retail POS) | QR credited → mark bill paid | Bills marked paid by forged or duplicate messages |

Not a use case: person-to-person UPI and printed shop QR codes — no merchant server receives a
message, so there is nothing to guard.

## 3. Terms

| Term | Meaning |
|---|---|
| **Order** | The merchant asks the gateway to expect a payment of a fixed amount (`order_id`). Fixing the amount server-side stops the client from changing it. |
| **Checkout** | The gateway's payment screen inside the merchant's app or site. |
| **Payment** | One attempt to pay an order. An order can have several. |
| **Authorize / Capture** | The bank approves and blocks the money / the merchant claims it (usually automatic). |
| **Verify** | Check a signature — proves the gateway produced a message. |
| **Confirm** | Ask the gateway's API directly — proves the payment is real, even if a secret leaked. |
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
      │                     │ ② create Order ──►│                    │
      │ ◄── order_id ───────│ ◄── order_id ─────│                    │
 ③ Checkout opens the UPI app (intent: pay Razorpay's VPA ₹1,299, ref)                   │
 ④ customer enters UPI PIN in their UPI app ──► PSP bank ──► NPCI ──► customer's bank debits
      │                     │                   │ ◄── ⑤ NPCI: credited to Razorpay's bank │
      │ ◄── ⑥ payment_id, order_id, signature ──│  (Checkout was polling Razorpay)        │
 ⑦ proof ──────────────────►│                   │                    │
      │                     │ ⑧ /verify ──► AEGIS ──confirm──► Razorpay API     (Route A)
      │ ◄── success page ───│ ⑨ fulfil          │                    │
      │                     │ ◄── AEGIS ◄── ⑩ webhooks: payment.captured, order.paid (Route B)
                              ⑪ T+1: Razorpay settles to the merchant's bank
```

- UPI is a **push** payment: the customer sends money from their own app. The merchant — and
  Aegis — never see the PIN or bank details.
- Steps ①–⑥ and ⑪ are untouched by Aegis. Aegis acts in ⑧ and ⑩.
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
| In Aegis | Verify API | Ingress → Kafka → Processor |

Both routes end in **one state machine**. Whichever reports the payment first moves the order to
`PAID` and creates **one `order.paid` with one ID**; the other route becomes a no-op.

## 6. What goes wrong at each step

| Step | Common bug | Effect | Aegis protection |
|---|---|---|---|
| ② Order | Amount taken from the client | ₹1 paid for a ₹1,299 order | Out of scope (merchant logic) |
| ⑦–⑧ | Signature not checked, or `status=success` trusted | Free orders | Verify API: signature + confirm |
| ⑦–⑧ | `order_id` taken from the client | Pay for a cheap order, claim an expensive one | Merchant's stored `order_id` required |
| ⑩ | Webhook signature not checked / empty secret | Anyone can mark orders paid | Signature check + confirm |
| ⑩ | Duplicate or replayed webhook | Wallet credited twice | Dedupe + state machine |
| ⑩ | `payment.failed` for attempt 1 arrives after attempt 2 succeeded | **Paid order cancelled** | Order-level state |
| ⑥–⑩ | Two attempts both captured on one order | Customer charged twice | `order.overpaid` |
| ⑨ | Both routes fulfil | Order fulfilled twice | One `order.paid` ID, database-enforced |

## 7. UPI scan-and-pay

| QR scanned | Merchant server receives a message? | Aegis |
|---|---|---|
| Personal QR (person to person) | No — bank to bank | Not applicable |
| Printed shop QR with soundbox / app alert | No — the shop hears or reads an alert | Not applicable |
| **Dynamic QR** from a billing system or website (e.g. gateway QR Codes API) | **Yes** — e.g. `qr_code.credited` webhook marks the bill paid | **Yes (M2)** |

```
 POS / website ─► gateway: create QR for bill #88 (₹640, single use)
 Customer scans with UPI app ─► NPCI ─► gateway credited
 Gateway ──qr_code.credited──► AEGIS ──confirm (QR + payment)──► POS backend: bill #88 paid
```

A dynamic QR is treated as a **payment target**, like an order: a single-use fixed-amount QR is
paid once (extra payments → `overpaid`); a multi-use QR produces one notification per received
payment.

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

Aegis sits at the **COU**, handling ③–④. BBPS connections are private and messages are signed, so
forgery is much harder than with public webhooks; the value of Aegis there is **correctness**:
duplicate responses, pending transactions that never resolve, refund-then-success, and double
processing on retries.

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

### 8.4 What Aegis does for fulfilment (M3)

- The **payment side is identical** to checkout.
- The **fulfilment side** runs the same engine with an aggregator/BBPS adapter: verify the
  callback, **confirm with the status-check API**, apply the fulfilment state machine, notify once.
- A refund signal (`fulfilment.failed`) only for a **confirmed, final** failure.
- `PENDING` transactions are re-checked on a schedule, even with no callback.
- Success after a refund raises `fulfilment.late_success` so the amount can be recovered.
- Aegis never refunds or recharges itself; it tells the merchant when it is safe to.
