# Code Architecture

How the Aegis code is organised, the rules that keep it that way, and the design patterns used.
The system design (what Aegis does) lives in [`docs/design`](../design); this document is about
**how the code is built**.

## 1. Style: hexagonal (ports and adapters)

```
                 ┌──────────────────────── adapter.in ────────────────────────┐
                 │  web (Ingress, Callback, Gateway API, Admin) ·              │
                 │  kafka (Checker consumer) · scheduler (Forwarder)           │
                 └──────────────────────────────┬──────────────────────────────┘
                                                │ calls use cases (ports in)
                 ┌──────────────────────────────▼──────────────────────────────┐
                 │  application  — use cases, transaction boundaries            │
                 │     port.in  (what the outside may ask)                      │
                 │     port.out (what the core needs from the outside)          │
                 └──────────────────────────────┬──────────────────────────────┘
                                                │ uses
                 ┌──────────────────────────────▼──────────────────────────────┐
                 │  domain  — pure Java: messages, validations, checks,         │
                 │  verdicts, value objects                                     │
                 └──────────────────────────────────────────────────────────────┘
                 ┌──────────────────────── adapter.out ───────────────────────┐
                 │  persistence (JPA) · kafka (ingress log) · http (provider,  │
                 │  forwarding) · crypto (at-rest encryption, JWE)             │
                 └──────────────────────────────────────────────────────────────┘
                 ┌──────────────────────── provider ──────────────────────────┐
                 │  spi (ProviderAdapter, registry, contract) ·                │
                 │  razorpay · stripe · paytm · bbps · upi · …                 │
                 └──────────────────────────────────────────────────────────────┘
```

**Why:** the checks (the valuable, risky part) must not depend on Kafka, PostgreSQL, Spring MVC or
any provider. Each of those can change without touching the core.

## 2. Packages

Familiar layers (controller, DTO, service, repository, entity) placed inside the hexagon, so each
one has exactly one reason to change.

```
aegis-service/src/main/java/dev/aegis/
  domain/                        pure Java, no framework imports
    shared/                      value objects: Money, Ref
    message/                     PaymentMessage, MessageType, Outcome, Verdict, BlockReason
    payment/                     Validation, Payment (one row each, with its message entries)
    check/                       Check rules, Checks, Gate (the single decision point)
  application/
    port/in/                     *UseCase interfaces + their command/result records
    port/out/                    *Repository (storage) and *Port (Kafka, HTTP, crypto, clock)
    service/                     *Service: use-case implementations, transaction boundaries
  provider/
    spi/                         ProviderAdapter, RawMessage, ParsedMessage, SecretSet, registry
    razorpay/                    M1; later stripe/, bbps/, upi/, one package each
  adapter/
    in/web/controller/           *Controller: ingress /in, Gateway API /gw, Admin /admin
    in/web/dto/                  request/response records (the HTTP contract)
    in/web/error/                exception → RFC 9457 problem response, in one place
    in/web/security/             application key and client-certificate authentication
    in/kafka/                    Checker consumer
    in/scheduler/                Forwarder, retention
    out/persistence/             *PersistenceAdapter: implements repository ports, maps rows ↔ domain
    out/persistence/entity/      *Entity: JPA table mappings
    out/persistence/repository/  Spring Data repositories (locking and ON CONFLICT queries)
    out/kafka/                   ingress log publisher
    out/http/                    provider client, forward client
    out/crypto/                  AES-GCM evidence cipher, Aegis signer, JWE
  config/                        typed properties, connections, applications, fail-closed validation

lab/                             Exploit Lab (M0), separate Maven modules
  mock-gateway/                  Razorpay-compatible provider
  vulnshop/                      reference merchant with flaw switches F1–F10
  attacks/                       one script per exploit; lab/run.sh writes lab/RESULTS.md
```

A request through the layers:

```
 HTTP ─► *Controller ─(dto → command)─► *UseCase ◄── *Service ─► Gate (domain)
                                                        │
                                                        ├─► *Repository ◄── *PersistenceAdapter ─► *Entity
                                                        └─► *Port       ◄── Kafka / HTTP / crypto adapter
```

## 3. Dependency rules (enforced by ArchUnit on every build)

| Rule | Why |
|---|---|
| `domain` depends only on `java.*` | Checks stay framework-free and trivially unit-testable |
| `application` depends on `domain` and `provider.spi` only (plus `spring-tx` annotations) | Use cases never know about HTTP, Kafka or JPA |
| `adapter.*` may depend on `application` and `domain`, never on each other | Swappable adapters |
| `provider.<x>` depends on `provider.spi` and `domain` only; never on another provider | Providers are plugins |
| JPA (`jakarta.persistence`) only in `adapter.out.persistence` | Persistence model stays separate |
| No package cycles | Keeps the graph understandable |
| Names and places: `*Controller` in `web.controller`, `*Service` in `application.service`, `*UseCase` in `port.in`, `*Port`/`*Repository` in `port.out`, `*Entity` in `persistence.entity`, Spring Data in `persistence.repository` | Predictable navigation; one place per kind of class |

## 4. SOLID, applied

| Principle | How it shows up |
|---|---|
| **S**ingle responsibility | Ingress verifies and buffers; the Checker decides verdicts; the Forwarder delivers; the Gateway API proxies. One check per class. |
| **O**pen/closed | New provider = new `provider.<x>` package; new check = new `Check` in the chain. Core code is not edited. |
| **L**iskov substitution | Every `ProviderAdapter` passes the same shared contract test suite. |
| **I**nterface segregation | Adapters for providers without outbound requests or encryption use the SPI's no-op defaults instead of fake implementations. |
| **D**ependency inversion | The core defines ports; adapters implement them. |

## 5. Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Hexagonal / Ports & Adapters** | Whole service | Isolate checks from infrastructure |
| **Adapter + Anti-corruption layer** | `provider.<x>` | Each provider's formats, signatures and statuses become provider-neutral `ParsedMessage`/`Outcome` |
| **Plugin + Registry** | `ProviderRegistry` | Add providers without touching the core |
| **Strategy** | Signature schemes (HMAC, checksum, XML-DSig), ack formats, backoff | Swap per provider by configuration |
| **Chain of Responsibility** | `Checks.run` over named `Check` rules (fresh → validated → amount → no downgrade → new fact) | Each rule isolated and tested alone; first failure gives the block reason |
| **Value Object** | `Money`, `ProviderId`, references as records | Validation at construction |
| **Idempotent Consumer** | Message ID and same-fact checks on the locked payment row | At-least-once input, once-only forwarding |
| **Competing Consumers** | Kafka partitions; Forwarder claims rows with `FOR UPDATE SKIP LOCKED` | Scale by adding instances |
| **Retry with backoff + jitter, Circuit Breaker** | Forwarding to the application | Survive a slow or failing application |
| **Decorator** | Metrics/tracing around ports | Cross-cutting concerns outside business code |

Deliberately **not** used: business state machines, outbox/event sourcing, reactive stacks,
Lombok, deep inheritance.

## 6. Provider adapters

The `ProviderAdapter` interface is in LLD §6. Adding a provider:

1. Create `provider/<name>/` and implement `ProviderAdapter` (unused parts keep the SPI defaults).
2. Map statuses strictly: only the provider's final-success status becomes `SUCCESS`.
3. Register the bean; add a connection in configuration.
4. Pass the shared contract test suite (signature accept/reject, empty-secret rejection, parsing,
   acknowledgement format, freshness, enquiry limits).

See the `add-provider` skill.

## 7. Persistence (JPA)

- Spring Data JPA repositories implement the persistence ports; entities live only in
  `adapter.out.persistence`.
- Flyway owns the schema; Hibernate runs with `ddl-auto=validate`.
- Native SQL for the two places where JPA would hide essential behaviour: `INSERT … ON CONFLICT`
  (one row per payment) and `SELECT … FOR UPDATE` / `SKIP LOCKED` (validation lock, forwarding queue).
- The `data` and `messages` JSON fields map to Java records with `@JdbcTypeCode(SqlTypes.JSON)`.
- Evidence columns are encrypted/decrypted through `EvidenceCipherPort`, never stored in clear.
- `open-in-view=false`. **Never call the network inside a database transaction.**

## 8. Testing pyramid

| Level | Scope | Tools | Naming |
|---|---|---|---|
| Unit | domain checks, application services (ports faked) | JUnit 5, AssertJ | `*Test` |
| Architecture | dependency rules above | ArchUnit | `ArchitectureTest` |
| Contract | every provider against the SPI contract | JUnit 5 abstract suite | `*ContractTest` |
| Integration | adapters with real infrastructure | Testcontainers (PostgreSQL, Kafka), WireMock | `*IT` |
| Scenario | T1–T19 from the LLD test catalogue | Testcontainers + WireMock | `*ScenarioIT` |

Unit tests run in `test`; integration and scenario tests in `verify`. Coverage gate: JaCoCo, 80 %
lines for `domain` and `application`.
