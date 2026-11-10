# F26 — Shared partner rates (Redis)

| | |
|---|---|
| Status | done |
| Milestone | M4 |
| Depends on | F25 |
| Design references | LLD §11, §14 · DECISIONS D36 |

## 1. Goal
A partner's contracted rate holds however many Aegis instances serve it: the allowance lives in one
place, and when that place cannot be reached partner requests are refused, never let through.

## 2. Scope
- In: `RedisRateLimiter` (second `RateLimiterPort` strategy), one atomic GCRA script on Redis's
  clock, `aegis.partner.limiter: redis` with `redis-url` and `redis-timeout`, Redis in the deploy
  stack, environment check, alert, runbook.
- Out: Redis for anything else (Ingress limits stay per instance, D26: providers must be
  acknowledged without it); Redis replication or Sentinel (one instance per environment today).

## 3. Design
| Concern | Rule |
|---|---|
| Algorithm | the same GCRA as `LocalRateLimiter` (`Gcra.step`), in `gcra.lua`: one key per partner and per operation holding its theoretical arrival time in µs; `TIME` inside the script, so instances never disagree about the clock |
| Atomicity | one `EVALSHA` per decision (falls back to `EVAL` once after a Redis restart); no read-then-write from Java |
| Keys | `aegis:<environment>:rate:partner:<name>[:<operation>]`, each with a TTL of the time it still limits anything (+1 s): idle partners cost nothing, `maxmemory 64mb volatile-ttl` bounds the worst case |
| Failure | a command not done in 250 ms (`redis-timeout`), a refused connection or a disconnected client → `IllegalStateException` → 503 `LIMITS_UNAVAILABLE` (fail closed); the connection is opened lazily and re-established by the client, so Aegis starts and recovers without Redis |
| Isolation | only the partner API uses Redis; Ingress, Checker, Gateway, Forwarder and Admin do not, and readiness does not include it |
| Deploy | `redis:8.6.8-alpine`, no persistence (counters with TTLs), health-checked; compose sets `AEGIS_PARTNER_LIMITER=redis` |

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `RedisRateLimiterIT` | IT (real Redis) | burst then refusal with retry-after; T29 two instances, 100 concurrent requests, one allowance; key TTL; T30 Redis paused → refused in < 2 s, then recovers |
| `PartnerRedisIT` | IT (whole service) | rates kept in Redis under the environment's prefix; T30 end to end: 503 `LIMITS_UNAVAILABLE` + `Retry-After`, the application not called, the same request ID executes after recovery |
| `PartnerConfigurationTest` | unit | defaults, binding, unknown limiter, Redis without an address, retention and answer-timeout bounds |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] D36, LLD, compose, check, alert, runbook
