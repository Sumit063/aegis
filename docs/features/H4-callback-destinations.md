# H4 — Callback result binding, destination policy

| | |
|---|---|
| Status | done |
| Depends on | H3 |
| Design references | HARDENING §7, §8 · LLD §3 (browser callback), §8 · DECISIONS D27 |

## 1. Goal
The customer-facing redirect cannot be reused or replayed, and no configured destination can point
Aegis at cloud metadata or other link-local services.

## 2. Design
- **Signed result:** `return-url?ref=…&verdict=…&payment=<Aegis payment id>&ts=<epoch s>&signature=
  hex(HMAC(signing secret, ref.verdict.payment.ts))`. Bound to the payment (the forwarded message's
  `id` starts with it) and timestamped; the reference shop rejects results older than 10 minutes.
  Still display only: applications fulfil from forwarded messages.
- **No open redirect:** the redirect target is the application's configured `return-url`; nothing
  from the request chooses it.
- **Destinations** (`provider.spi.Destinations`), checked for every configured `base-url`,
  `forward-url`, `return-url` at startup, and again before every send (names can re-resolve):
  http(s) only; never link-local (169.254.0.0/16, fe80::/10), unspecified or multicast addresses, or
  metadata host names; `https` required in `prod` unless `aegis.network.allow-plain-http=true`
  (a private network the operator controls). Private and loopback addresses stay allowed:
  self-hosted merchants live there.
- **Trust model:** destinations come only from operator configuration (`aegis.yaml`), never from a
  request, header or payload; the policy guards against mistakes and DNS tricks, not against the
  operator.

## 3. Tests
| Test | Covers |
|---|---|
| `CallbackIT` | redirect carries payment and fresh timestamp; signature over all four |
| `AegisControllerTest.returnPageChecksAegisSignatureBindingAndAge` | other payment → 401; older than 10 min → 401 |
| `DestinationsTest` | metadata/link-local/unspecified/other schemes refused; public and private merchants allowed; production requires https |
| `lab/run.sh` | unchanged results (F7/F8 still blocked, 100/100 legitimate) |
