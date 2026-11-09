# Exploit Lab (M0)

Reproducible attacks against a realistic merchant integration, run once **directly** and once
**through Aegis**. Design: [docs/design/EXPLOIT-LAB.md](../docs/design/EXPLOIT-LAB.md).

| Part | What it is | Port |
|---|---|---|
| [`mock-gateway`](mock-gateway) | Razorpay-compatible provider: orders, payments, signed webhooks and Checkout results | 8090 |
| [`vulnshop`](vulnshop) | Reference merchant; each integration flaw F1–F10 is a switch (`shop.flaws`) | 8091 (direct), 8092 (behind Aegis) |
| [`npci-switch`](npci-switch) | NPCI-style BBPS and UPI switch ([NPCI.md](../docs/design/NPCI.md)): verifies Aegis's signed requests, delivers signed results, emits attack messages | 8093 |
| Aegis + PostgreSQL + Kafka | the system under test, configured by [`aegis.yaml`](aegis.yaml) | 8080 / 8081 |

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
