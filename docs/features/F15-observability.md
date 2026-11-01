# F15 — Observability: metrics, alerts, structured logs

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F09–F14 |
| Design references | LLD §12 · HLD §9 |

## 1. Goal
Operators see what Aegis decided and how healthy it is, are alerted on what matters, and logs can
never leak secrets or message content.

## 2. Scope
- In: `MetricsPort` + Micrometer adapter, forward-lag gauge, Prometheus alert rules, a Grafana
  dashboard, an optional observability compose overlay, ECS JSON logs on servers, log-safety test.
- Out: tracing, correlation IDs across Kafka (hardening H5).

## 3. Design
| Metric | Labels (fixed sets only) | Meaning |
|---|---|---|
| `aegis_messages_total` | provider, type, verdict (PASSED/BLOCKED/REPEAT), reason | every verdict, inbound and outbound |
| `aegis_ingress_ack_seconds` | provider, verified | time to answer a provider (p50/p99) |
| `aegis_forward_total` | application, result (delivered/retry/failed) | delivery attempts |
| `aegis_forward_lag_seconds` | — | age of the oldest due message |

No references, IDs or amounts are ever labels (cardinality and privacy).
- `MetricsPort` keeps the core free of Micrometer; services call it, `MicrometerMetrics` implements it.
- Alerts (`deploy/observability/alerts.yml`): Aegis down, forged-message burst, any amount
  mismatch/downgrade/no-validation, forward FAILED, forward lag > 5 min, slow acknowledgement, Checker lag.
- `deploy/compose.observability.yaml`: Prometheus v3.15.1 and Grafana 13.2.5 on 127.0.0.1; dashboard
  `deploy/observability/grafana-dashboard.json`.
- Servers log one ECS JSON object per line; services log references and verdicts only.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `ObservabilityIT` | integration | metrics exposed at `/actuator/prometheus` incl. `BAD_SIGNATURE`; secrets and a customer marker in a body never appear in logs (genuine and forged) |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
