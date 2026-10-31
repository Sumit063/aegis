---
name: code-review
description: Review Aegis code changes for correctness, payment-safety invariants, architecture, tests and security. Use before committing or merging, or when asked to review a diff, branch or PR in this repo.
---

# Code review

Review the diff (`git diff`, `git diff master...HEAD`, or the PR) against the design note and this
checklist. Report findings ranked by severity with file:line and a concrete fix. Verify each
finding by reading the code; do not report guesses.

## 1. Payment safety (blockers)
- [ ] Every inbound message runs the full check chain on every code path; no shortcut skips the
      signature (CVE-2026-87978 class).
- [ ] Nothing acknowledged before durable storage.
- [ ] All checks for a payment run in one transaction holding the validation row lock (`FOR UPDATE`);
      payment rows created with `ON CONFLICT DO NOTHING` on `(provider, payment_ref)`.
- [ ] Aegis never calls a provider except for an application request.
- [ ] No network call inside a `@Transactional` method (follow the call graph).
- [ ] Retries are safe to repeat; crashes between steps cannot lose or double an effect.
- [ ] Amounts are `long` minor units + currency; no floating point.
- [ ] Fail-closed on missing or empty secrets, unknown provider status, or verification error.

## 2. Security
- [ ] No secrets, tokens or personal data in logs, exceptions, metrics labels or tests.
- [ ] Signatures compared in constant time (`MessageDigest.isEqual`).
- [ ] Inputs bounded (body size, field lengths) and validated at the boundary.
- [ ] Only `/in/**` is public; `/gw/**` and `/admin/**` require mTLS and app credentials.
- [ ] Evidence (bodies) encrypted at rest; never logged.

## 3. Architecture and design
- [ ] `ArchitectureTest` passes; no new cross-adapter or cross-provider dependency.
- [ ] Domain stays framework-free.
- [ ] SOLID: single-purpose classes; behaviour added by new classes (adapter, `Check`), not core branches.
- [ ] Patterns used as documented in ARCHITECTURE §5; no speculative abstractions.
- [ ] JPA: explicit fetches, no lazy loading across layers, `ddl-auto=validate`, migration
      included and backward compatible.

## 4. Tests
- [ ] New behaviour has tests at the right level; scenario tests reference LLD T-ids.
- [ ] Edge cases: duplicates, replays, out-of-order, callback + webhook concurrently, provider
      down, application down.
- [ ] No `Thread.sleep`; deterministic clocks (`ClockPort`).
- [ ] `./mvnw verify` green.

## 5. Readability
- [ ] Names match conventions in `AGENTS.md`; comments explain why.
- [ ] Docs updated when behaviour or decisions changed.

## Output format
`[blocker|major|minor] path:line — problem — fix`. End with a one-line verdict:
approve / approve with minors / changes required.
