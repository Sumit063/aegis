# F03 — Lab: attacks, runner and results

| | |
|---|---|
| Status | done |
| Milestone | M0 |
| Depends on | F02 |
| Design references | EXPLOIT-LAB §3, §6 |

## 1. Goal
One command that proves every flaw class is real, and (from M1) that Aegis stops it. It runs on
every push in CI, so a regression in the shop, the gateway or Aegis fails the build.

## 2. Scope
- In: `lab/compose.yaml`, one image recipe for the lab apps, one script per exploit, `lab/run.sh`,
  `lab/RESULTS.md`, the CI job.
- Out: the Aegis column (F16), the optional WordPress plugin reproductions (EXPLOIT-LAB §4).

## 3. Design
- `lab/attacks/F<n>.sh`: set up (place orders, pay), attack, then read the shop's own state. The
  result is judged only by **loss**: an unpaid order fulfilled, a paid order fulfilled twice, or a
  paid order cancelled. A script prints one line: `F<n> exploited|blocked <what happened>`.
- `lab/attacks/lib.sh`: the shared steps (switch flaws, place an order, pay, sign, send) and the
  target, chosen by `TARGET`:

| Mode | Shop | Flaw under test | Expected |
|---|---|---|---|
| `direct` | verifies provider messages itself | on | exploited |
| `careful` | the same shop | off (control) | blocked |
| `aegis` | behind Aegis (F16) | on | blocked |

  The control proves each exploit needs exactly its flaw, so a "blocked" through Aegis can only
  come from Aegis.
- The shop switches flaws at runtime (`PUT /flaws`), so one running shop serves every attack.
- F10 uses a 3-second event-ID memory in place of a real shop's days.
- `lab/run.sh` builds the jars, starts the compose project, runs every attack in every mode, writes
  `lab/RESULTS.md` and exits non-zero on any unexpected result. Works with bash 3 (macOS) and 5.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `lab/run.sh` | system (Docker) | F1–F10 exploited directly and blocked with the flaw off |
| CI job `Exploit Lab` | system | the same, on every push and pull request |
| `VulnshopApplicationTest` | Spring context | the shop starts with no flaws on |

## 5. Done when
- [x] `lab/run.sh` passes locally and in CI
- [x] `lab/RESULTS.md` committed; README, EXPLOIT-LAB and CICD updated
