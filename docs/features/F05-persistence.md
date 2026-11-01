# F05 — Persistence: schema, repositories, evidence encryption

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F04, F06 |
| Design references | LLD §7 · DECISIONS D3, D5, D15, D22 |

## 1. Goal
Store validations and payments exactly as designed (one row each, the whole cycle in JSON), with
the locks that make every check race-free and every body encrypted at rest.

## 2. Scope
- In: Flyway `V1` (`validations`, `payments`, `config`), JPA entities, Spring Data repositories,
  `ValidationRepository` / `PaymentRepository` ports and adapters, `EvidenceCipherPort` with
  AES-256-GCM, evidence-key configuration and generation on the host.
- Out: the forwarding queue queries (F13), runtime settings (F14), retention (F14).

## 3. Design
- **Ports** (`application.port.out`): `ValidationRepository` (insert with the whole call, `lock` by
  provider reference = `SELECT … FOR UPDATE`, update), `PaymentRepository` (`lockOrCreate` =
  `INSERT … ON CONFLICT DO NOTHING` + `SELECT … FOR UPDATE`, `save`, `insertUntrusted`,
  `ofValidation`), `EvidenceCipherPort`. Records `Evidence` (headers, sealed body/reply, HTTP status).
- **Adapters** (`adapter.out.persistence`): `*Entity` classes, Spring Data `*JpaRepository`
  (`@Lock(PESSIMISTIC_WRITE)` and one native `ON CONFLICT` insert), `*PersistenceAdapter` classes and
  `PaymentRows` (Data Mapper between rows and domain records).
- **JSON fields** are mapped as text (`@JdbcTypeCode(SqlTypes.JSON) String`) and (de)serialised by
  the adapter with Jackson: the stored shape (`StoredEntry`) is explicit and versionable, and the
  domain never sees evidence or delivery history.
- **Merging:** saving a payment keeps the evidence and delivery history of entries already stored and
  takes only what the domain changes (repeat counters, delivery state); new entries take their
  evidence. `forward_due_at` is set while any entry is `PENDING` and cleared otherwise.
- **Evidence:** `AesGcmEvidenceCipher` — AES-256-GCM, fresh 96-bit IV, `enc:v1:<key id>:<base64>`;
  keys in `aegis.evidence.keys`, `current` seals, all open (rotation). Servers: `AEGIS_EVIDENCE_KEY`
  generated once by `deploy.sh` into `.env`; `local` has a fixed local-only key.
- **Schema change from the LLD:** `payments` has no amount columns; the amount is the validation's.
- **Patterns:** Repository (ports), Data Mapper (`PaymentRows`), Pessimistic locking, Idempotent
  insert.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `PersistenceIT` | integration (PostgreSQL) | validation stored with its call and locked; one row per payment; evidence kept and encrypted; repeat counters; forwarding flag; untrusted rows; a locked payment makes the next transaction wait |
| `AesGcmEvidenceCipherTest` | unit | round trip, fresh IV, tampering, rotation, bad keys |
| `EvidencePropertiesTest`, `ProfilesTest` | unit | fail-closed keys; servers never default the key |

`IntegrationTest` is the shared base: containers start once per run and the context is cached.

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] LLD §7, DECISIONS D22, CICD updated
