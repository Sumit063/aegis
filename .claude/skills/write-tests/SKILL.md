---
name: write-tests
description: Write or improve Aegis tests at the right level (unit, architecture, contract, integration, scenario). Use when adding tests, raising coverage, or mapping LLD test catalogue scenarios T1–T23 to code.
---

# Write tests

## Choose the level

| What you are testing | Level | Tools | Class name |
|---|---|---|---|
| Value objects, state machines, rules, policies | Unit | JUnit 5, AssertJ — no mocks | `*Test` |
| Use-case services | Unit | In-memory fakes of ports (hand-written, in `src/test`) | `*Test` |
| A provider module against the SPI | Contract | Extend the shared abstract contract suite | `*ContractTest` |
| JPA repositories, Kafka, HTTP clients, controllers | Integration | Testcontainers (PostgreSQL, Kafka), WireMock, MockMvc | `*IT` |
| End-to-end behaviour from the LLD catalogue | Scenario | Full app + Testcontainers + WireMock | `*ScenarioIT` |

`*Test` runs in `./mvnw test`; `*IT` runs in `./mvnw verify`.

## Rules

- One behaviour per test; name it as a sentence: `duplicateWebhookIsRecordedOnce()`.
- Arrange / act / assert blocks; builders for fixtures (`aCapturedPayment().withAmount(…)`).
- State machines: test **every** allowed transition and a representative set of forbidden ones
  (parameterised tests over the transition table).
- Concurrency: run competing calls with an executor and assert database-level outcomes
  (one notification, one transition).
- Time: inject `ClockPort` / `java.time.Clock`; never sleep. Use Awaitility for async assertions.
- Data: obviously fake values (`pay_TEST0001`, `order_TEST0001`, `test-secret-…`); never real keys
  or personal data.
- Scenario tests: put the LLD id in `@DisplayName("T7 two captured payments on one target")`.
- Don't test framework behaviour or getters; test decisions.

## Done when
`./mvnw verify` is green and coverage for `domain`/`application` stays ≥ 80 %.
