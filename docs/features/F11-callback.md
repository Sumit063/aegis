# F11 — Browser callback and signed redirect

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F10 |
| Design references | LLD §2, §3 (browser callback) · DECISIONS D21 |

## 1. Goal
Checkout results carried by the customer's browser get the same checks as webhooks, and the
customer lands on the application's page with a result the application can trust.

## 2. Scope
- In: `POST /in/{connection}/callback`, inline check through the Checker's use case, 303 redirect
  to the application's `return-url` with `ref`, `verdict`, `signature`.
- Out: rendering the result page (the application's), payment-failure forms (unsigned, blocked).

## 3. Design
- `IngressController.callback` → `HandleCallbackUseCase` / `CallbackService`: verify (fail closed),
  seal the body, call `CheckMessageUseCase` inline (no Kafka: the customer is waiting and a browser
  never retries), then redirect.
- Redirect: `return-url?ref=<merchant ref | unknown>&verdict=PASSED|BLOCKED&signature=hex(HMAC_SHA256(signing
  secret, ref + "." + verdict))`. A repeated post (back button) gets the original verdict and is
  only counted.
- **The return page is display only.** The application fulfils from the forwarded message (F13),
  never from the browser's redirect, which a customer can replay.
- Cookies and `Authorization` from the browser are never stored.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `CallbackIT` | integration | genuine callback → 303 with a valid signature, validation PAID, entry CALLBACK PASSED; repost counted; unsigned `status=success` (F7) and a signature for another order (F8) → BLOCKED; unknown connection 404 |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] LLD §3 states the redirect format
