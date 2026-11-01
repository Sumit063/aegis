# Aegis — High-Level Design

Read [PAYMENT-FLOWS.md](PAYMENT-FLOWS.md) for how checkout, UPI and bill payments work and the terms
used here.

## 1. Role

**Aegis is a zero-trust security gateway for payment messages.** It sits between an application
(merchant backend, recharge or bill-payment app, BBPS customer unit) and its payment providers
(gateways, NPCI UPI/BBPS, aggregators). Every payment message, in either direction, passes through
Aegis, which checks that it is authentic, fresh, unique and consistent with what the provider
validated, records it, and passes or blocks it.

Aegis **does not** manage orders, run business workflows, fulfil, refund or move money.
Aegis **never calls a provider on its own**: it forwards only what the application asks for and
checks what the provider sends back.

## 2. The problem

Applications hand over value (ship goods, credit a wallet, pay a bill) when a message says money
moved. Those messages are routinely trusted without proper checks. Public CVEs from 2026 alone:

| Flaw | Examples | Effect |
|---|---|---|
| Signature never checked | CVE-2026-94360, CVE-2026-103298, CVE-2026-94361, CVE-2026-5050, CVE-2026-16621, CVE-2026-21894 | Anyone marks any order paid |
| Signature checked on some paths only | CVE-2026-87978 (one handler branch skips it) | Same |
| Broken signature logic | CVE-2026-11575 (unkeyed hash), CVE-2026-41432 (empty secret accepted), CVE-2026-33731 (rejected only if signature **and** lookup fail) | Same |
| Trusted "known" host | CVE-2026-33661 (`Host: localhost` skipped checks) | Same |
| Wrong status trusted | CVE-2026-41432 (`complete` treated as `paid`) | Unpaid orders fulfilled |
| No de-duplication or replay protection | Providers deliver at least once (Razorpay retries for 24 h) | Wallet credited N times |
| Out-of-order messages | Late `failed` after `captured` | Paid order cancelled |
| Result not matched to what was validated | Amount or order swapped | ₹10 pays for ₹10,000 |
| Double payment request on retry | App or user retries a UPI/BBPS payment | Customer debited twice |
| Provider secrets in every service | Keys spread over repos and configs | One leak forges everything |

Every application re-implements these checks and many get one wrong. Aegis does them once, the same
way, for every provider.

## 3. Where Aegis sits

```
                ┌──────────────────────────── AEGIS ─────────────────────────────┐
 Application ──►│ OUT  validation · payment request · status check (app-initiated)│──► Provider
 (holds no      │      check → sign/encrypt with provider secrets → record        │   (Razorpay, Paytm,
  provider      │                                                                │    Stripe, NPCI UPI,
  secrets)   ◄──│ IN   callback · webhook · async response · reply                │◄──  BBPS, aggregators)
  original +    │      verify → check → record → forward or block                 │
  verdict       └────────────────────────────────────────────────────────────────┘
```

Every payment system has the same two steps, which is why one engine fits all:

| Flow | ① Validation (authority fixes the amount) | ② Payment carried by | Result returns as | Format · protection |
|---|---|---|---|---|
| BBPS (COU ↔ NPCI) | Bill fetch | app → COU → NPCI | async response + Ack | XML · mTLS + XML signature |
| UPI (PSP ↔ NPCI) | Validate address | `ReqPay` | `RespPay` + Ack | XML · mTLS + XML signature |
| Razorpay | Create order | customer's browser (Checkout) | callback + webhook | JSON · TLS + HMAC-SHA256 |
| Paytm-style gateway | Initiate transaction | customer's browser/app | callback + webhook | JSON · TLS + checksum |
| Stripe | Create PaymentIntent | customer's browser/app | webhook | JSON · TLS + HMAC + timestamp |

With gateways, step ② happens in the browser, so Aegis sees ① and the results. With NPCI it sees
every leg.

Adopting Aegis: point the provider base URL and the webhook/callback URLs at Aegis and move provider
secrets into Aegis. Business code is unchanged.

### 3.1 Who can run it

Every hop in a payment chain has a receiver that acts on a message from the hop below. Aegis fits at
any of them; only the adapter changes.

```
 Customer ─► Merchant app ─► Gateway (Razorpay) ─► Acquiring bank ─► NPCI ─► Customer's bank
               ▲ Aegis           ▲ Aegis                ▲ Aegis
         (gateway messages) (bank callbacks)     (NPCI RespPay/BBPS)
```

| Runs Aegis | Upstream it talks to | Validation | Inbound messages | Adapter |
|---|---|---|---|---|
| Merchant / app | Gateway | create order | callback, webhook | Razorpay, Stripe, Paytm-style |
| Recharge / bill app (BBPS COU) | NPCI Bharat BillPay | bill fetch | async payment response | BBPS (XML, signed) |
| Gateway (e.g. Razorpay) | acquiring bank | payment initiation | bank callback | bank-specific |
| UPI PSP / bank | NPCI UPI | validate address | `RespPay` | UPI (XML, signed) |
| Recharge platform | aggregator / operator | plan / amount check | recharge callback | aggregator-specific |

## 4. What Aegis checks

**Inbound** (callback, webhook, async response, reply to an application request):

| # | Check | Stops |
|---|---|---|
| 1 | Transport: mTLS or source allow-list where the provider supports it | Spoofed senders |
| 2 | Decrypt, then **verify the signature** — every message, every path; missing, empty or weak secret means block | Every signature CVE above |
| 3 | Freshness: message time within the provider's retry window | Replays of old messages |
| 4 | Same message already seen (provider message ID) | Retries, replays |
| 5 | Refers to a known validation; amount and currency match it | Swapped orders and amounts |
| 6 | Same fact already passed (same payment, same outcome, via callback **and** webhook) | Double fulfilment |
| 7 | No downgrade: `FAILED` after a `SUCCESS` on the same validation | Paid order cancelled |

**Outbound** (only when the application asks):

| # | Check | Stops |
|---|---|---|
| 1 | Caller authenticated (mTLS + per-app key) and allowed to use that provider connection | Rogue internal callers |
| 2 | Payment request matches an OK validation and its amount | Tampered amounts |
| 3 | No new payment request while one is in flight or after success | Double debit |
| 4 | Status checks within the provider's limits (NPCI: first ≥ 90 s after authentication, ≤ 3 in 2 h) | Provider penalties |
| 5 | Signed/encrypted with provider secrets held only by Aegis | Secret sprawl |

Every message is stored (encrypted at rest) with a verdict: `PASSED`, or `BLOCKED` with a reason.
Passed inbound messages are forwarded to the application as **the original message, unchanged, plus
Aegis's verdict**, over mTLS, signed by Aegis, optionally encrypted to the application's public key.

## 5. Zero trust, concretely

| Principle | How |
|---|---|
| Never trust the network | mTLS on every hop Aegis controls; no host, IP or header is trusted for being "internal" |
| Verify every message explicitly | One pipeline, no shortcuts: every message gets every check |
| Never trust the sender's own claims | Amount and reference must match what the **authority** fixed at validation; browser-carried callbacks are untrusted input |
| Least privilege | Provider secrets only in Aegis; each application has its own credentials and sees only its own connections |
| Assume breach | Encrypted raw evidence of every message; alert on every block; fail closed |
| Don't require trust in Aegis either | The application receives the provider's original message and signature and can re-verify it |

**Residual risk.** With shared-secret (HMAC) providers, whoever steals the secret can sign a valid
message for an existing validation with the right amount; a passive gateway cannot tell. Mitigations:
- the secret exists only in Aegis;
- dual-secret rotation without downtime;
- for high-value handovers, the application asks for a status check **through Aegis** first.

NPCI and BBPS sign with private keys, so a leak on the application side cannot forge their messages.

## 6. Guarantees

- Nothing reaches the application without passing every check.
- Nothing is acknowledged to a provider before it is durably stored.
- A passed message is delivered to the application at least once, always with the same `id`, retried
  for 72 hours; after that it stays stored and an operator re-forwards it. It is never dropped.
- What survives which failure (process restart, broker loss, disk loss, database loss, an application
  down for longer than 72 h) is stated per failure in RUNBOOK §2; a single host is not disk-loss safe.
- A fact (payment X succeeded) is forwarded once, whichever channel reports it first.
- Aegis never initiates a call to a provider.

## 7. Architecture

```
 Provider ──► Ingress ──► Kafka ──► Checker ──► PostgreSQL ──► Forwarder ──► Application
 Application ──► Gateway API ──► Provider        (inline: check, sign, send, verify reply, record)
```

| Component | Job |
|---|---|
| Ingress | Transport check, decrypt, signature; write to Kafka; return the provider's acknowledgement (2xx or `<Ack/>`) |
| Checker | Freshness, duplicates, match, same fact, downgrade; store with verdict |
| Gateway API | The application's outbound calls: check, sign, send, verify and store the reply |
| Forwarder | Deliver passed messages to the application; retry with backoff |
| Admin | Look up by reference, list blocks, re-forward, settings |

Kafka lets Ingress acknowledge within provider deadlines (Razorpay: 5 s) without depending on the
database. PostgreSQL enforces uniqueness and stores the records.

## 8. Latency

| Path | Added by Aegis |
|---|---|
| Acknowledging a provider message | signature check + Kafka write: milliseconds |
| Application's outbound call | checks + one insert + signing: milliseconds on top of the provider's time |
| Forwarding to the application | asynchronous, after acknowledgement |

## 9. Failure behaviour

| Failure | Behaviour |
|---|---|
| Kafka down | Ingress returns an error, so the provider retries; nothing is acknowledged unstored |
| PostgreSQL down | Checker pauses (messages wait in Kafka); outbound calls refused (fail closed) |
| Application down | Forwarder retries with backoff for 72 h; then the message is marked failed, alerted, kept and re-forwarded by an operator |
| Provider down | Outbound call fails back to the application; Aegis does not retry it |
| Aegis down | Providers retry their messages; outbound calls fail fast; money still moves |

## 10. Non-goals

Order management · fulfilment · refunds · reconciliation · polling providers · card data · moving
money.

## 11. Milestones

| # | Milestone |
|---|---|
| M0 | Exploit Lab: reproduce the CVE classes against a vulnerable reference application |
| M1 | Core gateway with Razorpay: inbound checks, outbound proxy, forwarding, admin |
| M2 | Stripe and Paytm-style adapters; load and failure tests |
| M3 | NPCI-style XML adapters (BBPS, UPI) against a simulator |
| M4 | Partner API protection and rate limits |
