# Exploit Lab (M0)

Reproducible attacks against a realistic merchant integration, run once **directly** and once
**through Aegis**. Design: [docs/design/EXPLOIT-LAB.md](../docs/design/EXPLOIT-LAB.md).

| Part | What it is | Port |
|---|---|---|
| [`mock-gateway`](mock-gateway) | Razorpay-compatible provider: orders, payments, signed webhooks and Checkout results | 8090 |
| [`vulnshop`](vulnshop) | Reference merchant; each integration flaw F1–F10 is a switch (`shop.flaws`) | 8091 (direct), 8092 (behind Aegis) |
| [`npci-switch`](npci-switch) | NPCI-style BBPS and UPI switch ([NPCI.md](../docs/design/NPCI.md)): verifies Aegis's signed requests, delivers signed results, emits attack messages | 8093 |
| Aegis + PostgreSQL + Kafka + Redis | the system under test, configured by [`aegis.yaml`](aegis.yaml); a second instance (`aegis-2`) serves partners only | 8080 / 8081, 8082 |

```bash
lab/run.sh            # build, start, run every attack, write lab/RESULTS.md (needs Docker, jq, openssl)
KEEP=1 lab/run.sh     # same, and leave the lab running for manual attacks
TARGET=direct lab/attacks/F5.sh   # one attack (lab running)
```

Every attack runs in each mode and is judged only by the shop's loss:

| Mode | Shop | Flaw under test | Expected |
|---|---|---|---|
| `direct` | verifies provider messages itself | on | exploited |
| `careful` | the same shop | off (control) | blocked |
| `aegis` | behind Aegis (same image, only URLs and credentials differ) | on | blocked |

Then 100 legitimate payments (webhook and browser callback each) go through Aegis: every order must
be fulfilled exactly once.

Latest results: [RESULTS.md](RESULTS.md). CI runs the lab on every push.

## Failure lab

```bash
lab/failures.sh                   # traffic through Aegis while Aegis, Kafka, PostgreSQL are each SIGKILLed
FAULTS=kafka DOWN=10 lab/failures.sh
```

Genuine payments keep flowing while each component is killed and restarted in turn; the mock
gateway retries unacknowledged webhooks (`compose.failures.yaml`). Every order the shop placed must
end with exactly one fulfilment. Latest results: [FAILURES.md](FAILURES.md); CI runs it on every push.

## NPCI lab (M3)

```bash
lab/npci.sh                       # BBPS and UPI through Aegis to the switch; writes lab/NPCI.md
KEEP=1 lab/npci.sh                # same, and leave the lab running
```

The shop's bill desk pays bills (BBPS) and UPI requests through Aegis; the switch's results come back
through Aegis to the desk (`/aegis/npci`). Legitimate flows (success, deemed then success, failure,
deemed then reversal) must reach the desk exactly as they happened; every attack in
[NPCI.md](../docs/design/NPCI.md) §4 must be stopped; 300 concurrent payments must settle once. The
failure lab runs BBPS bill payments alongside checkout. Latest results: [NPCI.md](NPCI.md).

NPCI keys (the switch's and Aegis's RSA key pairs) are generated once per checkout by
[`keys.sh`](keys.sh) into `lab/.env` (git-ignored), which compose reads.

| Switch endpoint | Purpose |
|---|---|
| `POST /bbps/{BillFetchRequest,BillPaymentRequest,TxnStatusRequest}`, `POST /upi/{ReqValAdd,ReqPay,ReqChkTxn}` | the switch's APIs; a request not signed by Aegis's key is 401 |
| `PUT /test/next` | what the next payment does: `SUCCESS`, `FAILURE`, `DEEMED_THEN_SUCCESS`, `DEEMED_THEN_FAILURE`, `DEEMED_THEN_REVERSAL`, `SUCCESS_THEN_FAILURE`, `SILENT`; `duplicates`; `wrongAmount` |
| `POST /test/attack?kind=…&txn=…` | delivers one attack message about a transaction: `forged`, `tampered`, `wrapped`, `unsigned`, `xxe`, `stale`, `sha1`, `hmac`, `keyinfo`, `replay` |
| `GET /test/received`, `GET /test/txn/{id}` | what the switch saw (proves refused requests never reached it) |

## Partner lab (M4)

```bash
lab/partners.sh                   # partners → recharge API, directly and through Aegis; writes lab/PARTNERS.md
KEEP=1 lab/partners.sh            # same, and leave the lab running
java lab/bench/PartnerBench.java 3000 32 [copies]   # throughput and latency (lab running)
```

The shop offers a recharge API to partners (`/partner-api/recharges`, `/partner-api/status/{id}`).
Direct (`shop-direct`), it trusts a static API key and the body, like many partner APIs: a replayed
request, an amount changed in transit and a retry storm all execute (D1–D3). Behind Aegis
(`shop-aegis`) it runs only what Aegis signed, and partners call Aegis's `/partner/…` on either of
two instances sharing PostgreSQL and Redis: every attack (forged, altered, stale, another partner's
secret, retry storms, reused IDs, operations and methods not granted, path traversal, floods) is
stopped before the shop, rates hold across both instances, Redis and shop failures never cause a
second execution, and 500 recharges sent twice at once each run exactly once. `GET
/lab/partner-executions` on either shop shows how often each request ran. Latest results:
[PARTNERS.md](PARTNERS.md).

Everything here uses fake, lab-only secrets and runs on a private Docker network. Nothing in the
lab talks to a real gateway or switch.

## Mock gateway

| Endpoint | Like Razorpay? | Purpose |
|---|---|---|
| `POST /v1/orders` | yes | create an order (Basic auth `key_id:key_secret`) |
| `GET /v1/orders/{id}`, `/v1/orders/{id}/payments`, `/v1/payments/{id}` | yes | read the gateway's truth |
| `POST /test/checkout` | test-only | the customer pays: returns the Checkout fields (`razorpay_payment_id`, `razorpay_order_id`, `razorpay_signature`) and delivers the signed webhook |
| `POST /test/events/{id}/redeliver` | test-only | the provider retries a webhook |
| `GET /test/events/{id}` | test-only | the exact signed bytes of an event, as an attacker who saw a log would have them |

```bash
./mvnw -pl lab/mock-gateway spring-boot:run      # http://localhost:8090
```

## Vulnshop

| Endpoint | Used when | Purpose |
|---|---|---|
| `POST /orders {"amount"}` | always | place an order; creates the gateway order (directly, or through Aegis) |
| `GET /orders/{id}` | always | the order's state and `fulfilments` (the measure of loss) |
| `POST /webhooks/razorpay` | direct | the gateway's webhook, verified by the shop |
| `POST /payment/callback?order=SHOP-n` | direct | the browser's Checkout result, verified by the shop |
| `POST /aegis/messages` | behind Aegis | messages Aegis forwarded, verified by `X-Aegis-Signature` |
| `GET /aegis/return` | behind Aegis | where Aegis redirects the browser; display only |

```bash
./mvnw -pl lab/vulnshop spring-boot:run -Dspring-boot.run.arguments=--shop.flaws=F5,F9
```
