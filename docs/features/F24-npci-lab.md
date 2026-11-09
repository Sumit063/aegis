# F24 — NPCI lab: attacks, legitimate traffic, failures

| | |
|---|---|
| Status | approved |
| Milestone | M3 |
| Depends on | F23 |
| Design references | NPCI.md §4 · EXPLOIT-LAB.md · F19 |

## 1. Goal
Show, with the switch simulator and Aegis in Docker, that every NPCI.md §4 threat is blocked, that
legitimate bill and UPI payments reach the application exactly as they happened, and that killing
components under NPCI traffic loses or doubles nothing.

## 2. Scope
- In: `lab/npci.sh` (scenarios below, writes `lab/NPCI.md`), NPCI traffic added to
  `lab/failures.sh`, a CI job, M3 benchmark numbers for signing and verification.

## 3. Scenarios
| # | Scenario | Expected through Aegis |
|---|---|---|
| N1 | Forged result (unknown key) | 401, application told nothing |
| N2 | Signature wrapping (signed body moved, forged one in its place) | 401 |
| N3 | Tampered amount after signing | 401 |
| N4 | XXE / entity expansion | refused, no file read, no expansion |
| N5 | Algorithm swap (HMAC with the public key; RSA-SHA1) | 401 |
| N6 | `KeyInfo` carrying the attacker's key | 401 |
| N7 | Stale result (signed 2 h ago) | `REPLAY` |
| N8 | Result redelivered 5× | Acked each time, forwarded once |
| N9 | Retry with a new transaction while one is in flight | 409, switch saw one request |
| N10 | Status check after 10 s | 429, switch not called |
| N11 | Genuine result for another amount | `AMOUNT_MISMATCH` |
| N12 | FAILURE after SUCCESS | `DOWNGRADE` |
| L1–L6 | BBPS success, deemed → success, failure; UPI success, deemed → reversal, failure | application sees exactly the real sequence |
| Load | 300 bill and UPI payments concurrently | every one settled exactly once |

## 4. Done when
- [ ] `lab/npci.sh` green locally and in CI; `lab/failures.sh` with NPCI traffic green
- [ ] M3 marked done in README, HLD, features README; site roadmap
