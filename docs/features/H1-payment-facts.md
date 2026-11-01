# H1 — Payment facts: explicit transitions and fact identity

| | |
|---|---|
| Status | done |
| Depends on | M1 |
| Design references | HARDENING §1, §2, §6 · LLD §3 steps 6–12, §5 · DECISIONS D21, D24 |

## 1. Goal
Payment state can never be corrupted by duplicate, late or out-of-order events: every state change is
an explicit, tested transition, and every distinct business fact (each refund) is recognised as such.

## 2. Gaps closed (found in the M1 code)
1. A second **partial refund** was blocked as `DUPLICATE` (same status).
2. A **second payment** succeeding for an order already paid was forwarded as news to fulfil.
3. **Unsupported events** were forwarded with outcome OTHER.
4. SUCCESS after FAILED passed for **every** provider (ranking), not only where documented.
5. A refund event naming only its payment could not find its validation.

## 3. Design
- `Outcome` without ranking, plus `REVERSED`. `Transitions`: one explicit table for every (current,
  next) pair. `Policy` (freshness, late success) comes from the adapter (`ProviderAdapter.policy()`).
- `PaymentMessage.factRef` and `Payment.Entry.amount`/`factRef`: refunds are facts by ID; stored in
  the row's JSON; `Payment.wasCaptured()` and `refunded()` read the append-only history.
- Checks: `AMOUNT_MATCHES` (not for refunds/reversals), `REFUND_WITHIN_PAYMENT` (all refunds ≤ paid,
  same currency), `NOT_PAID_ELSEWHERE` (`DUPLICATE_PAYMENT` / `DOWNGRADE`), `TRANSITION`.
- Razorpay: explicit event allowlist; `refund.processed` → refund ID + amount (required); late
  authorization declared.
- Validation lookup through a known payment when the message names only the payment; lock order
  stays validation → payment.
- Alerts: `PaidTwice`; `INVALID_TRANSITION` joins the amount/downgrade alert.

## 4. Tests
| Test | Covers |
|---|---|
| `TransitionsTest` | all 36 pairs, late-success policy, refund identity (42 cases) |
| `GateTest` | partial refunds (two pass, same refund DUPLICATE, excess AMOUNT_MISMATCH), refund before capture, paid twice, strict late success, unsupported not forwarded |
| `ChecksTest` | paid-elsewhere table, status-check confirmations, refund amount rules |
| `RazorpayAdapterTest` | refund identity and amount, missing fields unparseable, late-authorization policy |
| `CheckerIT.partialRefundsArePassedEachOnce` | end to end with PostgreSQL |
| `lab/run.sh` | F1–F10 still blocked through Aegis; 100/100 legitimate payments fulfilled once |
