# Code Architecture

How the Aegis code is organised, the rules that keep it that way, and the design patterns used.
The system design (what Aegis does) lives in [`docs/design`](../design); this document is about
**how the code is built**.

## 1. Style: hexagonal (ports and adapters)

```
                 ┌──────────────────────── adapter.in ────────────────────────┐
                 │  web (Ingress, Verify, Admin) · kafka (ClaimConsumer) ·     │
                 │  scheduler (Confirm, Recheck, Delivery workers)             │
                 └──────────────────────────────┬──────────────────────────────┘
                                                │ calls use cases (ports in)
                 ┌──────────────────────────────▼──────────────────────────────┐
                 │  application  — use cases, transaction boundaries, policies  │
                 │     port.in  (what the outside may ask)                      │
                 │     port.out (what the core needs from the outside)          │
                 └──────────────────────────────┬──────────────────────────────┘
                                                │ uses
                 ┌──────────────────────────────▼──────────────────────────────┐
                 │  domain  — pure Java: targets, transactions, claims,         │
                 │  state machines, rules, value objects, domain events         │
                 └──────────────────────────────────────────────────────────────┘
                 ┌──────────────────────── adapter.out ───────────────────────┐
                 │  persistence (JPA) · kafka (ClaimLog) · http (Delivery)     │
                 └──────────────────────────────────────────────────────────────┘
                 ┌──────────────────────── provider ──────────────────────────┐
                 │  spi (capability interfaces, registry) ·                    │
                 │  razorpay · stripe · bbps · recharge · mastercard · …       │
                 └──────────────────────────────────────────────────────────────┘
```

**Why:** the payment rules (the valuable, risky part) must not depend on Kafka, PostgreSQL, Spring
MVC or any provider. Each of those can change — or gain a second implementation — without touching
the core.

## 2. Packages

```
aegis-service/src/main/java/dev/aegis/
  domain/                 pure Java, no framework imports
    shared/               value objects: Money, SourceId, ProviderRef, ids
    target/               PaymentTarget aggregate (orders, QR codes)
    transaction/          Transaction aggregate, TxnKind, state machines
    claim/                Claim, ClaimOrigin, ClaimStatus, ClaimRule chain
    notification/         Notification, NotificationPolicy
    event/                domain events
  application/
    port/in/              use-case interfaces (ReceiveWebhookUseCase, VerifyCheckoutUseCase, …)
    port/out/             ClaimLogPort, InboxRepository, OrderRepository, OutboxRepository, EventLogPort, RuntimeConfigPort, ClockPort, …
    service/              use-case implementations (transaction boundaries live here)
  provider/
    spi/                  capability interfaces, ProviderModule, ProviderRegistry, SignatureScheme
    razorpay/             M1
    stripe/ bbps/ recharge/ mastercard/   later, one package each
  adapter/
    in/web/               controllers, request/response DTOs
    in/kafka/             ClaimConsumer
    in/scheduler/         lease-based workers
    out/persistence/      JPA entities, Spring Data repositories, mappers, port implementations
    out/kafka/            ClaimLog publisher
    out/http/             outbound delivery client
  config/                 typed properties, role toggles, fail-closed validation, bean wiring
```

## 3. Dependency rules (enforced by ArchUnit on every build)

| Rule | Why |
|---|---|
| `domain` depends only on `java.*` | Payment rules stay framework-free and trivially unit-testable |
| `application` depends on `domain` and `provider.spi` only (plus `spring-tx` annotations) | Use cases never know about HTTP, Kafka or JPA |
| `adapter.*` may depend on `application` and `domain`, never on each other | Swappable adapters |
| `provider.<x>` depends on `provider.spi` and `domain` only; never on another provider | Providers are plugins |
| JPA (`jakarta.persistence`) only in `adapter.out.persistence` | Persistence model stays separate from the domain model |
| No package cycles | Keeps the graph understandable as it grows |
| Controllers end in `Controller`, ports in `Port`/`UseCase`, adapters in `Adapter` | Predictable navigation |

## 4. SOLID, applied

| Principle | How it shows up |
|---|---|
| **S**ingle responsibility | Ingress only authenticates and logs claims; the Processor only confirms and transitions; the Deliverer only delivers. One use case per class. |
| **O**pen/closed | New provider = new `provider.<x>` package implementing SPI capabilities; new claim check = new `ClaimRule`; new notification = new policy entry. Core code is not edited. |
| **L**iskov substitution | Every `ProviderModule` honours the same contracts, verified by a shared **contract test suite** each provider must pass. |
| **I**nterface segregation | Providers implement only the capabilities they have (`WebhookAuthenticator`, `CheckoutProofVerifier`, `TruthSource`, `StatusEnquiry`, …), not one fat interface. |
| **D**ependency inversion | The core defines ports; adapters implement them. The core never imports an adapter. |

## 5. Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Hexagonal / Ports & Adapters** | Whole service | Isolate payment rules from infrastructure |
| **Adapter + Anti-corruption layer** | `provider.<x>` | Translate each provider's payloads, statuses and errors into domain terms; nothing provider-specific leaks inward |
| **Plugin + Registry** | `ProviderRegistry` (Spring collects all `ProviderModule` beans) | Add providers without touching the core |
| **Capability interfaces (ISP)** | `provider.spi` | A BBPS module has no checkout verifier; a gateway has no status enquiry — each implements what it supports |
| **Strategy** | `SignatureScheme` (HMAC-hex, HMAC-base64+timestamp, RSA/XML-DSig, OAuth 1.0a body hash), `BackoffPolicy`, `ForwardingMode` (envelope/passthrough), `VerificationMode` (strict/verify-only) | Swap algorithms per provider/destination by configuration |
| **Table-driven state machine** | `TransitionTable` per `TxnKind`, `PaymentTarget` rules | Allowed moves are data, reviewable at a glance, exhaustively testable |
| **Chain of Responsibility** | `ClaimRule` chain in confirmation (exists → target match → amount match → not contradicted → not behind) | Each check isolated; new checks slot in |
| **Aggregate + Repository (DDD)** | `PaymentTarget` owns its `Transaction`s and is stored as one row; repositories are ports | One consistency boundary, one lock |
| **Value Object** | `Money`, `SourceId`, `ProviderRef` as Java records | Validation at construction; no primitive obsession |
| **Domain events + Observer** | Transitions raise events; `NotificationPolicy` maps events → notifications | State logic doesn't know who is told |
| **Transactional Outbox** | Outbox rows (and events) written in the same DB transaction as the state change; Deliverer relays | No lost or phantom notifications |
| **Idempotent Consumer** | Inbox unique key; deterministic outbox IDs (`UUIDv5`) | At-least-once input, once-only effects |
| **Competing Consumers + Lease** | Workers claim rows with `SKIP LOCKED` + `locked_until` | Horizontal scaling without coordination; crash recovery |
| **Retry with backoff + jitter, Circuit Breaker, Bulkhead** | Provider calls and deliveries (Resilience4j) | Survive slow or failing dependencies without overload |
| **Decorator** | Metrics/tracing around ports (Micrometer observations) | Cross-cutting concerns outside business code |
| **Builder** | Notification envelopes, test fixtures | Readable construction of large immutable objects |
| **CQRS-lite** | Admin timelines use read-only query projections | Investigation queries never touch the write model |

Deliberately **not** used: deep inheritance / Template Method (composition instead), service
locators, Lombok (records cover it), reactive stacks (virtual threads instead).

## 6. Provider plugin model

```java
// provider.spi — each provider module exposes only the capabilities it has
interface ProviderModule { ProviderId id(); Set<Capability> capabilities(); }

interface WebhookAuthenticator { boolean authenticate(RawRequest request, SecretSet secrets); }
interface WebhookParser       { ParsedClaim parse(RawRequest request); }
interface CheckoutProofVerifier { boolean verify(CheckoutProof proof, SourceCredentials credentials); }
interface TruthSource         { ProviderTransaction fetchTransaction(TxnRef ref); ProviderTarget fetchTarget(TargetRef ref); }
interface StatusEnquiry       { FulfilmentStatus enquire(TxnRef ref); }   // BBPS, recharge aggregators
```

| Provider type | Example | WebhookAuthenticator | WebhookParser | CheckoutProofVerifier | TruthSource | StatusEnquiry | Signature scheme |
|---|---|---|---|---|---|---|---|
| Payment gateway | Razorpay (M1) | ✓ | ✓ | ✓ | ✓ | — | HMAC-SHA256 hex |
| Payment gateway | Stripe (M2) | ✓ | ✓ | — | ✓ | — | HMAC-SHA256 with timestamp |
| Dynamic QR | Razorpay QR (M2) | ✓ | ✓ | — | ✓ | — | HMAC-SHA256 hex |
| Bill payments | BBPS COU integration (M3) | ✓ | ✓ | — | — | ✓ | Digital signature (RSA / XML-DSig) |
| Recharge aggregator | Any aggregator (M3) | ✓ | ✓ | — | — | ✓ | Varies: HMAC, token, mTLS |
| Card network APIs | Mastercard (future) | ✓ | ✓ | — | ✓ | ✓ | OAuth 1.0a signed requests, mTLS |

Adding a provider: create `provider/<name>/`, implement the capabilities it has, register a
`ProviderModule` bean, pass the shared contract test suite. See the `add-provider` skill.

## 7. Persistence (JPA)

- **Spring Data JPA** repositories implement the persistence ports. JPA entities live only in
  `adapter.out.persistence` and are mapped to and from domain objects.
- **Schema is owned by Flyway**; Hibernate runs with `ddl-auto=validate`.
- **Optimistic locking** (`@Version`) on every mutable entity; a **pessimistic lock**
  (`@Lock(PESSIMISTIC_WRITE)`) on the order row for every transition. Transactions are a JSON
  array inside the order entity (`@JdbcTypeCode(SqlTypes.JSON)`), mapped to domain records.
- **Events** are appended through `EventLogPort` in the same transaction as each change.
- **Queue claiming** (`FOR UPDATE SKIP LOCKED`) and **insert-if-absent** (`ON CONFLICT DO NOTHING`)
  use explicit native queries — the two places JPA abstractions would hide essential behaviour.
- `open-in-view=false`; no lazy loading across layers; fetch what a use case needs explicitly.
- **Never call the network inside a database transaction.**

## 8. Testing pyramid

| Level | Scope | Tools | Naming |
|---|---|---|---|
| Unit | domain, application services (ports faked) | JUnit 5, AssertJ | `*Test` |
| Architecture | dependency rules above | ArchUnit | `ArchitectureTest` |
| Contract | every provider against the SPI contract | JUnit 5 abstract suite | `*ContractTest` |
| Integration | adapters with real infrastructure | Testcontainers (PostgreSQL, Kafka), WireMock | `*IT` |
| Scenario | T1–T23 from the LLD test catalogue | Testcontainers + WireMock | `*ScenarioIT` |

Unit tests run in `test`; integration and scenario tests run in `verify` (Failsafe).
Coverage gate: JaCoCo, 80 % lines for `domain` and `application`.
