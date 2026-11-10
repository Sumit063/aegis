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
Operational settings (today: per-application pause of forwarding) are editable through Admin without
a deployment. Stored in `config`, versioned with who changed them; each instance holds an immutable
snapshot reloaded every 5 seconds. Secrets, addresses and certificates are never runtime-editable.
- **Rejected:** `LISTEN/NOTIFY` (an extra long-lived connection per instance for a few seconds of
  latency that no setting needs).

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
- **Late authorization** is documented by Razorpay (a payment shown as failed can be authorized and
  captured later), so the adapter allows SUCCESS after FAILED (D24).
- `refund.processed` carries the refund's ID (fact reference) and amount; a refund without them is
  unparseable. Event types are an explicit allowlist; disputes and settlements are `UNSUPPORTED`
  until modelled (open assumption: their semantics are not used for any decision).
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

### D23 — Payment requests are recorded before they are sent
Under the validation's lock, a permitted payment request is written as an in-flight entry, the
transaction commits, and only then is the request sent; the reply is attached afterwards.
- **Why:** with no network call inside a transaction (invariant 6), checking and sending cannot be
  atomic; writing first means a concurrent request already sees one in flight, so two can never go
  out (T10). If the provider is unreachable, the request stays in flight until a status check or a
  result settles it, consistent with D2 (Aegis never decides on its own).
- **Applications authenticate** with a per-application API key (bearer) and, where configured, a
  client-certificate subject checked behind the TLS-terminating proxy; a connection is usable only
  by the application it belongs to.

### D24 — Payment states change by an explicit table, facts by identity
A payment's state changes only by the transitions in `domain.check.Transitions` (LLD §3 step 12);
there is no ordering of outcomes to reason from. Three identities: the **message** (provider event
ID, dedupes retries), the **payment** (provider transaction), the **fact** (outcome + fact reference,
e.g. each refund's ID).
- **Why:** a ranking cannot express that a second partial refund is new, that a refund needs a prior
  capture, or that SUCCESS after FAILED is real for one provider and invalid for another.
- Late success after failure is a **provider policy** declared by the adapter (Razorpay documents
  late authorization), never assumed.
- A second payment succeeding for a validation already paid is `DUPLICATE_PAYMENT`: stored, alerted,
  not forwarded as news to fulfil (the merchant refunds it).
- Authenticated events Aegis does not interpret are `UNSUPPORTED`: acknowledged, stored, never forwarded.
- Refunds carry their own amount; all refunds of a payment never exceed what was paid. History is
  append-only, so a refund never erases the capture.
- **Rejected:** numeric ranking of outcomes (M1 first cut); forwarding unsupported events with
  outcome OTHER.

### D25 — Identities are enforced by the database
`payment_messages (provider, message_id)` and `payment_facts (payment_id, outcome, fact_ref)` are
primary keys written in the same transaction as the payment row.
- **Why:** row locks make the checks correct, but a constraint makes the guarantee independent of
  every code path, present and future (review: "do not rely solely on application-level checks").
- A message ID is unique per provider, not per payment: seen anywhere means a repeat.
- **Rejected:** moving the whole message history out of the JSON field: history per payment is
  bounded (repeats only count, forgeries never join), and the identities are what needs a constraint.

### D26 — Unauthenticated traffic has bounded cost
- A message with a bad signature gets **401** (the provider is told it was not accepted) and is kept
  as evidence only within a per-connection quota (`aegis.limits.forgeries-per-minute`, default 60);
  beyond it, it is counted and dropped. Untrusted rows are kept 7 days, not 180.
- `/in/{connection}` has a per-connection request rate (429) and in-progress cap (503), applied
  before the body is read. Providers retry both: genuine messages are delayed, never lost.
- The Checker retries outages forever (nothing skipped) but sends a record that is itself bad to
  `aegis.inbound.dlt` after three attempts, so one poison record cannot stop a partition.
- Limits are per instance and approximate across instances: they bound load, they never decide
  anything about money.

### D27 — Destinations are configuration, checked against SSRF
Provider base URLs, forward URLs and return URLs come only from `aegis.yaml`. Every one is checked at
startup and before each send: http(s), never link-local or metadata addresses, `https` in production
unless a private network is explicitly allowed. Private and loopback addresses are allowed on
purpose (self-hosted merchants).
- The browser's signed result is bound to the payment and timestamped (`payment`, `ts`).

### D28 — Operability
- **Readiness** includes Kafka and the database: a process that cannot store messages takes no traffic.
- **Correlation:** `X-Request-Id` (or a new one) is logged with every line, written into the Kafka
  record and restored by the Checker; deliveries carry `X-Aegis-Message-Id` (= the stable `id`).
- **Forwarder** drains while work is due (measured: a fixed pace capped it near 100 payments/s).
- Guarantees are stated in RUNBOOK §2: acknowledged = durably in Kafka; at-least-once delivery with a
  stable `id`; facts at most once by database keys. Exactly-once side effects need the receiver to
  dedupe by `id`; single-host disks need backups.

### D29 — Required fields, clock skew, refunds as facts, exact guarantees
Post-hardening review (HARDENING §13), each finding verified against the code first.
- **Fail closed on provider fields.** An adapter rejects a message lacking a field its provider's
  documented contract carries (Razorpay: `created_at`, payment `id`, integer `amount`, `currency`;
  refund ID, amount, currency). Nothing is defaulted (a missing currency was `INR`). A field is
  optional only where the protocol has none (the signed checkout callback carries no amount; the
  order signature binds it to the validated order).
- **Clock skew.** A timestamp more than the provider's skew ahead (SPI `maxClockSkew()`, default
  5 min) is `FUTURE_TIMESTAMP`; the replay window is unchanged and both bounds are inclusive.
  Webhooks and async responses must carry a send time (`MISSING_TIMESTAMP`, enforced in the domain
  as well as the adapter, H7); callbacks and status replies have none and are not judged on it.
- **Refunds are facts, not a state.** A payment's status is its lifecycle (PENDING, SUCCESS, FAILED,
  REVERSED). Each identified refund is a fact on the captured payment; their sum ≤ paid; full means
  equal. A status reply is an observation: "captured" confirms, "refunded" states the cumulative
  amount and adds no refund.
- **Guarantees per failure** (RUNBOOK §2): the single-broker profile survives restarts and power loss
  (each message flushed before the ack) but not the loss of the broker's disk; the production profile
  is three brokers, three replicas, two in sync (`aegis.kafka.*`). Deliveries retry 72 h, then are kept
  for an operator.
- **Retention by last activity**, never while a delivery is due; keys retire only after every retained
  record and kept backup no longer needs them.
- **Rejected:** allowing SUCCESS after REFUNDED (would accept a stale capture after a full refund as
  news); making every amount mandatory in the domain (some protocols legitimately carry none; the
  adapter knows which).

### D30 — Stripe: the PaymentIntent is the payment; freshness at arrival
- **The PaymentIntent is validation and payment.** Its attempts can fail and a later one succeed,
  so SUCCESS after FAILED is allowed for Stripe; a stale failure after success is still a
  `DOWNGRADE`. Charges are not payment references: a failed attempt can have none.
- **What was captured must match**: SUCCESS carries `amount_received`, so a partial capture is an
  `AMOUNT_MISMATCH`, never a success at the order's price.
- **`t` is the send time.** Stripe signs a new timestamp for every delivery attempt, so freshness is
  its 5-minute tolerance; the event's `created` (kept for 3 days of retries) is not used.
- **Freshness is judged at arrival.** The Checker used its own clock when it got to a message, so a
  backlog after an outage could turn genuine messages into replays; it now uses Aegis's arrival time
  (written by Ingress), which a sender cannot influence.
- **No browser channel** where the provider's redirect is unsigned (Stripe): results arrive by
  webhook or status check through Aegis.
- **IDs in provider URLs** are restricted to ID characters (Stripe and Razorpay status checks).
- **Rejected:** using `charge.refunded` as the refund fact (cumulative, no refund identity, it
  would double-count with `refund.*`); counting `pending` refunds (not yet money returned).

### D31 — Cashfree: exact decimal amounts; the environment is the host
- **Decimal major units are converted exactly.** JSON numbers are read as decimals, never doubles;
  `amount × 10^digits(currency)` must be a non-negative integer that fits a `long`. `1.005` INR is
  refused, not rounded: rounding either way would accept an amount nobody sent.
- **The type and the status must agree** (`PAYMENT_SUCCESS_WEBHOOK` with `payment_status: SUCCESS`);
  a contradiction is unparseable, never resolved in favour of either.
- **Late success allowed**: dropped or failed UPI and net-banking payments can be confirmed late by
  the bank; FAILED after SUCCESS is still a `DOWNGRADE`.
- **Production is a host**: Cashfree's test and live credentials look alike, so `base-url` is
  required and `api.cashfree.com` is allowed only in production.
- **Rejected:** defaulting `order_currency` to INR (Cashfree's own default) and letting Cashfree
  generate the `order_id` (the application's reference must be its own).

### D32 — Public ingress publishes only what providers call
- **Only `/in/` is reachable from the internet.** Providers send webhooks and the customer's browser
  posts callbacks there; both are verified before anything is stored as genuine. `/gw` (applications),
  `/admin` (operators) and the management port stay on the host or private network.
- **A tunnel, not an open port.** Staging is published by a Cloudflare tunnel (outbound connection,
  no inbound port on the host) whose hostname rule is anchored (`^/in/`): an unanchored `in/.*` also
  matched `/admin/`. Production uses the same rule at its reverse proxy, which also terminates TLS
  and, where required, mTLS (HARDENING §12).
- **Rejected:** publishing the whole application port and relying on `/admin` and `/gw` keys alone;
  keys stay required, but what is not published cannot be attacked.
- **M4:** where partners are served, `/partner/` is published as well (anchored, `^/partner/`); every
  request there is authenticated before anything is stored (D35).

### D33 — One XML signature profile, verified against configured keys only
- **Profile:** an enveloped `ds:Signature`, the last child of the root, with exactly one
  `Reference URI=""` (the whole document), transforms exactly enveloped-signature then exclusive
  C14N, digest SHA-256, signature RSA-SHA256 with a key of at least 2048 bits. Anything else, a
  second `Signature` anywhere included, is refused before any cryptography runs.
- **Why whole-document:** signature-wrapping attacks need a reference to *part* of a document, so
  that the verified element and the element read differ. With `URI=""` there is nothing else to read.
- **Keys from configuration only.** `KeyInfo` in a message is ignored: a message cannot choose the
  key that verifies it. Switch certificates (or public keys) are a list, current and previous, for
  rotation; there is no PKI path validation (NPCI.md §5).
- **Parsing:** DOCTYPE refused, no external entities or XInclude, JDK secure processing and
  `org.jcp.xml.dsig.secureValidation` on. Bodies are already capped at 64 KB.
- **Requests are signed by Aegis**, with the connection's `signing-key` (PKCS#8). The application
  holds no NPCI key and sends unsigned XML; a request that already carries a signature is refused.
- **A provider reply that fails verification is `BAD_SIGNATURE`**, like an inbound message, not
  `UNPARSEABLE`: SPI `UntrustedReplyException`. A refused validation reply is stored as FAILED.
- **Rejected:** XML-DSig with ID references (wrapping risk), trusting `KeyInfo` certificates
  (anyone can embed one), and a third-party XML security library (the JDK's is the reference
  implementation; one fewer dependency).

### D34 — NPCI-style contract, a simulator, and the UPI amount authority
- **Contract:** NPCI's BBPS and UPI specifications are for members only, so Aegis implements an
  NPCI-*style* contract (NPCI.md) that keeps what matters for security: XML, signatures on every
  message, `Ack`, asynchronous results, deemed transactions, reversals and status-check limits. The
  lab's switch simulator implements the same contract independently (its own signing code), so the
  two check each other.
- **UPI amount:** a payer PSP has no authority that fixes the amount; the payer's instruction does.
  The validation (`ReqValAdd`) records the amount the application states, and every later message
  (payment request, result, status) must match it. This still stops a result for another amount, a
  second payment while one is in flight and a downgrade; it cannot stop an application that states
  the wrong amount itself, which no gateway could.
- **Synchronous validation and status answers** in the contract (NPCI answers them
  asynchronously). Recorded as a limit, not hidden (HARDENING §12).
- **No late success after failure** for either: a FAILED result is final; money comes back by
  reversal (UPI) or the deemed transaction resolving (BBPS).
- **Rejected:** a UPI validation without an amount (the amount check would have nothing to compare
  with), and making Aegis call the switch to resolve deemed transactions (D2: only the application
  asks).

### D35 — Partner API: Aegis authenticates, limits and executes each request ID once
- **Who:** a partner is an external system that calls an application's own payment API (a
  reseller, agent network or aggregator submitting recharges, bill payments or payouts). The
  application's partner API sits behind Aegis at `/partner/**` (role `PARTNER`); Aegis forwards to
  the application's `partner-url`. Same zero-trust stance as for providers: the partner's network,
  host and headers prove nothing, only its signature does.
- **Signature:** `X-Signature: v1=hex(HMAC-SHA256(secret, "v1\n" METHOD "\n" path?query "\n"
  timestamp "\n" request-id "\n" hex(SHA-256(body))))`, with `X-Partner-Id`, `X-Request-Id`,
  `X-Timestamp`. Everything that decides what the request does is signed, so nothing can be moved
  to another operation, time or ID. Secrets are lists (rotation), 32+ characters, per partner.
  Optional client-certificate subject, like applications.
- **Freshness:** ±5 minutes (`aegis.partner.freshness`) around Aegis's clock, both ways; the window
  bounds how long a request ID must be remembered (retention 30 days, far above it).
- **Scopes:** each partner is granted operations (the first path segment) and methods; anything
  else, and any path that is not plain segments (dot segments, percent-encoding), is 403 before the
  application sees it. The path forwarded is the path signed.
- **One execution per request ID (idempotency key).** The ID is claimed by `INSERT … ON CONFLICT DO
  NOTHING` on `(partner, request_id)` before the application is called, so two instances cannot both
  run it. A repeat gets the stored answer (`X-Aegis-Replayed: true`), whatever its status: an error
  answer is an answer. A different request under a used ID is 422. While the first runs, 409. If the
  application was never reached, the ID is released (503, retry is safe); if it was reached and did
  not answer, the outcome is unknown for good (504, the partner asks the status with a new ID).
  Aegis never guesses that a request did not run.
- **Rates** per partner and per operation (`RateLimiterPort`), checked after authentication,
  so a forger cannot spend a partner's allowance; unauthenticated floods are bounded per claimed
  partner by the H3 limits before the body is read (unknown names share one budget).
- **Evidence:** request and answer bodies sealed like every other body; operators see state and
  status only (`GET /admin/partners/{partner}/requests/{id}`).
- **Not in M4:** amount or value quotas per partner (they need the application's request format,
  i.e. a partner adapter); signing Aegis's answers to the partner (TLS carries them, the answer is
  the application's own).
- **Rejected:** API keys alone (a stolen key replays and alters requests; a signature binds the
  request), nonces in memory (lost on restart, not shared across instances), and letting requests
  through when rates cannot be checked.

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
