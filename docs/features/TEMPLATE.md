# F<NN> — <Feature name>

| | |
|---|---|
| Status | draft · approved · in progress · done |
| Milestone | M<n> |
| Depends on | F<NN>, … |
| Design references | HLD §…, LLD §…, DECISIONS D… |

## 1. Goal
One or two sentences: what this feature makes possible and why it is needed now.

## 2. Scope
- In:
- Out (explicitly):

## 3. Design
- **Domain:** new or changed types, invariants, state transitions.
- **Ports:** new `port.in` use cases and `port.out` interfaces (signatures).
- **Adapters:** which adapters implement or call them.
- **Patterns:** which patterns from ARCHITECTURE §5 apply, and where.
- **Data:** Flyway migration(s), indexes, locking.
- **Configuration:** new properties, defaults, validation.
- **Failure behaviour:** what happens on each dependency failure.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| | unit / IT / scenario | invariant, LLD T-id |

## 5. Done when
- [ ] All tests above pass; `./mvnw verify` green
- [ ] Docs updated (LLD/DECISIONS if behaviour or choices changed)
- [ ] Code review checklist passed
