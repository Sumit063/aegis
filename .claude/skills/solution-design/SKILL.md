---
name: solution-design
description: Design an Aegis feature before coding. Use when starting any backlog feature (F01, F02, …), changing behaviour, or when asked to "design", "plan" or "write the design note" for something in this repo.
---

# Solution design

Produce `docs/features/<ID>-<slug>.md` from `docs/features/TEMPLATE.md`, good enough that
implementation needs no further design decisions.

## Steps

1. **Load context.** Read the feature's row in `docs/features/README.md`, then the relevant parts of
   `docs/design/HLD.md`, `LLD.md`, `DECISIONS.md` and `docs/engineering/ARCHITECTURE.md`.
   Check that dependencies listed for the feature are `done`.
2. **State the goal and scope.** Write what is in and, explicitly, what is out.
3. **Design inside-out.**
   - Domain: types, invariants, state transitions (as tables).
   - Ports: exact `port.in` / `port.out` signatures.
   - Adapters: which ones implement/call them.
   - Patterns: name each pattern from ARCHITECTURE §5 you use and where. Prefer adding a class
     (provider module, `ClaimRule`, policy entry) over editing core branches.
   - Data: Flyway migration, indexes, lock order (target → transaction), transaction boundaries.
     No network calls inside a transaction.
   - Configuration and fail-closed validation.
   - Failure behaviour for every dependency (DB, Kafka, provider, backend).
4. **Check against the invariants** in `AGENTS.md`. For each, write one line on how the design
   keeps it, or "not affected".
5. **Plan tests.** List unit, integration and scenario tests; map to LLD T-ids.
6. **Look for gaps.** Concurrency (two routes, retries, crashes), idempotency, ordering, scale
   (what happens at 10× load), security (secrets, PII, trust boundaries). Fix the design, don't
   defer silently.
7. **Record decisions.** If a choice changes or adds to `DECISIONS.md`, update it in the same change.
8. Set status `draft` and ask for approval. Do not write production code in this skill.

## Output quality bar

- Signatures, tables and names are concrete — no "handle errors appropriately".
- Every new behaviour has a test listed.
- A reader could implement it without asking a question.
