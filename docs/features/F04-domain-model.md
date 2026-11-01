# F04 — Domain model

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F00 |
| Design references | LLD §1, §3, §4, §5, §7 · DECISIONS D1, D3, D5 |

## 1. Goal
The pure-Java core every later feature reuses. It covers references, provider-neutral messages,
validations, payments, and the checks that turn a message into a verdict. Persistence (F05), the
Razorpay adapter (F08), the Checker (F10) and the Gateway API (F12) only translate between this
model and the outside world.

## 2. Scope
- In: value types, the validation and payment records, the check rules, and `Gate`, the single entry
  point for "a message arrived" and "may this call go out".
- Out: signature verification and decryption (provider adapters, F07/F08), storage and locking
  (F05/F10), forwarding (F13). Message bodies and headers stay out of the domain; persistence keeps
  the encrypted evidence next to each entry.

## 3. Design

All types live in `dev.aegis.domain`. They are immutable records and depend only on `java.*`
(ArchUnit).

| Package | Type | Role |
|---|---|---|
| `shared` | `Money` | amounts in minor units (existing) |
| `shared` | `Ref` | one validated reference type for providers, provider refs, payment refs, merchant refs and message IDs; `Ref.unverified(uuid)` for untrusted messages |
| `message` | `MessageType`, `Outcome`, `BlockReason`, `Verdict`, `PaymentMessage` | what a message is and what Aegis decided |
| `payment` | `Validation` | one validation: authority's amount, status `CREATED → PAID` (or `FAILED`) |
| `payment` | `Payment` | one payment and every message about it (`entries`), with its latest passed outcome |
| `check` | `Check`, `Checks` | each rule is one named `Check`; `Checks.run` returns the first block |
| `check` | `Gate` | `receive(...)` for messages that arrive (inbound or replies); `permit(...)` before an outbound call |

### Rules

**`Gate.receive`**, in order:
1. **Repeat:** the message ID is already in the payment, so only the counter goes up.
2. **Fresh:** `REPLAY` if the message is older than the provider's freshness window.
3. `OTHER` outcomes pass.
4. **Validated:** `NO_VALIDATION`.
5. **Amount matches:** `AMOUNT_MISMATCH` when the message carries an amount.
6. **No downgrade:** `DOWNGRADE` when a FAILED arrives for a PAID validation, or when an outcome ranks
   below the payment's current status (a late PENDING after SUCCESS).
7. **New fact:** `DUPLICATE` when a forwarded type reports the status the payment already has.

Then:
- The entry is appended with its verdict.
- A passed message moves the payment status forward.
- A passed SUCCESS marks the validation PAID.

The repeat check comes before freshness. That way a re-delivered old message is counted, not
appended twice.

Two cases worth spelling out:
- **SUCCESS after FAILED on the same payment passes.** Providers authorize late (Razorpay keeps
  checking with the bank for up to 3 days).
- **`Gate.permit`**:
  - a payment request needs a validation that is not FAILED, a matching amount, the validation not
    PAID, and no sibling payment PENDING (`DUPLICATE_PAYMENT`);
  - a status check must respect the provider's `Limits` (`RATE_LIMIT`).

  Blocked outbound calls are refused and not stored.

Untrusted messages (bad signature, unparseable) get their own payment via `Payment.untrusted`, with
an unverified message ID. They can never touch a genuine payment.

- **Patterns:** value objects; chain of responsibility (`Checks.run`); a pure decision function
  (`Gate`).
- **Data:** none. **Configuration:** none. **Failure behaviour:** no I/O. Invalid input is rejected
  by record constructors.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `RefTest` | unit | validation, unverified IDs |
| `PaymentTest` | unit | entry numbering, forwarding, status progression, repeats, untrusted, in flight |
| `ChecksTest` | unit | every rule, passing and blocking |
| `GateTest` | unit | T4–T11, the LLD §9 Razorpay and §10 BBPS walkthroughs, late authorization, late PENDING, OTHER |

## 5. Done when
- [x] All tests above pass; `./mvnw verify` green
- [x] Docs updated (LLD §3 downgrade rule, DECISIONS R10)
- [x] Code review checklist passed
