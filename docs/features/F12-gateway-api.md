# F12 — Gateway API: validations, payment requests, status checks

| | |
|---|---|
| Status | done |
| Milestone | M1 |
| Depends on | F05, F08 |
| Design references | LLD §2, §4, §9 step 1, §10 · DECISIONS D2, D23 |

## 1. Goal
Applications make every provider call through Aegis, so provider secrets live only in Aegis, every
call is recorded, double payments and abusive status checks never leave, and Aegis never calls a
provider on its own.

## 2. Scope
- In: `POST /gw/{connection}/validations | payments | status`, application authentication, the
  provider HTTP client, recording of requests and replies.
- Out: mTLS termination (the reverse proxy's; Aegis checks the client-certificate subject it is
  given), outbound request signing beyond what adapters do.

## 3. Design
- **Authentication** (`web.security.ApplicationAuthentication`): `Authorization: Bearer <api key>`
  (constant-time lookup); if the application has `client-cert-subject`, the presented certificate
  must match. 401 / 403. The use case then checks the connection belongs to the caller (403, T16).
- **Validation:** `facts` (merchant ref, amount) → `prepare` → send → `reply` → one row with the
  request and reply (sealed); `CREATED` or `FAILED`. The provider's reply goes back unchanged.
- **Payment request:** in one transaction under the validation lock, `Gate.permit` (validated, amount,
  payable, nothing in flight) and, if allowed, the request is **written first** as a `PAY_REQUEST`
  entry (in flight). Then it is sent with no transaction open, and the reply is attached to that entry.
  A blocked request is answered 409 and never sent (T10). If the provider cannot be reached the
  request stays in flight until a status check or a result settles it (Aegis never expires it).
- **Status check:** `Gate.permit` against the provider's enquiry limits (T11, 429, provider not
  called); then the reply goes through `Gate.receive` like any message (a SUCCESS marks the
  validation PAID; a FAILED after PAID is `DOWNGRADE`) and is recorded with request and reply.
- **Response:** provider status and body unchanged + `X-Aegis-Verdict: PASSED | BLOCKED <REASON>`;
  refusals are RFC 9457 problems with `reason`. Provider unreachable → 503.
- `ProviderHttpClient`: JDK client, 3 s connect, 10 s answer, no redirects, no retries, on the
  caller's virtual thread.
- **Patterns:** Proxy (the application's provider calls), Reservation before side effect,
  Ports & Adapters.

## 4. Tests
| Test | Level | Covers |
|---|---|---|
| `GatewayIT` | integration (scripted `FakeProvider`, `TestClock`) | Razorpay order with Aegis's credentials, stored sealed, key never stored (LLD §9 step 1); refused validation → FAILED; T16 (no key, unknown key, other app, unknown connection, unsupported call); LLD §10 bill payment: pay → T10 retry refused and not sent → T11 status check too early (provider not called) → status after 2 min → PAID; provider down → request stays in flight |

## 5. Done when
- [x] Tests pass; `./mvnw verify` green
- [x] LLD §4, DECISIONS D23 updated
