# NPCI-style messages (BBPS and UPI)

The message contract that Aegis's `bbps` and `upi` adapters and the lab's NPCI switch simulator
(`lab/npci-switch`) both implement. It follows the structure of NPCI's Bharat BillPay and UPI
interfaces (XML, enveloped XML signatures, an `Ack` for every asynchronous message, async results,
deemed transactions, reversals, status checks) without claiming to be either specification: those are
distributed to members only. Element and attribute names below are this contract's. Where it
simplifies NPCI, §5 says so.

## 1. Common rules

| Concern | Rule |
|---|---|
| Encoding | UTF-8 XML, one root element in the namespace below, no DOCTYPE (refused), no external entities, at most 64 KB |
| Signature | Every message is signed (D33): an enveloped `ds:Signature`, the last child of the root, over the whole document (`Reference URI=""`), RSA-SHA256, exclusive C14N. The application's requests reach Aegis **unsigned**; Aegis signs them with the connection's `signing-key`. Messages from the switch are signed with the switch's key and verified against the connection's `switch-certificate` list (current, previous) |
| Head | `Head` (first child) carries `ver`, `ts` (ISO-8601 with offset, the signing time) and `msgId` (unique per message; the message ID Aegis deduplicates on) |
| Acknowledgement | A message delivered asynchronously is answered `200` with `<Ack api="…" reqMsgId="…" ts="…" RspCd="Successful"/>` in the message's namespace, once stored. Anything else (no answer, 5xx) makes the switch deliver it again, unchanged |
| Amounts | BBPS: integer minor units (`amount="84000"`) with an ISO 4217 numeric currency (`currency="356"`). UPI: decimal major units (`value="499.00"`) with an alphabetic currency (`curr="INR"`), converted exactly (D31) |
| Status checks | At most 3 per transaction within 2 hours, the first no sooner than 90 seconds after the payment request (HLD §4); Aegis refuses earlier or further checks without calling the switch |

## 2. BBPS (Aegis at the customer operating unit, COU)

Namespace `http://bbps.org/schema`, prefix `bbps`. `refId` is the COU's reference for one bill:
fetch, payment and responses all carry it. `txnReferenceId` identifies one payment attempt.

| Step | Message | Direction | Answer |
|---|---|---|---|
| Validation | `BillFetchRequest` (`Head@refId`, `BillDetails/Biller@id`, `BillDetails/CustomerParams/Tag@name,@value`) | app → Aegis → switch | `BillFetchResponse` (same `refId`; `Reason@responseCode`, `@responseReason`; `BillerResponse@amount`, `@currency`, `@billNumber`, `@dueDate`, `@customerName`) |
| Payment request | `BillPaymentRequest` (`Head@refId`, `Txn@txnReferenceId`, `Amount/Amt@amount`, `@currency`, `PaymentMethod@paymentMode`) | app → Aegis → switch | `Ack` |
| Result | `BillPaymentResponse` (`Head@refId`, `Txn@txnReferenceId`, `Reason@responseCode`, `@responseReason`, `@approvalRefNum`, `Amount/Amt@amount`, `@currency`) | switch → Aegis → app | Aegis's `Ack` |
| Status check | `TxnStatusRequest` (`Head@refId`, `TxnStatusReq@txnReferenceId`) | app → Aegis → switch | `TxnStatusResponse` (`Reason@responseCode`; `Txn@txnReferenceId`, `@status`, `Amount/Amt@amount`, `@currency`) |

Outcomes: `BillPaymentResponse` with `responseReason="Successful"` and `responseCode="000"` is
SUCCESS; `Failure` with any other code is FAILED; `Pending` (a deemed transaction: the customer was
debited, the biller has not confirmed) is PENDING. A reason and a code that disagree are unparseable.
`TxnStatusResponse@status` is `SUCCESS`, `FAILURE` or `PENDING`. A fetch is accepted only with
`responseCode="000"`, the request's `refId` and a positive amount.

## 3. UPI (Aegis at the payer's PSP)

Namespace `http://npci.org/upi/schema/`, prefix `upi`. `Txn@id` identifies a transaction;
`Txn@refId` links a payment to its validation.

| Step | Message | Direction | Answer |
|---|---|---|---|
| Validation | `ReqValAdd` (`Txn@id`, `@refId` = the application's order reference; `Payer@addr`; `Payee@addr`; `Payee/Amount@value`, `@curr`) | app → Aegis → switch | `RespValAdd` (same `Txn@id`; `Resp@result` `SUCCESS`/`FAILURE`, `@maskName`) |
| Payment request | `ReqPay` (`Txn@id`, `@refId` = the validation's `Txn@id`, `@type="PAY"`; `Payer/Amount@value`, `@curr`; `Payee@addr`) | app → Aegis → switch | `Ack` |
| Result | `RespPay` (`Txn@id`, `@refId`, `@type` `PAY` or `REVERSAL`, `@orgTxnId` for a reversal; `Resp@result`, `@errCode`; `Resp/Ref[@type="PAYEE"]@settAmount`, `@settCurrency`, `@approvalNum`) | switch → Aegis → app | Aegis's `Ack` |
| Status check | `ReqChkTxn` (`Txn@orgTxnId` = the payment, `@refId`) | app → Aegis → switch | `RespChkTxn` (`Txn@orgTxnId`, `@refId`, `@type`; `Resp@result`, `@settAmount`, `@settCurrency`) |

Outcomes: `PAY` with `SUCCESS` is SUCCESS, `FAILURE` is FAILED, `PENDING` or `DEEMED` (debited,
not yet confirmed by the payee's bank) is PENDING. `REVERSAL` with `SUCCESS` is REVERSED for the
payment named by `orgTxnId` (the customer's money returned); a reversal that did not succeed is
OTHER (stored, not news). A validation is accepted only with `SUCCESS` and the request's `Txn@id`.

## 4. What Aegis adds

| Threat | Without Aegis | With Aegis |
|---|---|---|
| Forged or altered result | the COU or PSP must verify XML signatures itself, correctly | verified once, against configured certificates only (D33) |
| Signature wrapping (moved or duplicated elements) | a naive verifier checks one element and reads another | one signature over the whole document; anything else refused |
| XXE, entity expansion | parser defaults may read files or exhaust memory | DOCTYPE refused before parsing |
| Duplicate result (the switch redelivers until Acked) | paid twice, refunded twice | one fact per payment; repeats counted, Acked, not forwarded |
| Double payment request on a retry | customer debited twice | refused while one is in flight (`DUPLICATE_PAYMENT`, 409) |
| Status checks beyond NPCI's limits | penalties | refused before the switch is called (`RATE_LIMIT`, 429) |
| A deemed transaction followed by FAILURE | value delivered **and** money refunded | PENDING then FAILED forwarded in order; FAILED after SUCCESS refused (`DOWNGRADE`) |

## 5. Simplifications (honest limits)

- **Validation and status answers are synchronous** here. In NPCI's interfaces bill fetch and
  transaction status are answered asynchronously, like payments. Aegis's engine records an
  asynchronous answer to a payment request today; asynchronous answers to validations and status
  checks are open (HARDENING §12).
- **UPI amount authority.** A payer PSP has no authority that fixes the amount the way a biller or a
  gateway order does: the amount is the payer's own instruction. The validation therefore records
  the amount the application states in `ReqValAdd`, and Aegis holds every later message to it (D34).
- **Not modelled:** UPI collect requests, mandates, QR intent signing, BBPS complaints, multi-biller
  payments, encryption of PII elements and the switch's certificate chain (a configured certificate
  is trusted as such; there is no PKI path validation).
