# Runbook

Operating Aegis in an environment (`staging` today, `prod` later). Every command runs on the host as
the deploy user, in `~/aegis/<env>`; `c` is `docker compose --project-name aegis-<env>`.

```bash
cd ~/aegis/staging && c() { docker compose --project-name aegis-staging "$@"; }
ADMIN="Authorization: Bearer $(grep ^AEGIS_ADMIN_API_KEY= .env | cut -d= -f2)"   # runner user, or sudo
```

## 1. Is it healthy?

| Check | Command |
|---|---|
| Everything at once | `scripts/check.sh staging` (from a laptop) |
| Readiness (database + Kafka) | `curl -s 127.0.0.1:8181/actuator/health/readiness` |
| What was blocked recently | `curl -s -H "$ADMIN" 127.0.0.1:8180/admin/blocked` |
| Oldest undelivered message | `curl -s 127.0.0.1:8181/actuator/prometheus \| grep aegis_forward_lag` |
| Every Aegis metric | `curl -s 127.0.0.1:8181/actuator/prometheus \| grep ^aegis_` |
| Partner traffic by result | `curl -s 127.0.0.1:8181/actuator/prometheus \| grep aegis_partner_requests_total` |

## 2. Guarantees and their limits

What always holds, on every profile:

- **Acknowledged = stored in Kafka.** A provider gets 2xx only after the broker confirmed the write
  (`acks=all`, idempotent producer). A timeout is answered 503; the provider retries; a second copy
  is recognised by its message ID.
- **At-least-once to the application, with stable identity.** Every passed message is delivered,
  with the same `id` every time, until the application acknowledges it or 72 hours pass. Then it is
  marked failed (`ForwardFailed` alert) and **kept**; an operator re-forwards it (§4). Aegis cannot
  make the application's own side effect happen exactly once: the application must ignore an `id` it
  has processed.
- **Facts at most once.** A message ID and a forwardable fact exist once (database keys, D25).
- **Not claimed:** exactly-once delivery, or zero loss when a disk is lost without the profile below.

What survives which failure (the first four rows are exercised under traffic by the *Failure lab*,
`lab/failures.sh`, on every CI run):

| Failure | Single-broker profile (dev, staging, today's single host) | Production durability profile |
|---|---|---|
| Aegis process restart or crash | nothing lost: unchecked records stay in Kafka, offsets commit after the database | same |
| Kafka process crash, host power loss | nothing acknowledged is lost: the broker writes each message to disk before acknowledging (`KAFKA_LOG_FLUSH_INTERVAL_MESSAGES=1`) | same; also no pause, another replica leads |
| Kafka disk or host lost | **acknowledged messages the Checker had not yet stored are lost** (normally seconds of backlog); providers do not resend acknowledged messages; recover with status checks (§5) | nothing acknowledged is lost while one in-sync replica survives (3 replicas, 2 in sync) |
| PostgreSQL down | Checker pauses, messages wait in Kafka; Gateway API answers 503 | same |
| PostgreSQL disk lost | records since the last backup are lost; restore (§5) | same unless the database is replicated (outside Aegis) |
| Application down for less than 72 h | delivered when it returns | same |
| Application down for more than 72 h | marked failed, alerted, kept; re-forward after it returns (§4) | same |

**Production durability profile:** three Kafka brokers on separate disks,
`AEGIS_KAFKA_REPLICAS=3`, `AEGIS_KAFKA_MIN_INSYNC=2` (set before the topics are first created;
existing topics are changed with `kafka-reassign-partitions.sh` and `kafka-configs.sh`), PostgreSQL
backups copied off the host (§5), ideally a streaming replica. The single-broker profile is what the
compose files run; it is not disk-loss safe and is documented as such.

## 3. Common situations

| Situation | What happens | What to do |
|---|---|---|
| Kafka down | Ingress answers 503; providers retry (Razorpay: 24 h) | restart Kafka: `c up -d kafka` |
| PostgreSQL down | Checker pauses (messages wait in Kafka); Gateway API 503 | restart: `c up -d postgres`; the Checker resumes by itself |
| Application down | deliveries retry with backoff; lag alert after 5 min | nothing; or pause it (§4) to stop retries |
| `ForwardFailed` alert | a message gave up after 72 h | fix the application, then re-forward (§4) |
| `DeadLetters` alert | a record could not be processed | §4 replay after the fix |
| `PaidTwice` alert | a customer paid twice for one order | refund the second payment at the provider |
| `ForgedMessages` alert | forged messages on a connection | nothing is accepted; check the source, rotate the webhook secret if a leak is suspected (§6) |
| Redis down (`PartnerLimitsUnavailable`) | partner requests 503; everything else unaffected | `c up -d redis`; partners retry; allowances start full |
| `PartnerForgeries` alert | unauthenticated requests on `/partner` | nothing reached the application; if one partner's name dominates, ask it about its clock and secret, rotate (§6) |
| `PartnerOutcomeUnknown` alert | the application took a partner request and did not answer | the partner gets 504 for that request ID; check the application for it (`X-Aegis-Request-Id`) and tell the partner its outcome |

## 4. Operations

```bash
# Pause / resume deliveries to an application (messages queue, nothing is lost)
curl -s -H "$ADMIN" -X PUT -H 'Content-Type: application/json' -d '{"pausedApplications":["shop"]}' 127.0.0.1:8180/admin/settings
curl -s -H "$ADMIN" -X PUT -H 'Content-Type: application/json' -d '{"pausedApplications":[]}' 127.0.0.1:8180/admin/settings

# Find a payment by any reference, then re-forward one of its messages
curl -s -H "$ADMIN" "127.0.0.1:8180/admin/payments?ref=pay_XXXX"
curl -s -H "$ADMIN" -X POST 127.0.0.1:8180/admin/payments/<id>/messages/<n>/forward

# Dead letters: look, then replay into the ingress topic once the cause is fixed
c exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
  --topic aegis.inbound.dlt --from-beginning --property print.key=true --timeout-ms 5000
c exec kafka bash -c '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
  --topic aegis.inbound.dlt --from-beginning --property print.key=true --property key.separator="|" --timeout-ms 5000 \
  | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 --topic aegis.inbound \
    --property parse.key=true --property key.separator="|"'
```

Replaying is safe: a record already processed is recognised as a repeat.

**Public ingress (staging):** `aegis-staging.sqyra.com` reaches Aegis through the Cloudflare tunnel
of the site stack, for paths matching `^/in/` only (D32). The tunnel container joins this stack's
network; after it is recreated, run `docker network connect aegis-staging_default sqyra-site-tunnel-1`.
Check: `curl -X POST https://aegis-staging.sqyra.com/in/razorpay` → 401, `/admin/blocked` → 404.
Staging's Razorpay connection uses test-mode keys; the `shop` application is paused (no shop runs
there), so passed messages wait in the queue.

**Onboarding a partner:** generate its secret (`openssl rand -hex 32`), add it to `.env`
(`ACME_SECRET=…`), give the application a `partner-url` and add the partner with its operations and
rates to `aegis.yaml` (`deploy/aegis.example.yaml`), then `c up -d aegis`. Hand the partner its
secret over a secure channel with the contract in LLD §14 (headers, canonical string, freshness,
request IDs). Look up one request: `curl -s -H "$ADMIN" 127.0.0.1:8180/admin/partners/acme/requests/<id>`.
Rotating a partner's secret: add the new one first in the list, keep the old one second until the
partner has switched, then remove it.

**Adding a connection or application:** put its secrets in `.env` (mode 600) and the entry in
`aegis.yaml` (`deploy/aegis.example.yaml` shows each provider), then `c up -d aegis`. Every `.env`
value is passed into the container, so the `${...}` placeholders resolve; a missing one stops
startup (`c logs aegis` names it).

## 5. Retention, backup, restore, disaster recovery

One retention policy, applied by the daily retention job and by backup rotation:

| Data | Kept | Why |
|---|---|---|
| Payment records (passed and blocked messages of genuine payments, refunds, delivery attempts, encrypted bodies and replies) | 180 days after the payment's **last activity**; never while a delivery is due | far beyond any provider's retry window (Razorpay 24 h), so a purged message cannot replay as new; a late refund keeps the whole history |
| Validations with no payment | 180 days after last activity | same |
| Rejected traffic (bad signature, unparseable: untrusted rows) | 7 days | security telemetry, not evidence of money (D26) |
| Kafka records | Kafka's retention (7 days default) | a buffer, not the record of truth |
| Backups (`pg_dump`) | 14 daily dumps (the `backup` container) | restore point for the database |
| Evidence keys | until no retained record and no kept backup holds data sealed with them (§6) | a removed key makes that data unreadable |

A restored environment keeps the same policy: the next retention run removes what has aged out.

| What | How | Loss if the host dies |
|---|---|---|
| PostgreSQL (all records) | daily `pg_dump` by the stack's `backup` container into `~/aegis/<env>/backups/`; copy them off the host | since the last dump |
| Kafka (messages not yet checked) | not backed up; normally drained within seconds | single-broker profile: messages acknowledged but not yet checked (§2) |
| `.env`, `aegis.yaml` (secrets, configuration) | copy to a password manager / secret store when changed | **everything sealed with the evidence key becomes unreadable without it** |

The `backup` container dumps once a day (on start, then whenever the day's dump is missing) and
keeps the newest 14; `scripts/check.sh` fails if none is under 26 h old. Dumps are readable only by
root (mode 600) and hold evidence sealed with the evidence key, so a dump restores only with its
`.env`. Copying them off the host is still the operator's job: a dump on the same disk does not
survive the host.

**Rehearsal.** The *Restore rehearsal* workflow (weekly, or `gh workflow run "Restore rehearsal"`)
runs `deploy/rehearse-restore.sh staging` on the host: it restores the newest dump into a throwaway
copy (project `aegis-staging-restore`, admin role only, so nothing is received or delivered), starts
Aegis on it and requires `GET /admin/evidence` to open every sampled value. The copy is removed
afterwards.

```bash
# Restore for real into a fresh environment: deploy it (same .env and aegis.yaml), stop Aegis, restore, start
c stop aegis
c run --rm --no-deps -T --entrypoint pg_restore backup --clean --if-exists --no-owner -d aegis /backups/aegis-<date>.dump
c start aegis && scripts/check.sh staging
curl -s -H "$ADMIN" 127.0.0.1:8180/admin/evidence     # "unreadable": 0
```

After a restore, messages the providers sent between the dump and the failure are recovered by
**application-initiated status checks** through Aegis (`POST /gw/<connection>/status`), never by
Aegis polling on its own (D2).

## 6. Secret rotation

| Secret | Steps |
|---|---|
| Provider webhook secret | add the new one first in `aegis.yaml` (`webhook: [new, old]`), deploy, change it at the provider, then remove the old one |
| Provider API key | create the new key at the provider, replace `key-secret`/`key-id`, deploy, revoke the old key |
| Application signing secret / API key | coordinate with the application (it verifies Aegis's signature) |
| Evidence key | add `k2` to `aegis.evidence.keys`, make it `current`, deploy. `k1` still opens old data. Remove `k1` only when no record holds it (`GET /admin/evidence` shows no `k1` in `sealedByKey`, at the latest 180 days after its last use) **and** the oldest kept backup was taken after that (14 more days). |
| Admin key | replace `AEGIS_ADMIN_API_KEY` in `.env`, deploy |
