# Benchmark

The whole pipeline, measured end to end with a reproducible program. No other throughput claim is
made anywhere.

```
provider (Bench) → Ingress → Kafka → Checker → PostgreSQL → Forwarder → shop (fulfils)
```

## How to run

```bash
KEEP=1 lab/run.sh                       # start the lab (and run every attack once)
java lab/bench/Bench.java 2000 32       # <payments> <concurrency>
docker compose -f lab/compose.yaml down -v
```

Per payment: one genuine signed `payment.captured` webhook; 20 % are sent again (a provider retry);
10 % get an extra forged webhook. Orders are created beforehand through the Gateway API and are not
timed. The program fails unless **every order is fulfilled exactly once**. End-to-end latency is from
sending the webhook to the shop recording the fulfilment.

## Environment (2026-11-01)

Apple M5 (10 cores, 16 GB); Docker on Colima, 4 vCPU / 6 GB; every component in one VM: Aegis (one
instance, all roles), PostgreSQL 17, Kafka 4.2 (one broker, `acks=all`, flush per message), mock
gateway, two shops. Default Aegis settings except the lab's raised request limit (the limiter is
tested separately in `LimitsIT`).

## Results

| Run | Requests | Throughput | Acknowledgement p50 / p95 / p99 | End-to-end p50 / p95 / p99 | Fulfilled exactly once |
|---|---|---|---|---|---|
| Steady (concurrency 4) | 1,318 (1,000 payments) | 740 req/s | 4.8 / 9.7 / 13.7 ms | 362 / 920 / 1,118 ms | 1,000 / 1,000 |
| Burst (concurrency 32) | 2,602 (2,000 payments) | 1,104 req/s | 27.4 / 50.0 / 62.2 ms | 2,026 / 3,095 / 3,511 ms | 2,000 / 2,000 |

Every forged message was answered 401; no order was fulfilled twice; dead letters: 0.
Resource use after the runs: Aegis ~570 MB, Kafka ~380 MB, PostgreSQL ~75 MB, all at a few % CPU.

**Tuning driven by this benchmark:** the Forwarder first ran one 50-payment pass every 500 ms, which
capped delivery near 100 payments/s; under the burst, end-to-end p50/p99 were **9.4 s / 19.3 s**.
It now drains continuously while work is due (bounded per tick): **2.0 s / 3.5 s** for the same burst.

## Reading these numbers

- Acknowledgement (what a provider waits for, Razorpay allows 5 s) stays in milliseconds even in a
  burst: verification and one durable Kafka write.
- End-to-end in the burst is queueing: 2,000 payments arrive in ~2.4 s; the Checker (3 consumers)
  and the Forwarder work through them. More instances or partitions add capacity (`SKIP LOCKED` and
  consumer groups let them share work).
- One laptop VM, every component sharing 4 vCPUs: a lower bound for a real host, not a capacity plan.
