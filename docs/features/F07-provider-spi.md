# F07 — Provider SPI, registry, contract test suite

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F04, F06 |
| Design references | LLD §6 · ARCHITECTURE §6 · DECISIONS D14, D17 |

## 1. Goal
One small interface that captures everything provider-specific, so Razorpay (F08) and every later
provider plug in without touching the engine; and one test suite every adapter must pass.

## 2. Scope
- In: `ProviderAdapter` and its value types (`Channel`, `RawMessage`, `Ack`, `CallKind`, `CallFacts`,
  `PreparedCall`, `Reply`, `UnparseableMessageException`), `ProviderRegistry` with startup validation,
  `ProviderAdapterContract`, a test-only `ExampleAdapter`.
- Out: real providers (F08 onwards).

## 3. Design
- **Anti-corruption layer:** adapters turn provider formats into the domain's `PaymentMessage`;
  nothing outside `provider.<x>` knows a provider's field names or statuses.
- **Interface segregation:** optional capabilities (decryption, outbound calls, enquiry limits,
  provider-specific validation) have defaults; an adapter implements only what its provider has.
- **Plugin + Registry:** `ServiceLoader` finds adapters; `ProviderRegistry.validate` stops startup
  when a connection names an unknown adapter or lacks a required secret or setting.
  `config.ProviderConfiguration` runs it before any traffic.
- **Exact bytes:** `RawMessage` keeps the body as received; signatures are always computed over
  it, before any parsing.
- **Liskov substitution:** `ProviderAdapterContract` holds the behaviour every adapter must have
  (LLD T2, T3, T17 included).
- `ExampleAdapter` (test sources): JSON + HMAC header, supports every call kind and NPCI-like
  enquiry limits, so the Gateway API (F12) can be tested on the paths Razorpay does not use.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `ExampleAdapterContractTest` | contract | 22 contract cases on both channels |
| `ProviderRegistryTest` | unit | ServiceLoader discovery, unknown adapter, `RawMessage` and `Secret` behaviour |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] LLD §6, ARCHITECTURE §6, DECISIONS D14, add-provider skill updated
