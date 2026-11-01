# H5 — Operations: correlation, readiness, runbook, benchmark

| | |
|---|---|
| Status | done |
| Depends on | H4 |
| Design references | HARDENING §9, §11 · DECISIONS D28 |

## Changes
- `KafkaHealth` (`kafka`) joins readiness with the database.
- `CorrelationFilter`: `X-Request-Id` (validated, else a new UUID) → MDC → `IngressRecord.correlationId`
  → restored by `CheckerListener`; deliveries carry `X-Aegis-Message-Id`.
- `ForwarderJob` drains while work is due (bounded 5 s per tick).
- [RUNBOOK](../engineering/RUNBOOK.md): guarantees and their limits, situations, admin operations,
  dead-letter replay, backup/restore/DR, secret rotation.
- [BENCHMARK](../engineering/BENCHMARK.md) + `lab/bench/Bench.java`; the lab shop records `fulfilled_at`.
- Lab: Aegis request limit raised for the benchmark; the shop behind Aegis keeps event IDs 1 h.

## Tests
| Test | Covers |
|---|---|
| `FailureIT.kafkaDownMeansNoAcknowledgement` | readiness 503 while Kafka is paused, 200 after |
| `ObservabilityIT.echoesTheCorrelationId` | caller ID kept and echoed; unsafe IDs replaced |
| `java lab/bench/Bench.java` | 2,000 and 1,000 payments fulfilled exactly once under load |
