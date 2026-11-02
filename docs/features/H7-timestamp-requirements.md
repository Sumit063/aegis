# H7 — Timestamp requirements by message type

| | |
|---|---|
| Status | done |
| Depends on | H6 |
| Design references | LLD §3 step 9, HARDENING §13, DECISIONS D29 |

## 1. Goal
Freshness is judged on every message whose protocol has a send time, even if an adapter forgets to
require it. H6 enforced this only in the Razorpay adapter; the domain still skipped missing times.

## 2. Scope
- In: a per-message-type rule in the domain; tests that status replies are not dated by the payment.
- Out (explicitly): new providers; changes to the replay window or clock skew.

## 3. Design
- **Domain:** `MessageType.timestamped()` is true for WEBHOOK and ASYNC_RESPONSE. `Checks.FRESH`
  blocks those without `sentAt` as the new `BlockReason.MISSING_TIMESTAMP`; CALLBACK, STATUS_CHECK and
  PAY_REQUEST have no send time and skip freshness (signature binding and identity deduplication for
  callbacks; replies answer Aegis's own call).
- **Adapters:** unchanged. The Razorpay adapter already rejects webhooks without a valid `created_at`
  and never uses a payment's `created_at` as a status reply's send time.
- **Data:** none (block reasons are stored as text, no constraint).

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `ChecksTest.onlyNotificationsMustCarryASendTime` | unit | each message type without a send time |
| `RazorpayAdapterTest.aStatusReplyIsNotDatedByThePayment` | unit | a reply with an old payment `created_at` has no send time |

## 5. Done when
- [x] All tests above pass; `./mvnw verify` green
- [x] Docs updated (LLD, DECISIONS D29, HARDENING §13)
- [x] Code review checklist passed
