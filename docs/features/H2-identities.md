# H2 — Database-enforced identities

| | |
|---|---|
| Status | done |
| Depends on | H1 |
| Design references | HARDENING §2, §4 · LLD §7 · DECISIONS D25 |

## 1. Goal
"One message, once" and "one fact, forwarded once" hold because the database refuses anything else,
not only because the code checks under a row lock.

## 2. Design
- **Flyway V2** adds two identity tables, written in the same transaction as the payment row and
  deleted with it (`ON DELETE CASCADE`), and backfills them from existing rows (existing
  installations upgrade in place; staging did):
  - `payment_messages (provider, message_id)` primary key → a provider message ID exists once.
  - `payment_facts (payment_id, outcome, fact_ref)` primary key → a forwardable fact (passed
    callback/webhook/async response, not OTHER) exists once per payment.
- A message ID names one message for the **whole provider**: the Checker looks it up first; seen
  anywhere means a repeat, counted on the payment where it was first stored.
- Lock order unchanged: validation → payment.
- A constraint violation fails the transaction (no partial state); the record is retried and, being
  found, becomes a repeat.
- **Identities, precisely:** message = provider event ID (Razorpay `X-Razorpay-Event-Id`; without
  one, `body:<sha256 of the exact bytes>`; callbacks `cb:<payment id>`); payment = provider
  transaction ID; fact = outcome + fact reference (refund ID); delivery = `<payment id>-<entry>`.
- Unverified messages keep `unverified:<uuid>` IDs, so a forgery can never claim a genuine ID.

## 3. Tests
| Test | Covers |
|---|---|
| `IdentityIT.concurrentConsumersStoreOneMessageAndOneFact` | the same record handled by 10 consumers at once → 1 message row, 1 fact row, 1 PASSED (review item 9) |
| `IdentityIT.differentEventIdsForTheSameFactMakeOneFact` | `payment.captured` + `order.paid` → 2 messages, 1 fact (item 8) |
| `IdentityIT.theDatabaseRefusesASecondCopyOfAFact` | direct inserts violate the keys |
| `IdentityIT.aMessageIdIsUniqueAcrossPayments` | a reused event ID is a repeat of the first |
| all earlier ITs | unchanged behaviour (now also with unique event IDs per test) |
