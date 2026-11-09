# F21 — BBPS adapter (Aegis at the COU)

| | |
|---|---|
| Status | approved |
| Milestone | M3 |
| Depends on | F20 |
| Design references | NPCI.md §2 · LLD §6, §10 · DECISIONS D33, D34 · PAYMENT-FLOWS §8.2 |

## 1. Goal
A customer operating unit's bill payments through Aegis: bill fetch fixes the amount, the payment
request goes out once, asynchronous results and status checks are verified and held to the bill.

## 2. Scope
- In: `BillFetchRequest`/`Response` (validation), `BillPaymentRequest` (payment request, `Ack`
  reply), `BillPaymentResponse` (inbound async), `TxnStatusRequest`/`Response` (status check),
  `<bbps:Ack/>`, status-check limits.
- Out: complaints, multi-biller payments, asynchronous fetch and status answers (NPCI.md §5).

## 3. Design
| Concern | Rule |
|---|---|
| Connection | secrets `signing-key` (Aegis's PKCS#8 key), `switch-certificate` (list); `base-url` required; paths `/bbps/BillFetchRequest`, `/bbps/BillPaymentRequest`, `/bbps/TxnStatusRequest` |
| Facts | merchant ref = provider ref = `Head@refId`; payment ref = `Txn@txnReferenceId` (status: `TxnStatusReq@txnReferenceId`); amount `Amount/Amt` |
| Requests | root must match the call (`BillFetchRequest` for a validation, …); unsigned; signed by Aegis in `prepare`; `refId` and `txnReferenceId` match `[A-Za-z0-9_-]{1,64}` |
| Replies | verified (`UntrustedReplyException` otherwise); bound to the request (`refId`, `txnReferenceId` must be the request's) |
| Inbound | only `BillPaymentResponse` is news; any other signed root → OTHER |
| Policy | freshness 1 h, clock skew 5 min, no late success, limits 90 s / 3 in 2 h |
| Amounts | integer minor units; currency numeric `356` (or any ISO numeric known to the JDK) |

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `BbpsAdapterContractTest` | unit | the shared SPI contract (keys named current, previous, unknown) |
| `BbpsAdapterTest` | unit | outcome table, reason/code disagreement, amounts, required fields, root per call, pre-signed request refused, reply bound to request, path-safe IDs, ack |
| `BbpsIT` | IT | LLD §10 row by row against `FakeProvider` (signed replies) and signed async responses: fetch, pay, retry refused, deemed → status too early (429) → SUCCESS, duplicate SUCCESS Acked not forwarded, forged response 401, late FAILURE `DOWNGRADE` |

## 5. Done when
- [ ] Tests pass; `./mvnw verify` green; LLD row, example config
