---
name: verify-build
description: Run and interpret the Aegis build and fix failures (formatting, compile, unit, ArchUnit, integration, coverage, enforcer). Use after every code change and before reporting work as done.
---

# Verify the build

## Run

```bash
./mvnw spotless:apply      # always first: formatting is never a manual fix
./mvnw verify              # full gate
```

Faster loops while iterating: `./mvnw test` (unit + architecture), or a single test:
`./mvnw test -Dtest=ClassNameTest`. Always finish with `./mvnw verify`.

Integration tests need Docker. If Testcontainers cannot find Docker on a Colima setup, export:
`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

## Read failures in this order

| Failure | Meaning | Fix |
|---|---|---|
| `spotless:check` | Formatting | `./mvnw spotless:apply` |
| Enforcer | Wrong Java/Maven | Use JDK 25 and the wrapper `./mvnw` |
| Compilation | Code error | Fix the code |
| `ArchitectureTest` | A dependency rule was broken | Move the code or invert the dependency via a port. Never weaken the rule. |
| Unit test | Behaviour regression | Fix code or (if the design changed) the test, and say which |
| `*IT` | Adapter/infra behaviour | Check container logs in the output; check migrations |
| JaCoCo check | Coverage below 80 % in domain/application | Add missing tests for real behaviour |

## Rules
- Report the exact command and result. Never claim success on a red or skipped build.
- Do not skip tests (`-DskipTests`) or hooks (`--no-verify`) to get green.
