# F25 — Partner API: signed requests, scopes, one execution per request ID

| | |
|---|---|
| Status | done |
| Milestone | M4 |
| Depends on | F06, F12, H3 |
| Design references | HLD §3.2, §4 · LLD §2, §7, §11, §14 · DECISIONS D35 |

## 1. Goal
An application that offers a payment API to partners (resellers, agents, aggregators submitting
recharges, bill payments or payouts) puts Aegis in front of it. Aegis authenticates every partner
request, refuses stale and replayed ones, holds each partner to the operations it was granted and its
rate, and executes a request ID at most once: a retry gets the first answer, never a second payment.

## 2. Scope
- In: `/partner/**` (role `PARTNER`), partner configuration (`aegis.partners`), HMAC request
  signatures with rotation, optional client-certificate subject, freshness, per-operation scopes and
  methods, per-partner and per-operation rates through `RateLimiterPort` (in-memory store),
  `partner_requests` (request IDs, sealed request and answer), proxy to the application's
  `partner-url` signed by Aegis, metrics, Admin lookup, retention.
- Out: the shared rate store (F26), the partner lab (F27), amount or value quotas per partner
  (payment-aware partner limits are not in M4; HARDENING §16).

## 3. Design
| Concern | Rule |
|---|---|
| Domain | `domain.partner`: `PartnerRequest` (who, ID, method, path, operation, signed time, request hash), `Refusal` (reasons), `Stored` (a request ID's record), `PartnerGate` (freshness, scope, what a repeated ID gets) |
| Ports | in `PartnerUseCase`; out `DirectoryPort.partner(name)`, `PartnerRequestRepository`, `PartnerClientPort`, `RateLimiterPort`, `MetricsPort.partner(…)` |
| Adapters | `PartnerController` (`/partner/**`), `PartnerHttpClient`, `PartnerRequestPersistenceAdapter` (JDBC, `INSERT … ON CONFLICT DO NOTHING`), `LocalRateLimiter` (GCRA in memory) |
| Patterns | Strategy (`RateLimiterPort`: local, Redis in F26), Idempotency Key (`partner_requests`), Chain of Responsibility (the ordered checks of LLD §14) |
| Data | V4: `partner_requests`, primary key `(partner, request_id)`, index on `updated_at` |
| Configuration | `aegis.partners.<name>` (application, secrets, client-cert-subject, rate, operations), `aegis.applications.<app>.partner-url`, `aegis.partner.*` (freshness 5 min, retention 30 days, answer timeout 30 s, limiter `local`) |
| Failure | PostgreSQL down → 503 before the application is called; application never reached → 503 and the ID is released; reached but no answer → 504 `OUTCOME_UNKNOWN`, kept: a repeat gets 504 too and the partner asks the status with a new ID |

Request ID outcomes (`PartnerGate.repeat`):

| Stored record | Same request | Different request |
|---|---|---|
| answered | the stored answer, `X-Aegis-Replayed: true` | 422 `REQUEST_ID_REUSED` |
| in progress, younger than the answer timeout | 409 `IN_PROGRESS` | 422 |
| in progress past the timeout, or unknown | 504 `OUTCOME_UNKNOWN` | 422 |

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `PartnerGateTest` | unit | freshness both ways, scope and method, every row of the table above |
| `PartnerSignatureTest` | unit | canonical string, rotation, tampered method/path/time/ID/body, empty and malformed signatures |
| `LocalRateLimiterTest` | unit | burst, steady rate, retry-after, keys independent, clock steps back |
| `AegisPropertiesTest` | unit | partner validation fails closed (unknown application, no partner-url, short secret, bad operation name or method, non-positive rate) |
| `PartnerIT` | IT | T20–T28 (LLD §13) against `FakeApplication`: answered and signed upstream, forged/tampered/stale/unknown 401, wrong certificate and operation 403, retry replays the answer once executed, 20 concurrent repeats execute once, reused ID 422, rate 429 with `Retry-After` and partners isolated, application down 503 then retry executes, timeout 504 kept, metrics, Admin lookup, retention |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] HLD, LLD, DECISIONS D35, example configuration, RUNBOOK onboarding
- [x] Code review checklist passed
