---
name: add-provider
description: Integrate a new provider into Aegis (payment gateway, dynamic QR, BBPS, recharge aggregator, card network such as Mastercard) as a plugin module. Use when asked to add, support or integrate a provider.
---

# Add a provider module

Providers are plugins (ARCHITECTURE §6). Adding one must not change `domain`, `application` or any
other provider.

## Steps

1. **Design note first** (`solution-design`): list the provider's
   - webhook/callback format and authentication (→ `SignatureScheme` strategy),
   - checkout proof, if any,
   - truth source: fetch APIs or status-enquiry API,
   - status vocabulary → domain state mapping table (every provider status mapped or explicitly
     ignored),
   - target model (order, payment intent, QR, bill, recharge request),
   - rate limits, timeouts, retry semantics, idempotency keys.
2. **Create** `provider/<name>/` with:
   - `<Name>ProviderModule` implementing `ProviderModule`,
   - only the capability interfaces the provider actually supports
     (`WebhookAuthenticator`, `WebhookParser`, `CheckoutProofVerifier`, `TruthSource`,
     `StatusEnquiry`),
   - a mapper (anti-corruption layer): provider DTOs → domain types; no provider types leak out,
   - a `SignatureScheme` implementation if no existing one fits.
3. **Register** the module as a Spring bean; the `ProviderRegistry` picks it up. Add config
   validation for its credentials (fail closed).
4. **Tests:**
   - `<Name>ContractTest` extending the shared SPI contract suite,
   - signature vectors (valid, tampered body, wrong secret, empty secret, rotated secret),
   - status mapping table test (every row),
   - WireMock integration test for the truth source, including timeouts and 5xx.
5. **Docs:** add the provider to the capability matrix in ARCHITECTURE §6 and to `LLD.md` §10.
6. `./mvnw verify` green; review with `code-review`.

## Rules
- Never add `if (provider == X)` to core code. If the core seems to need it, the SPI is missing a
  capability — extend the SPI in a separate, reviewed change.
- Provider modules never import other provider modules.
- Credentials only via configuration; never hardcoded, never logged.
