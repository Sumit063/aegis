---
name: add-provider
description: Integrate a new provider into Aegis (payment gateway, dynamic QR, BBPS, recharge aggregator, card network such as Mastercard) as a plugin module. Use when asked to add, support or integrate a provider.
---

# Add a provider module

Providers are plugins (ARCHITECTURE §6). Adding one must not change `domain`, `application` or any
other provider.

## Steps

1. **Design note first** (`solution-design`): list the provider's
   - validation call (create order, bill fetch, …) and which reply fields fix the amount,
   - inbound message formats and authentication (HMAC, checksum, XML-DSig, mTLS, encryption),
   - the synchronous acknowledgement it expects (2xx, `<Ack/>`),
   - message ID, references and timestamp fields (for duplicates, matching and freshness),
   - status vocabulary → `Outcome` mapping (every status mapped or explicitly `OTHER`; only the
     final-success status maps to `SUCCESS`),
   - outbound calls Aegis proxies, their authentication, and status-check limits.
2. **Create** `provider/<name>/` with a `<Name>Adapter` implementing `ProviderAdapter`; unused parts
   keep the SPI defaults. Provider DTOs never leave the package.
3. **Register** the bean; add a connection to the configuration with fail-closed secret validation.
4. **Tests:**
   - `<Name>ContractTest` extending the shared SPI contract suite,
   - signature vectors (valid, tampered body, wrong secret, empty secret, rotated secret),
   - status mapping table test (every row),
   - WireMock test for proxied outbound calls, including timeouts and 5xx.
5. **Docs:** add the provider to the table in `LLD.md` §6.
6. `./mvnw verify` green; review with `code-review`.

## Rules
- Never add `if (provider == X)` to core code. If the core seems to need it, the SPI is missing a
  method — extend the SPI in a separate, reviewed change.
- Provider modules never import other provider modules.
- Credentials only via configuration; never hardcoded, never logged.
