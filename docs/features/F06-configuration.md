# F06 — Configuration: connections, applications, secrets

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F00 |
| Design references | LLD §11 · DECISIONS D9, D17 |

## 1. Goal
Typed, validated configuration of who Aegis talks to: provider **connections** (adapter, owning
application, API base URL, settings, rotatable secrets) and protected **applications** (API key,
forward URL, return URL, signing secret, optional client certificate and JWE key). A bad secret
stops startup instead of silently weakening a check.

## 2. Scope
- In: `AegisProperties` (connections, applications), fail-closed validation, `DirectoryPort` and its
  implementation, the per-environment `aegis.yaml`, masking of secrets everywhere.
- Out: which secret names each adapter needs (checked by the provider registry, F07); runtime
  settings (F14).

## 3. Design
- **Where configuration lives:** each environment has `aegis.yaml` next to its `.env` on the host
  (template `deploy/aegis.example.yaml`, created by `deploy.sh` on first deploy, mounted read-only
  at `/config/aegis.yaml`, imported with `spring.config.import: optional:file:`). Secret values in it
  are `${ENV}` placeholders, so secrets stay in `.env` only.
- **Validation** (`AegisProperties` compact constructors): every secret name must keep at least one
  non-empty value after empty "previous" slots are dropped; provider secrets ≥ 16 characters
  (Razorpay key secrets are 24), application secrets ≥ 32; a connection must name a configured
  application; an application needs a forward URL. Errors name the setting, never the value.
- **Secrets as a type:** `provider.spi.Secret` prints as `****` and compares in constant time;
  `SecretSet` holds lists newest-first (sign with the first, verify with any) for rotation.
- **Ports:** `application.port.out.DirectoryPort` (connection by name, application by name or API
  key); `config.ConfiguredDirectory` implements it, so the core never depends on Spring
  configuration (dependency inversion). API-key lookup compares against every key.
- **Patterns:** Value Object (`Secret`), Dependency Inversion (`DirectoryPort`), Fail Fast.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `AegisPropertiesTest` | unit + context runner | roles/environment; empty-slot drop; empty secret (T18, CVE-2026-41432); short secret not printed; unknown application; short application secret; masking; binding from properties |
| `ConfiguredDirectoryTest` | unit | connection/secret exposure, masking, API-key lookup |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] LLD §11, DECISIONS D17, CICD host layout updated
