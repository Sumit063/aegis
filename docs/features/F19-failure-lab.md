# F19 — Failure lab: crashes under load

| | |
|---|---|
| Status | done |
| Milestone | M2 |
| Depends on | F16 (lab through Aegis), H5, H8 |
| Design references | RUNBOOK §2 · HARDENING §12 (items 19, 20) · EXPLOIT-LAB |

## 1. Goal
Show, with real processes and real kills, what RUNBOOK §2 promises: while genuine payments flow,
Aegis, Kafka and PostgreSQL can each die without warning and come back, and every order the shop
placed is still fulfilled exactly once at the shop: nothing acknowledged lost, nothing doubled.

## 2. Scope
- In: `lab/failures.sh` (SIGKILL each component in turn under continuous traffic, restart, wait
  for convergence, write `lab/FAILURES.md`), webhook retries in the mock gateway (opt-in, as
  Razorpay retries unacknowledged webhooks), a CI job.
- Out (explicitly): disk loss (the single-broker profile does not survive it, RUNBOOK §2), network
  partitions, the production three-broker profile.

## 3. Design
- **Traffic.** The shop behind Aegis places orders through `/gw/razorpay/validations`; the customer
  pays in the mock's Checkout (the gateway sends the webhook to Aegis) and the browser posts the
  signed callback to Aegis. An order Aegis cannot create (it is down) fails at once and the
  customer tries again: only orders the shop actually placed are counted.
- **Faults, in turn:** `docker compose kill -s SIGKILL <component>`, down for `DOWN` seconds (5),
  then `up --wait`. Aegis is killed mid-pipeline (records in Kafka not yet checked, deliveries in
  flight); Kafka without a clean shutdown (acknowledged records must be on disk); PostgreSQL under
  the Checker and the Gateway API.
- **What each side does meanwhile.** Gateway: retries an unacknowledged webhook once a second
  (`mock.webhook-retry`, 5 minutes here; 0, the default, keeps every attack's deliveries exact).
  Aegis: refuses to acknowledge what it cannot store (503), resumes from committed offsets, re-sends
  a delivery whose lease expired with the same `id`. Shop: fulfils an order once however often it
  hears of it (the application's side of the contract, RUNBOOK §2).
- **Pass:** after the faults, within `SETTLE` seconds (180), every placed order has exactly one
  fulfilment. Otherwise the script exits 1, and CI fails.

## 4. Invariants
2. Nothing acknowledged before durable storage: an acknowledged webhook that vanished would leave
   its order unfulfilled (no status checks are made). 3. Blocked never forwarded: a doubled or
   wrong fulfilment fails the run. 5. Aegis never calls the provider on its own: recovery relies on
   the gateway's retries only. Others not affected.

## 5. Tests
| Test | Level | Covers |
|---|---|---|
| `MockGatewayTest.anUnacknowledgedWebhookIsRetriedUntilTheMerchantAcknowledges` | unit | the gateway's retries: same event ID, stops after a 2xx |
| `lab/failures.sh` (CI job *Failure lab*) | system | SIGKILL of Aegis, Kafka, PostgreSQL under traffic → every order fulfilled exactly once |

## 6. Remaining
- One kill per component per run; timings vary between runs (what was in flight differs), which is
  the point, but a single green run is not a proof for every interleaving.
- HARDENING §12 item 20 (forwarder crash mid-delivery) is now exercised by the Aegis kill, not by a
  test that stops exactly between send and record.
