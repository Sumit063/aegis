# H6 — Post-hardening review: required fields, clock skew, refunds, guarantees, retention

| | |
|---|---|
| Status | done |
| Depends on | H5 |
| Design references | HARDENING §13 · LLD §3, §5, §6, §7 · RUNBOOK §2, §5, §6 |

## Problem (verified against the code, HARDENING §13)
1. A Razorpay success event without `amount` parsed to a null amount and `AMOUNT_MATCHES` skipped it;
   a missing currency became `INR`; status-check replies had the same gap.
2. `FRESH` skipped messages without a timestamp and accepted any future timestamp.
3. A refund made the payment's state `REFUNDED`, so a genuine "captured" status reply after a partial
   refund was blocked as `DOWNGRADE`, and a "refunded" status reply was counted as a new refund.
4. README/HLD promised "no passed message is lost"; deliveries stop after 72 h and the single broker
   keeps one unsynced replica.
5. Retention purged payments by creation time (a recent refund or an undelivered message could be
   deleted); key retirement ignored backups. The schema also rejected the `REVERSED` state.

## Changes
- **Required fields (adapter, fail closed).** Razorpay: every payment entity needs an integer
  `amount` and a currency code; a refund needs its ID, integer amount and currency; a webhook needs
  an integer `created_at`. Missing, null or mistyped → `UNPARSEABLE`. Unknown events are unchanged
  (OTHER → `UNSUPPORTED`). The signed checkout callback carries no amount by protocol; its amount is
  bound by the order signature to the validated order.
- **Clock skew.** `Policy.maxClockSkew` (SPI `maxClockSkew()`, default 5 min); `FRESH` blocks a
  timestamp further in the future as `FUTURE_TIMESTAMP`. Messages without a timestamp skip it only
  where the provider's protocol has none (callbacks, status replies).
- **Refunds are facts, not a state.** The payment's state is its lifecycle (PENDING, SUCCESS, FAILED,
  REVERSED); refunds are facts on a captured payment, each identified, their sum ≤ the paid amount.
  A status reply is an observation: "captured" after refunds confirms the capture; "refunded" is the
  provider's cumulative total (`amount_refunded`), checked against the paid amount, never a new
  refund fact. Migration V3 moves stored `REFUNDED` states to `SUCCESS` and allows `REVERSED`.
- **Guarantees.** README/HLD/RUNBOOK state exactly what holds per failure; Kafka topic replication
  is configurable (`aegis.kafka.replicas`, `min-insync-replicas`) with a documented production profile.
- **Retention.** Purge by last activity (`updated_at`) and never while a delivery is pending; one
  documented policy for records, rejected evidence, delivery attempts, payloads, backups and keys.

## Tests
| Test | Covers |
|---|---|
| `RazorpayAdapterTest` | missing / null / mistyped amount, missing currency, missing / bad `created_at`, refund fields, legitimate events, unknown events, status replies |
| `ChecksTest`, `GateTest` | freshness boundaries (inside, outside, future, skew edge, missing); capture → partial refund → captured status; several partial refunds; duplicate refund; full refund; over-refund; late contradictory events |
| `TransitionsTest` | the new table (refund is a fact on SUCCESS) |
| `CheckerIT.statusChecksAfterRefundsConfirmTheCapture` | through the Gateway API: "captured" after a partial refund passes, "refunded" observation adds nothing, late failure blocked |
| `AdminIT.retentionDeletesOnlyRecordsInactiveForTheWholePeriod` | recent activity and pending deliveries are kept |
