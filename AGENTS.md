# AGENTS.md

Instructions for AI coding agents (and humans) working in this repository.

## What this is

Aegis is a self-hosted, zero-trust security gateway for payment messages. It sits between an
application and its payment providers, checks every message in both directions (signature,
freshness, duplicates, match with the validation, no downgrade), records it, and forwards the
original with its verdict, or blocks it. It holds the provider secrets and never calls a provider
unless the application asks.
Java 25 · Spring Boot 4 · Kafka · PostgreSQL · Docker Compose.

## Read before changing anything

| Need | Document |
|---|---|
| Where Aegis sits, payment flows, terms | `docs/design/PAYMENT-FLOWS.md` |
| Role, checks, zero trust, guarantees, failures | `docs/design/HLD.md` |
| Endpoints, pipelines, adapter SPI, schema, test catalogue (T1–T19) | `docs/design/LLD.md` |
| Every decision and why | `docs/design/DECISIONS.md` |
| Code layout, dependency rules, design patterns | `docs/engineering/ARCHITECTURE.md` |
| Pipelines and deployment | `docs/engineering/CICD.md` |
| Feature backlog and workflow | `docs/features/README.md` |

## Commands

```bash
scripts/setup-dev.sh            # once per clone: installs git hooks
./mvnw spotless:apply           # format
./mvnw test                     # unit + architecture tests (fast)
./mvnw verify                   # everything: format check, tests, integration tests, coverage
docker compose -f deploy/compose.yaml -f deploy/compose.local.yaml up -d postgres kafka   # local infra
```

Integration tests need a running Docker daemon.

## Non-negotiable invariants

Code that breaks one of these is wrong even if tests pass.

1. **Every message gets every check, on every code path.** No message type, host, IP or header
   skips signature verification.
2. **Nothing is acknowledged before it is durably stored** (provider message → Kafka before the ack).
3. **Never forward a message that failed a check.** Blocked messages are stored and alerted on.
4. **Duplicates and same-fact are enforced by the database** (unique keys), not by application
   checks alone. Checks on one validation run under its row lock.
5. **Aegis never calls a provider on its own** — only for an application request (D2).
6. **No network calls inside a database transaction.**
7. **Fail closed:** missing, empty or weak secrets stop startup; a verification error means block.
8. **Aegis never moves money**, never handles raw card data, and stores message bodies only
   encrypted.
9. **Secrets and personal data never appear in logs, exceptions, tests, commits or profile files.**
   Each environment's keys come only from its own environment variables.
10. **Amounts are `long` minor units with a currency** — never `double`/`float`.

## Architecture rules (enforced by `ArchitectureTest`)

- `domain` is pure Java — no Spring, JPA, Jackson or provider imports.
- `application` depends only on `domain` and `provider.spi`.
- Adapters never depend on each other. Providers never depend on each other.
- JPA annotations only in `adapter.out.persistence`.
- New behaviour goes in by **adding** a class (provider adapter, `Check`), not by
  adding `if (provider == …)` branches to the core.

## Coding conventions

- Java 25: records for value objects and DTOs, sealed interfaces for closed hierarchies,
  `switch` pattern matching, `Optional` only as a return type. No Lombok.
- Constructor injection only; classes `final` unless designed for extension.
- Names: `*UseCase` (port in), `*Port` / `*Repository` (port out), `*Adapter`, `*Controller`,
  `*Check`, `*Policy`.
- Validate at boundaries (DTOs, value-object constructors); trust types inside.
- Exceptions: domain-specific, unchecked, never swallowed; no stack traces with secrets.
- Comments explain *why*, not *what*. Public SPI types get Javadoc.
- Formatting is Spotless (Palantir Java Format) — run `./mvnw spotless:apply`, don't hand-format.

## Testing rules

- Every behaviour change ships with tests. Domain logic: unit tests, no mocks.
- Adapters: integration tests against real PostgreSQL/Kafka (Testcontainers) and WireMock.
- Map scenario tests to the LLD test catalogue IDs (`T7`, …) in the test name or `@DisplayName`.
- Test names describe behaviour: `failedAfterSuccessIsBlockedAsDowngrade()`.
- AssertJ for assertions; no `Thread.sleep` (use Awaitility).

## Workflow for agents

1. Work **one feature at a time** from `docs/features/README.md`. No design note, no code
   (skill: `solution-design`).
2. Implement inside-out: domain → application → adapters (skill: `implement-feature`).
3. **After every change, run `./mvnw verify`** (at minimum `./mvnw test` for small steps, and
   `verify` before declaring done). Never report success on a red build.
4. Self-review with the `code-review` skill before handing over.
5. Work on `feature/<ID>-<slug>` cut from `master`; open the PR into `dev`. Never commit directly
   to `master`, `staging` or `dev` (CICD.md §2). Commit in small logical steps with Conventional
   Commit messages; never bypass hooks (`--no-verify`).
6. Update docs when behaviour or a decision changes (`LLD.md`, `DECISIONS.md`).
7. Never commit secrets, real credentials or customer data. Test data uses obviously fake values.

## Skills

Project skills live in `.claude/skills/`:

| Skill | Use when |
|---|---|
| `solution-design` | Starting a feature: write and check its design note |
| `implement-feature` | Building an approved feature end to end |
| `write-tests` | Adding or reviewing tests |
| `code-review` | Reviewing a change before commit/merge |
| `verify-build` | Running and interpreting the build, fixing failures |
| `add-provider` | Integrating a new provider (gateway, BBPS, aggregator, card network) |
