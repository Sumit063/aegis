# Aegis — Decisions

Each decision: what, why, and what was rejected. Change a decision only by editing this file first.

## Stack

| Area | Choice | Why |
|---|---|---|
| Language | **Java 25 (LTS)** | Mature, widely known; virtual threads make blocking I/O cheap |
| Framework | **Spring Boot 4** (Spring MVC on virtual threads) | Production-proven; simpler to debug than reactive at this I/O pattern |
| Build | Maven multi-module | One deployable; lab apps build alongside |
| Ingress buffer | **Apache Kafka 4.x (KRaft)** | D6 |
| Database | **PostgreSQL 17** | D7 |
| DB access | Spring Data JPA + explicit native SQL where needed | D15 |
| Migrations | Flyway | Versioned SQL |
| HTTP client | JDK `HttpClient` | Built in, per-request timeouts, mTLS |
| Crypto | JDK (HMAC, AES-GCM, RSA, XML-DSig via `javax.xml.crypto`), Nimbus JOSE for JWE | Standard, audited implementations |
| Observability | JSON logs · Micrometer · Prometheus · Grafana | |
| Tests | JUnit 5 · Testcontainers · WireMock · ArchUnit | Real infrastructure in tests |
| Deployment | Docker Compose, single host per environment | Kubernetes only with more than one host |

## Decisions

### D1 — Aegis is a security gateway, nothing more
Aegis checks payment messages (authenticity, freshness, uniqueness, match with the validation, no
downgrade), records them, and passes or blocks them. It holds the provider secrets.
- **Why:** the CVEs it targets (HLD §2) are all failures of these checks; business logic differs per
  application, but the checks don't. A small scope keeps it fast, easy to adopt and easy to audit.
- **Rejected:** owning orders, state machines and fulfilment (an earlier design). It duplicated the
  application's logic, needed a provider confirmation call for every message, and made Aegis a
  dependency of business decisions.

### D2 — Aegis never calls a provider on its own
Every outbound call (validation, payment request, status check) starts with an application request.
- **Why:** no surprise traffic, no polling cost, no provider rate-limit or penalty caused by Aegis,
  and the application stays in control of its payment flow.
- **Consequence:** a missing callback is the application's to chase, using a status check through
  Aegis, which enforces the provider's limits.

### D3 — Two tables: `validations` and `payments`
Every payment system has two steps: a validation that fixes the amount, then payment messages that
refer to it.
- `validations`: **one row per validation**: its whole cycle (request and reply) in a JSON field,
  plus the authority's amount and a summary status (`CREATED`, `FAILED`, `PAID`).
- `payments`: **one row per payment**: every message of that payment (payment request and its
  acknowledgement, callback, webhooks, async responses, status-check requests and replies), each
  with its verdict, appended to a JSON field, plus a summary status (latest passed outcome).
- **Why one row per cycle:** one place to read everything about a payment; one lock covers all its
  checks; no joins.
- **Rejected:** one row per message (many rows to assemble per payment); separate tables per message
  type; one table for both steps (validations are the reference payments are checked against).

### D4 — Store originals; forward them unchanged
Every message is stored exactly as received (encrypted at rest) and forwarded to the application
unchanged, together with Aegis's verdict and the provider's signature headers.
- **Why:** evidence for disputes and investigations; existing application code keeps parsing the
  provider's format; the application can re-verify the provider's signature (it doesn't have to
  trust Aegis blindly).

### D5 — Uniqueness and ordering are enforced by the database
- One row per payment: `UNIQUE (provider, payment_ref)`.
- All checks for a payment run inside one transaction holding the validation row lock
  (`FOR UPDATE`), reading and writing the payment row: same message (message ID already in the
  row), same fact (status already equal), downgrade (validation `PAID`), double payment.
- **Why:** correct with any number of instances and with redelivery from Kafka; no in-memory caches
  to keep consistent.

### D6 — Kafka as the ingress buffer
Ingress verifies the signature, writes the message to Kafka, and only then acknowledges the provider.
- **Why:** acknowledgement within provider deadlines (Razorpay: 5 s) without depending on the
  database; bursts and retry storms are absorbed outside PostgreSQL; other consumers (risk,
  analytics) can read the same stream later.
- Settings: idempotent producer, `acks=all`; single broker uses `log.flush.interval.messages=1` so
  an acknowledgement means "on disk". The consumer commits offsets after the database commit; the
  unique keys absorb redelivery.
- Records carry the body sealed with the evidence key: Kafka's disk never holds clear text.
- Producer timeouts are 2–3 s, so a broker outage becomes a fast 503 (the provider retries) rather
  than a missed acknowledgement deadline.

### D7 — PostgreSQL
| Need | PostgreSQL feature |
|---|---|
| Duplicate and same-fact guarantees under concurrency | unique keys, partial unique indexes, `ON CONFLICT` |
| Race-free checks per validation | row locks |
| Cheap "what still needs forwarding?" | partial index |
| Live settings on every instance | `LISTEN / NOTIFY` |
MySQL is the runner-up (no partial indexes, no `LISTEN/NOTIFY`). Elasticsearch/OpenSearch is not
needed: lookups are exact by reference.

### D8 — Secure link to the application
mTLS (TLS 1.3) both ways, `X-Aegis-Signature` (HMAC-SHA256 over timestamp and body) with a 5-minute
timestamp window, and optional JWE body encryption to the application's public key.
- **Why:** mTLS authenticates both ends; the signature protects messages behind TLS-terminating
  proxies and allows replay rejection; JWE keeps bodies private even from proxies.

### D9 — Four environments, selected by Spring profile
`local`, `dev`, `staging`, `prod`. Shared settings in `application.yaml`, differences in
`application-<profile>.yaml`. Secrets never in profile files: each environment's keys come from its
own `.env` as environment variables, with no defaults outside `local`. Developer Mac runs local/dev,
the Ubuntu host runs staging; production gets its own host later without design changes.

### D10 — Runtime settings in PostgreSQL with in-memory snapshots
Operational settings (forward backoff, per-application pause, log levels) are editable through Admin
without a deployment. Stored in `config`; each instance holds an immutable snapshot refreshed by
`LISTEN/NOTIFY` plus a 30-second poll. Secrets, addresses and certificates are never runtime-editable.

### D11 — Branching: environment branches with promotion
`master` = production, `staging`, `dev`. Work on `feature/<ID>-<slug>` from `master`, PR into
`dev`, then promote `dev → staging → master`. Hotfixes from `master`, merged back into `staging` and
`dev`.

### D12 — CI on GitHub, CD pulled by the host
CI on GitHub-hosted runners; images in GitHub Container Registry tagged by commit SHA. The Ubuntu
host runs a self-hosted runner (outbound connection only) that deploys `staging`, health-gated with
rollback. Secrets stay on the host. Details: `docs/engineering/CICD.md`.

### D13 — Hexagonal architecture, enforced
Domain and application code are framework-free; adapters and providers plug in. ArchUnit checks the
rules on every build.

### D14 — One small adapter interface per provider
Each provider implements `ProviderAdapter` (LLD §6): verify, decrypt, parse, acknowledge, prepare
outbound calls, enquiry limits, freshness. Adding a provider = adding an adapter and passing the
shared contract tests; the engine and tables don't change.
- Adapters are discovered with `java.util.ServiceLoader`, not Spring: they stay plain Java and
  framework-free, and the registry is the one place that knows them.

### D15 — JPA with explicit locking
Spring Data JPA behind persistence ports; native SQL for `ON CONFLICT` inserts and `FOR UPDATE`
locks; `open-in-view=false`; `ddl-auto=validate`; Flyway owns the schema.

### D16 — Quality gates and feature-by-feature delivery
Pre-commit (format, secret patterns), commit-msg (Conventional Commits), pre-push (`./mvnw verify`);
the same in CI. Coverage ≥ 80 % on domain and application. Each feature has a reviewed design note
before code.

### D17 — Secrets: fail-closed and rotatable
Missing, empty or short secrets stop startup; empty optional slots are dropped, never treated as an
empty key (CVE-2026-41432). Secret lists (current + previous) allow rotation without downtime.
- Minimums: provider secrets 16 characters (Razorpay key secrets are 24, so 32 would reject real
  keys), secrets Aegis shares with applications 32 (they are generated).
- Secrets are a type (`Secret`) that never prints its value and compares in constant time.
- Connections live in a per-environment `aegis.yaml` with `${ENV}` placeholders, because nested maps
  do not map cleanly to environment variables; the secrets themselves stay in `.env`.

### D18 — Leaked shared secrets: accepted residual risk
A passive gateway cannot detect a correctly signed message made with a stolen HMAC secret.
Mitigations: secrets exist only in Aegis, rotation, and application-initiated status checks for
high-value handovers. Asymmetric providers (NPCI) are not affected.
- **Rejected:** automatic confirmation calls to the provider (violates D2).

### D19 — Exploit Lab: reference application + mock provider
Vulnerable plugin versions are not always still published, and a real sandbox cannot simulate every
attack. A reference application with one switch per flaw, plus a Razorpay-compatible mock, makes each
exploit reproducible.

### D20 — Money, IDs, time
Integer minor units + ISO currency, never floats; database identity keys; `timestamptz` in UTC.

### D21 — Razorpay specifics
- A **signed Checkout callback is a success** (Razorpay signs only successful payments); an unsigned
  failure form is blocked as `BAD_SIGNATURE`, and the failure reaches the application by webhook.
- `payment.authorized` is **PENDING**, not success: Razorpay auto-refunds uncaptured payments.
- A refund's amount is the refunded part, so it is **not compared** with the order amount.
- **Live keys only in production**, test keys everywhere else: startup fails otherwise, so a
  non-production environment can never move real money.
- A webhook without `X-Razorpay-Event-Id` gets the ID `body:<sha256 of body>`, so a replay of the
  same bytes is still the same message.

### D22 — JSON fields as text, mapped by the adapter
`validations.data` and `payments.messages` are `jsonb` columns mapped as `String` and (de)serialised
by the persistence adapter with Jackson into explicit stored records.
- **Why:** the stored shape is visible in one place and can evolve with a version; the domain sees
  only what checks need (no evidence, no delivery history); no dependency on Hibernate's JSON
  format-mapper integration.
- `payments` has no amount columns: the authority's amount is the validation's.
- Evidence keys are generated on the host (`AEGIS_EVIDENCE_KEY` in `.env`) and never leave it.

## Design review log

| # | Issue | Fix |
|---|---|---|
| R1 | A forged message reusing a real message ID would make the genuine one look like a duplicate | Untrusted messages get their own row and never touch a genuine payment's row (LLD §5) |
| R2 | Callback and webhook report the same payment, so the application could act twice | Same-fact check on the locked payment row (D5) |
| R3 | A late `FAILED` for an earlier attempt has a different payment reference | Downgrade rule is per validation, not per payment |
| R4 | Razorpay's browser callback carries no amount | Its signature binds payment to order; the order's amount is the validated one |
| R5 | Application status checks could break NPCI limits and cause penalties | Limits enforced in Aegis per adapter |
| R6 | A message replayed after its row is purged would look new | Retention (180 d) far exceeds every freshness window |
| R9 | One row per payment could grow without bound under repeated messages | Repeats only increase a counter; bodies capped at 64 KB |
| R10 | A late PENDING after SUCCESS (out-of-order webhooks) would move a payment backwards; a late SUCCESS after FAILED is genuine (late authorization) | Downgrade by outcome rank; SUCCESS and FAILED share a rank (found in F04) |
| R11 | A payment request's own acknowledgement set the status to PENDING, so NPCI's deemed answer looked like a duplicate | Acknowledgements are not news; in flight = passed request without a final answer (found in F04) |
| R7 | Leaked HMAC secret | Accepted residual risk with mitigations (D18) |
| R8 | Retrying a payment request while the first is pending could debit twice | `DUPLICATE_PAYMENT` while in flight |

An earlier, larger design (orders with state machines, provider confirmation, rechecks, outbox and
events tables) was replaced by this one on 2026-11-01; it remains in the git history.
