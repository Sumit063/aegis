# F16 — Lab through Aegis; M1 acceptance

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F03, F09–F15 |
| Design references | EXPLOIT-LAB §1, §6 · LLD §13 |

## 1. Goal
Prove M1: the same flawed shop, behind Aegis with its business code unchanged, can no longer be
exploited, legitimate payments still go through, and every LLD test-catalogue case is automated.

## 2. Scope
- In: Aegis, PostgreSQL, Kafka and a second shop in `lab/compose.yaml`; the `aegis` mode of every
  attack; a legitimate-traffic run; failure injection against real containers; T1–T19 mapping.
- Out: the hardening batches H1–H5 (docs/features/README.md).

## 3. Design
- `shop-aegis` is the same `vulnshop` image. Only configuration differs: orders go to
  `/gw/razorpay/validations` with the shop's Aegis key, the shop holds **no** Razorpay secret, and it
  receives forwarded messages signed by Aegis. Its flaws are switched on exactly as in `direct` mode.
- The mock gateway gets a second account whose webhooks are configured to Aegis (`/in/razorpay`).
- Attacks wait for Aegis's asynchronous delivery (`settle`) before reading the shop's state.
- `lab/attacks/legit.sh`: 100 genuine payments, each with webhook **and** browser callback, every
  order must be fulfilled exactly once (zero false blocks, no double fulfilment).

## 4. Results
`lab/run.sh` (also the CI job **Exploit Lab**): see [lab/RESULTS.md](../../lab/RESULTS.md).

| | Direct, flaw on | Direct, flaw off | Behind Aegis, flaw on |
|---|---|---|---|
| F1–F10 | 10 exploited | 10 blocked | 10 blocked |
| Legitimate payments | | | 100/100 fulfilled exactly once |

## 5. LLD test catalogue (T1–T19) → automated tests
| ID | Scenario | Test |
|---|---|---|
| T1 | Valid webhook → passed, forwarded once | `CheckerIT.aGenuineWebhookPassesAndIsQueuedForTheApplication`, `ForwarderIT.deliversTheOriginalOnceSigned`, lab `legit` |
| T2 | Bad / missing / empty-key signature | `ProviderAdapterContract` (both adapters), `IngressIT`, `CheckerIT.blocksWhatCannotBeTrusted`, lab F1, F2 |
| T3 | `Host: localhost` / internal IP on a forgery | `ProviderAdapterContract.trustedLookingHeadersDoNotHelp`, `IngressIT.refusesAForgeryEvenFromLocalhost`, lab F4 |
| T4 | Same webhook 5× → one entry, forwarded once | `ForwarderIT.deliversTheOriginalOnceSigned`, `CheckerIT.razorpayPurchase`, lab F5 |
| T5 | Callback then webhook → one forwarded | `CheckerIT.razorpayPurchase`, lab F9 |
| T6 | Older than freshness → REPLAY | `CheckerIT.blocksWhatCannotBeTrusted`, `GateTest` |
| T7 | Unknown provider reference → NO_VALIDATION | `CheckerIT.blocksWhatCannotBeTrusted`, lab F3 |
| T8 | Amount differs → AMOUNT_MISMATCH | `CheckerIT.blocksWhatCannotBeTrusted`, `GateTest` |
| T9 | FAILED after SUCCESS → DOWNGRADE | `CheckerIT.razorpayPurchase`, lab F6 |
| T10 | Second payment request in flight → DUPLICATE_PAYMENT | `GatewayIT.billPaymentWithStatusChecks` |
| T11 | Status check too early / over limit → RATE_LIMIT, provider not called | `GatewayIT.billPaymentWithStatusChecks`, `GateTest` |
| T12 | Application down → delivered after recovery, in order | `ForwarderIT.survivesAnApplicationOutageAndKeepsOrder` |
| T13 | Kafka down → 503, nothing lost | `FailureIT.kafkaDownMeansNoAcknowledgement` (container paused), `IngressServiceTest` |
| T14 | Redelivery after DB commit → repeat | `CheckerIT.redeliveryIsARepeat` |
| T15 | Concurrent callback + webhook → one fact | `CheckerIT.concurrentChannelsPassOneFact`, `PersistenceIT.aLockedPaymentMakesTheNextTransactionWait` |
| T16 | Wrong application / certificate → 403 | `GatewayIT.onlyTheOwningApplicationMayCall` |
| T17 | Previous secret verifies during rotation | `ProviderAdapterContract.previousSecretStillVerifies` |
| T18 | Missing secret → startup fails | `AegisPropertiesTest`, `EvidencePropertiesTest`, `ProviderAdapterContract.registryAcceptsTheConnectionAndRefusesOneMissingASecret` |
| T19 | Never calls a provider unasked | `FailureIT.inboundTrafficNeverCallsTheProvider` |

Also: database outage under the Checker (`FailureIT.databaseOutageDelaysButLosesNothing`).

## 6. Done when
- [x] `lab/run.sh` passes locally and in CI; `./mvnw verify` green
- [x] README, HLD, EXPLOIT-LAB, lab README updated; M1 done
