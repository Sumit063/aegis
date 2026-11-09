# F20 — XML signatures and secure parsing

| | |
|---|---|
| Status | done |
| Milestone | M3 |
| Depends on | F07 (SPI) |
| Design references | NPCI.md §1 · LLD §6 · DECISIONS D33 · W3C XML Signature 1.1, Exclusive C14N |

## 1. Goal
One audited way, shared by every XML provider, to parse provider XML safely, sign Aegis's requests
and verify the switch's messages and replies, so that no adapter writes its own cryptography.

## 2. Scope
- In: `provider.spi.Xml` (secure parse, attribute and element lookup, serialisation),
  `provider.spi.XmlSignature` (sign, verify to the D33 profile), key and certificate loading from
  configuration (PEM or single-line base64), SPI `UntrustedReplyException` and its use in
  `GatewayService` (reply verification fails → `BAD_SIGNATURE`; a validation is stored FAILED).
- Out: XML encryption, ID-referenced signatures, PKI path validation, mTLS (the TLS proxy's).

## 3. Design
- **Parse:** `DocumentBuilderFactory` namespace-aware, `disallow-doctype-decl`, no external
  general/parameter entities, no XInclude, no entity expansion, `FEATURE_SECURE_PROCESSING`. A
  DOCTYPE, a malformed document or more than one root → `UnparseableMessageException`.
- **Verify** (`XmlSignature.verify(Document, List<PublicKey>)` → boolean): exactly one
  `ds:Signature` in the whole document, and it is the root's last child; `SignedInfo` has exclusive
  C14N, RSA-SHA256, exactly one `Reference` with `URI=""`, transforms exactly
  [enveloped-signature, exclusive C14N], digest SHA-256. Then `DOMValidateContext` with a fixed key
  (each configured key in turn; `KeyInfo` ignored) and `secureValidation`. Any exception → false.
- **Sign** (`XmlSignature.sign(Document, PrivateKey)`): adds the signature as the root's last
  child, same profile, no `KeyInfo`. A document that already has a `Signature` is refused.
- **Keys:** `PrivateKey` from PKCS#8, `PublicKey` from an X.509 certificate or a
  SubjectPublicKeyInfo; RSA ≥ 2048 bits or startup fails (adapters call these in `validate`).
  Parsed keys are cached by value.
- **Patterns:** shared crypto in the SPI, like `Secret.hmacSha256` (F17).

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `XmlTest` | unit | DOCTYPE/XXE/billion laughs refused before expansion; malformed; namespaces |
| `XmlSignatureTest` | unit | round trip; rotated key; wrong key; tampered text and attribute; signature removed, duplicated, moved inside an element, wrapped (signed element moved, forged one in its place); `URI="#id"`; extra transform (XPath, XSLT); RSA-SHA1 / HMAC-SHA256 (key confusion); `KeyInfo` with an attacker key ignored; 1024-bit key refused |
| `BbpsIT` (F21) | IT | a reply failing verification → `BAD_SIGNATURE`, validation FAILED |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green (a mutation run without the profile check fails the ID-reference and extra-transform tests)
- [x] LLD, DECISIONS D33 updated
