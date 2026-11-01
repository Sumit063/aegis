---
name: implement-feature
description: Implement an approved Aegis feature end to end with tests and a verified build. Use when asked to build, implement or code a feature from docs/features (e.g. "implement F04").
---

# Implement a feature

Precondition: `docs/features/<ID>-*.md` exists with status `approved`. If not, stop and use the
`solution-design` skill first.

## Steps

1. **Read** the design note, `AGENTS.md` invariants and `docs/engineering/ARCHITECTURE.md`.
   Set the note's status to `in progress`.
2. **Work inside-out, one slice at a time:**
   1. `domain` — types, invariants, checks + unit tests (no mocks).
   2. `application` — ports and use-case services + unit tests with in-memory fakes of ports.
   3. adapters — persistence, Kafka, web, providers + integration tests (Testcontainers, WireMock).
   4. configuration wiring and validation.
3. **After every slice:**
   ```bash
   ./mvnw spotless:apply
   ./mvnw verify
   ```
   Fix failures before moving on (see `verify-build`). Never continue on a red build.
4. **Commit each green slice** with a Conventional Commit message, e.g.
   `feat(domain): add downgrade check`. Never use `--no-verify`.
5. **Scenario tests:** implement the LLD T-ids named in the design note.
6. **Self-review** with the `code-review` skill; fix findings.
7. **Docs:** update `LLD.md` / `DECISIONS.md` if anything deviated from the design; set the
   feature status to `done` in the note and in `docs/features/README.md`.
8. Report: what was built, test counts, `./mvnw verify` result, any follow-ups.

## Rules

- Respect the architecture rules; if `ArchitectureTest` fails, fix the dependency, never the test.
- Add behaviour by adding classes (strategies, rules, provider modules), not core `if` branches.
- No network calls inside `@Transactional` methods.
- No secrets or realistic personal data in code, tests or logs.
- Keep each commit buildable.
