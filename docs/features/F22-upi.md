# F22 — UPI adapter (Aegis at the payer's PSP)

| | |
|---|---|
| Status | approved |
| Milestone | M3 |
| Depends on | F20 |
| Design references | NPCI.md §3 · LLD §6 · DECISIONS D33, D34 · PAYMENT-FLOWS §7 |

## 1. Goal
A PSP's UPI payments through Aegis: the payee is validated with the stated amount, one `ReqPay`
goes out, `RespPay` results (including deemed and reversals) and status checks are verified and held
to it.

## 2. Scope
- In: `ReqValAdd`/`RespValAdd`, `ReqPay` (`Ack` reply), `RespPay` (inbound, `PAY` and `REVERSAL`),
  `ReqChkTxn`/`RespChkTxn`, `<upi:Ack/>`, status-check limits.
- Out: collect requests, mandates, QR intents, encrypted PII elements (NPCI.md §5).

## 3. Design
| Concern | Rule |
|---|---|
| Connection | as BBPS; paths `/upi/ReqValAdd`, `/upi/ReqPay`, `/upi/ReqChkTxn` |
| Facts | validation: merchant ref `Txn@refId`, amount `Payee/Amount`; payment: provider ref `Txn@refId`, payment ref `Txn@id`, amount `Payer/Amount`; status: payment ref `Txn@orgTxnId`, provider ref `Txn@refId` |
| Validation | accepted on `SUCCESS` with the request's `Txn@id`; provider ref = `Txn@id`; amount = the request's (D34) |
| Inbound | `RespPay` `PAY`: payment `Txn@id`, amount `Ref[@type=PAYEE]@settAmount`; `REVERSAL`: payment `Txn@orgTxnId`, fact `Txn@id`, its own amount |
| Policy | freshness 1 h, no late success, limits 90 s / 3 in 2 h |
| Amounts | decimal `value`/`settAmount` with `curr`, exact (`Npci.majorUnits`: Cashfree's rules, refused never rounded) |

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `UpiAdapterContractTest` | unit | the shared SPI contract |
| `UpiAdapterTest` | unit | outcome table incl. `DEEMED` and reversals, decimals (`499.005` refused), required fields, roots, binding, ack |
| `UpiIT` | IT | validate → pay → deemed → reversal forwarded; second `ReqPay` while in flight 409; amount mismatch; status check limits |

## 5. Done when
- [ ] Tests pass; `./mvnw verify` green; LLD row, example config
