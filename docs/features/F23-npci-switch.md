# F23 — NPCI switch simulator (lab)

| | |
|---|---|
| Status | done |
| Milestone | M3 |
| Depends on | F21, F22 |
| Design references | NPCI.md · DECISIONS D34 · EXPLOIT-LAB.md |

## 1. Goal
A switch to run BBPS and UPI against end to end: it verifies Aegis's signed requests, answers, and
delivers signed asynchronous results with the behaviours that hurt in production (deemed, reversal,
duplicates, redelivery until Acked), plus forged and malformed messages on request.

## 2. Scope
- In: lab module `lab/npci-switch` (Spring Boot, its own XML signing with the JDK API, not Aegis's
  code), bills and UPI addresses, scripted outcomes per payment, redelivery until Acked, a test API
  that emits attack messages at a URL; vulnshop gains a bill/UPI desk behind Aegis (`/aegis/npci`)
  that records what it is told; lab keys generated per run (`lab/keys.sh`, git-ignored).
- Out: NPCI's real schemas, PKI, mTLS.

## 3. Design
- `POST /bbps/{api}`, `POST /upi/{api}`: a request not signed by Aegis's key is answered 401.
- Results go to the configured callback URL after a delay; unacknowledged ones are redelivered every
  second for a configurable time (as `mock.webhook-retry`).
- `PUT /test/next` sets the next payment's script: `SUCCESS`, `FAILURE`, `DEEMED_THEN_SUCCESS`,
  `DEEMED_THEN_FAILURE`, `DEEMED_THEN_REVERSAL` (UPI), `SILENT`; `duplicates`; `wrongAmount`.
- `POST /test/attack` sends one attack message (forged, tampered, wrapped, unsigned, XXE, stale,
  algorithm swap, `KeyInfo` key) for a transaction and returns the receiver's HTTP status.
- `GET /test/received/{api}` counts requests the switch saw (proves refusals never reached it).

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `NpciSwitchTest` | unit | request verification, every script, redelivery stops on Ack, attack messages fail verification with the simulator's own verifier except where meant to |

## 5. Done when
- [x] Module builds in the lab image; tests pass (17)
