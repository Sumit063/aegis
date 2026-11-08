# H8 — Backups and a rehearsed restore

| | |
|---|---|
| Status | done |
| Depends on | H5, H6 |
| Design references | RUNBOOK §5, §6 · HARDENING §12 · LLD §2 (Admin API) |

## Problem (verified on the staging host)
RUNBOOK §5 described a nightly `pg_dump`, but none had ever been installed: the host had no
`~/backups` and no scheduled job. A host loss would have lost every record. The restore had never
been rehearsed, and there was no way to prove that restored evidence still opens with the keys
short of reading SQL by hand (the key-retirement rule relied on a `LIKE` query).

## Change
- **Backups are part of the stack.** A `backup` container (the PostgreSQL image, no new
  dependency) writes one `pg_dump -Fc` a day into `~/aegis/<env>/backups/` (mode 600) and keeps the
  newest 14. `deploy/check.sh` fails when none is under 26 h old.
- **`GET /admin/evidence?sample=N`**: sealed values per key ID across payments and validations, and
  how many of the N most recent open with the configured keys. Contents are never returned. Used
  after a restore and before retiring a key (RUNBOOK §6).
- **Restore rehearsal.** `deploy/rehearse-restore.sh <env>` restores the newest dump into a
  throwaway copy (admin role only: it receives nothing, calls no provider, delivers nothing), starts
  Aegis on it and requires every sampled value to open. The *Restore rehearsal* workflow runs it on
  the host weekly and on demand.

## Tests
| Test | Covers |
|---|---|
| `AdminIT.provesStoredEvidenceStillOpens` | counts per key; a value sealed with a retired key is reported unreadable; no sealed text in the response |
| `deploy/rehearse-restore.sh` against a local copy of the stack | dump taken by the container, restored, Aegis ready on it (schema 3), evidence report |
| `deploy/check.sh` | the backup container runs and a dump is under 26 h old |

## Remaining
- Dumps stay on the host's disk; copying them elsewhere is an operator task (RUNBOOK §5).
- Staging has no provider connection yet, so its rehearsal restores an empty database until real
  traffic is configured.
