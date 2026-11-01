# F14 — Admin API, runtime settings, retention

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F05, F13 |
| Design references | LLD §2, §7, §11 · DECISIONS D10 |

## 1. Goal
Operators can find anything by any reference, see what was blocked, re-send a message, and hold
deliveries to an application, all without a deployment; old records age out on their own.

## 2. Scope
- In: `/admin/**` (operator key), lookups, blocked list, re-forward, runtime settings (pause
  forwarding per application), 5-second settings refresh, daily retention.
- Out: viewing decrypted bodies (evidence stays sealed; decrypt with ops tooling under audit),
  dashboards (F15).

## 3. Design
| Endpoint | Purpose |
|---|---|
| `GET /admin/payments/{id}` | one payment, every message's verdict and delivery state |
| `GET /admin/payments?ref=` | by payment, order/bill or merchant reference |
| `GET /admin/validations?ref=` | by merchant or provider reference |
| `GET /admin/blocked?since=&limit=` | payments with a blocked message (JSON containment `@>`), newest first |
| `POST /admin/payments/{id}/messages/{n}/forward` | re-forward a passed, forwardable message (202) |
| `GET /admin/settings`, `PUT /admin/settings` | `{"pausedApplications": [...]}` |

- **Auth:** `Authorization: Bearer <admin key>`; `aegis.admin.api-key` ≥ 32 characters, generated per
  environment by `deploy.sh` (`AEGIS_ADMIN_API_KEY`), fail-closed at startup.
- **Runtime settings:** stored in `config` (versioned, with who changed it). Each instance keeps an
  immutable snapshot, reloaded at start and every 5 s; a failed reload keeps the last one. A paused
  application's payments are given back to the queue every 5 s, so nothing is lost and delivery
  resumes within seconds of unpausing.
- **Retention:** daily at 03:30 UTC, payments then unreferenced validations older than 180 days, in
  batches of 1000.
- **Layers:** `AdminController` + DTOs → `AdminUseCase`, `SettingsUseCase`, `RetentionUseCase` →
  repository ports; `RetentionJob`, `SettingsRefreshJob` (schedulers).

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `AdminIT` | integration | key required; lookups by every reference; no ciphertext in responses; blocked list; re-forward (and refusal); pause holds deliveries, unpause delivers, setting versioned; retention removes only old rows |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] LLD §11, DECISIONS D10, CICD updated
