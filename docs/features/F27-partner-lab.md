# F27 — Partner lab, benchmark and metrics on every server

| | |
|---|---|
| Status | done |
| Milestone | M4 |
| Depends on | F25, F26 |
| Design references | LLD §12, §14 · DECISIONS D35, D36 · HARDENING §16 · BENCHMARK |

## 1. Goal
Show, the way the Exploit Lab does for providers, that partner attacks work against a partner API on
its own and are stopped through Aegis; that two Aegis instances behave as one; that failures never
cause a second execution. Measure the cost. Make every server's metrics and alerts visible.

## 2. Scope
- In: the shop's recharge API in two modes, Redis and a second (partner-only) Aegis instance in the
  lab, `lab/partners.sh` (23 scenarios) and its CI job, `lab/bench/PartnerBench.java`; Prometheus
  and Grafana (provisioned data source and dashboard, partner panels) deployed with every server,
  checked after every deploy.
- Out: alert delivery (Alertmanager, paging).

## 3. Design
| Concern | Rule |
|---|---|
| Shop | `PartnerApiController`: executes everything that gets through and counts executions per request ID; `direct` mode checks a static key, `aegis` mode only Aegis's signature (LLD §14) |
| Lab | partners `acme` (normal) and `flood` (60/min, burst 10); requests alternate between `aegis` and `aegis-2`; signatures computed with openssl (no shared code with Aegis) |
| Scenarios | D1–D3 direct (must be exploited), L1–L4 legitimate, P1–P12 attacks and misuse, X1–X3 Redis paused, shop stopped, shop paused (no answer), Load: 500 recharges each sent twice at once |
| Metrics | `deploy.sh` always runs `compose.observability.yaml`; ports per environment in `.env` (staging 9190/3100); Grafana's admin password generated into `.env`; `check.sh` asks Prometheus whether it scrapes Aegis and has the rules |

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `PartnerApiControllerTest` | unit (lab) | direct mode runs every copy; aegis mode runs only what Aegis signed, refuses an altered body and the static key |
| `lab/partners.sh` | scenario (CI job `Partner lab`) | 23 scenarios ([PARTNERS.md](../../lab/PARTNERS.md)) |
| `PartnerBench` | benchmark | throughput, latency, exactly-once under a retry storm |
| `deploy/check.sh` | after every deploy | Prometheus scrapes Aegis; alert rules loaded |

## 5. Done when
- [x] Partner lab green locally and in CI; benchmark recorded
- [x] Staging runs Prometheus and Grafana; check passes
- [x] HARDENING §16, BENCHMARK, RUNBOOK, CICD, lab README
