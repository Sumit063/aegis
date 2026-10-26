# Aegis

**A self-hosted, payment-aware firewall for payment callbacks and webhooks.**

Aegis sits in front of your backend and makes sure every payment event that reaches it
(Razorpay, Cashfree, PhonePe, Stripe, ...) is **genuine, unique, in order and confirmed
with the provider** — with zero changes to your application code.

```
Payment provider ──► Aegis ──► Your backend (private)
                     verify · dedupe · order · confirm · log · replay
```

## Why

Forged or mishandled payment callbacks keep causing real losses. In 2026 alone, multiple
public CVEs let attackers mark unpaid orders as paid by forging callbacks
(e.g. CVE-2026-11575, CVE-2026-9832, CVE-2026-87978, CVE-2026-16738, CVE-2026-33661).
Each team re-implements verification and gets one detail wrong. Aegis does it once, correctly.

## Planned features

- **Fail-closed signature verification** per provider (refuses to run with empty/weak secrets)
- **Confirm-with-provider**: re-checks payment status, order ID and amount via the provider API
- **Replay & duplicate protection**: timestamp windows + event-ID dedupe
- **Payment state machine**: rejects illegal transitions (e.g. `failed` after `captured`)
- **Durable queue & replay** when your backend is down
- **Forward signing / mTLS** so your backend only trusts Aegis
- **Card-testing detection** from payment metadata (no raw card data — out of PCI scope)
- **Event timeline & audit log**, and later an AI analyst to explain failures

## Milestones

| # | Milestone | Status |
|---|---|---|
| 0 | **Exploit Lab** — reproduce real callback CVEs in Docker, prove Aegis blocks them | planned |
| 1 | Webhook integrity core (verify, dedupe, confirm, forward) | planned |
| 2 | Durable queue, replay, state machine, observability | planned |
| 3 | Partner API protection (mTLS/HMAC, rate limits) | planned |
| 4 | Card-testing / risk rules | planned |
| 5 | AI analyst + rule simulation | planned |

## Status

Early design stage. This is a learning-in-public project.
