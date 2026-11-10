#!/usr/bin/env bash
# Partner lab (F27, LLD §14, DECISIONS D35/D36): partners call the shop's recharge API, directly
# (static API key, as many partner APIs are built) and through two Aegis instances sharing Redis.
# Direct, the attacks work; through Aegis every one is stopped, every legitimate request runs exactly
# once, and failures of Redis and of the shop never cause a second execution.
# Writes lab/PARTNERS.md; exits 1 if any scenario fails.
#   lab/partners.sh            KEEP=1 lab/partners.sh leaves the lab running
#   LOAD=500 lab/partners.sh   distinct recharges in the load scenario (each sent twice at once)
set -euo pipefail
cd "$(dirname "$0")/.."

if docker compose version >/dev/null 2>&1; then compose=(docker compose); else compose=(docker-compose); fi
compose+=(-f lab/compose.yaml)
LOAD="${LOAD:-500}"

lab/keys.sh >/dev/null
./mvnw -q -B -ntp -DskipTests package -pl lab/mock-gateway,lab/vulnshop,lab/npci-switch,aegis-service -am
"${compose[@]}" up -d --build --wait --quiet-pull >/dev/null
[[ "${KEEP:-}" == 1 ]] || trap '"${compose[@]}" down -v >/dev/null 2>&1' EXIT

AEGIS="http://127.0.0.1:8080"
AEGIS_2="http://127.0.0.1:8082"
DIRECT="http://127.0.0.1:8091"
SHOP="http://127.0.0.1:8092"
API_KEY="lab-partner-api-key-not-real-0000000001"
ACME="lab-acme-partner-secret-not-real-000000001"
FLOOD="lab-flood-partner-secret-not-real-00000001"
work="$(mktemp -d)"
rows=()
failed=0

# ---- helpers -------------------------------------------------------------------------------

id() { echo "$1$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"; }
now() { date +%s; }
body() { echo "{\"mobile\":\"9000000001\",\"amount\":${1:-49900}}"; }

# The documented signature (LLD §14), computed with openssl: nothing here shares code with Aegis.
sign() { # secret method path ts id body
  local digest
  digest="$(printf '%s' "$6" | openssl dgst -sha256 -r | cut -d' ' -f1)"
  printf 'v1\n%s\n%s\n%s\n%s\n%s' "$2" "/partner$3" "$4" "$5" "$digest" | openssl dgst -sha256 -hmac "$1" -r | cut -d' ' -f1
}

# Calls Aegis's partner API; prints "<status> <reason or replayed>".
# With RETRY_AFTER=1, also prints whether a Retry-After header came back.
partner() { # base partner secret method path id body [ts] [signature]
  local ts="${8:-$(now)}" sig out h b extra
  sig="${9:-v1=$(sign "$3" "$4" "$5" "$ts" "$6" "$7")}"
  h="$(mktemp "$work/h.XXXXXX")"; b="$(mktemp "$work/b.XXXXXX")"
  out="$(curl -s --path-as-is -D "$h" -o "$b" -w '%{http_code}' --max-time 20 -X "$4" "$1/partner$5" \
    -H "X-Partner-Id: $2" -H "X-Request-Id: $6" -H "X-Timestamp: $ts" -H "X-Signature: $sig" \
    -H 'Content-Type: application/json' ${7:+--data-binary "$7"})" || out=000
  extra="$(jq -r '.reason // empty' "$b" 2>/dev/null || true)"
  grep -qi '^x-aegis-replayed: true' "$h" && extra="replayed"
  [[ "${RETRY_AFTER:-}" == 1 ]] && grep -qi '^retry-after:' "$h" && extra="$extra Retry-After"
  rm -f "$h" "$b"
  echo "$out${extra:+ $extra}"
}
acme() { partner "$AEGIS" acme "$ACME" "$@"; }

# The direct shop: a static key, nothing else.
direct() { # id body
  curl -s -o /dev/null -w '%{http_code}' -X POST "$DIRECT/partner-api/recharges" \
    -H "X-Api-Key: $API_KEY" -H "X-Request-Id: $1" -H 'Content-Type: application/json' --data-binary "$2"
}

ran() { # shop id → how many times the shop executed it
  curl -fsS "$1/lab/partner-executions" | jq -r --arg id "$2" '.byRequest[$id] // 0'
}

wait_healthy() { # service
  local deadline=$((SECONDS + 90))
  until [[ "$(docker inspect -f '{{.State.Health.Status}}' "aegis-lab-$1-1" 2>/dev/null)" == healthy ]] || ((SECONDS >= deadline)); do
    sleep 1
  done
}

result() { # id expected outcome ok
  local mark="PASS"
  [[ "$4" == 1 ]] || { mark="FAIL"; failed=$((failed + 1)); }
  printf '%s %-5s %-62s %s\n' "$mark" "$1" "$2" "$3"
  rows+=("| $1 | $2 | $3 | $mark |")
}

# ---- without Aegis: the attacks work -------------------------------------------------------

echo "── without Aegis (expected: exploited; the partner API trusts its key and the body)"

r="$(id D)"; direct "$r" "$(body)" >/dev/null; sleep 1; direct "$r" "$(body)" >/dev/null
n="$(ran "$DIRECT" "$r")"
result D1 "captured request replayed" "executed ${n}×" "$([[ "$n" == 2 ]] && echo 1 || echo 0)"

paise() { curl -fsS "$1/lab/partner-executions" | jq -r .rechargedPaise; }
r="$(id D)"; before="$(paise "$DIRECT")"; direct "$r" "$(body 9990000)" >/dev/null
added=$(($(paise "$DIRECT") - before))
result D2 "amount changed in transit (₹499 → ₹99,900)" "executed: ₹$((added / 100)) recharged" \
  "$([[ "$added" == 9990000 ]] && echo 1 || echo 0)"

r="$(id D)"
seq 1 20 | xargs -P 20 -I{} curl -s -o /dev/null -X POST "$DIRECT/partner-api/recharges" \
  -H "X-Api-Key: $API_KEY" -H "X-Request-Id: $r" -H 'Content-Type: application/json' --data-binary "$(body)"
n="$(ran "$DIRECT" "$r")"
result D3 "20 copies of one request at once (a retry storm)" "executed ${n}×" "$([[ "$n" == 20 ]] && echo 1 || echo 0)"

# ---- through Aegis: legitimate requests ----------------------------------------------------

echo "── through Aegis: legitimate partners (expected: executed exactly once)"

r="$(id R)"; answer="$(acme POST /recharges "$r" "$(body)")"
result L1 "signed recharge" "$answer, executed $(ran "$SHOP" "$r")×, shop verified Aegis's signature" \
  "$([[ "$answer" == 201 && "$(ran "$SHOP" "$r")" == 1 ]] && echo 1 || echo 0)"

answer="$(acme GET "/status/$r" "$(id S)" "")"
result L2 "status read" "$answer" "$([[ "$answer" == 200 ]] && echo 1 || echo 0)"

r="$(id R)"; answer="$(partner "$AEGIS" acme "lab-acme-previous-secret-not-real-00000001" POST /recharges "$r" "$(body)")"
result L3 "signed with the previous secret (rotation)" "$answer" "$([[ "$answer" == 201 ]] && echo 1 || echo 0)"

r="$(id R)"; first="$(acme POST /recharges "$r" "$(body)")"; sleep 2
retry="$(partner "$AEGIS_2" acme "$ACME" POST /recharges "$r" "$(body)")"
result L4 "retry of an answered request, to the other instance" "$first, then $retry, executed $(ran "$SHOP" "$r")×" \
  "$([[ "$retry" == "201 replayed" && "$(ran "$SHOP" "$r")" == 1 ]] && echo 1 || echo 0)"

# ---- through Aegis: attacks and misuse -----------------------------------------------------

echo "── through Aegis: attacks and misuse (expected: stopped before the shop)"

r="$(id A)"; answer="$(acme POST /recharges "$r" "$(body)" "$(now)" "v1=$(printf '0%.0s' {1..64})")"
result P1 "forged signature" "$answer, executed $(ran "$SHOP" "$r")×" "$([[ "$answer" == "401 UNAUTHENTICATED" && "$(ran "$SHOP" "$r")" == 0 ]] && echo 1 || echo 0)"

r="$(id A)"; ts="$(now)"; sig="v1=$(sign "$ACME" POST /recharges "$ts" "$r" "$(body)")"
answer="$(acme POST /recharges "$r" "$(body 9990000)" "$ts" "$sig")"
result P2 "amount changed after signing" "$answer, executed $(ran "$SHOP" "$r")×" "$([[ "$answer" == "401 UNAUTHENTICATED" && "$(ran "$SHOP" "$r")" == 0 ]] && echo 1 || echo 0)"

r="$(id A)"; answer="$(acme POST /recharges "$r" "$(body)" "$(($(now) - 600))")"
result P3 "captured request replayed 10 minutes later" "$answer, executed $(ran "$SHOP" "$r")×" "$([[ "$answer" == "401 STALE" && "$(ran "$SHOP" "$r")" == 0 ]] && echo 1 || echo 0)"

answer="$(partner "$AEGIS" acme "$FLOOD" POST /recharges "$(id A)" "$(body)")"
result P4 "signed with another partner's secret" "$answer" "$([[ "$answer" == "401 UNAUTHENTICATED" ]] && echo 1 || echo 0)"

export -f partner sign now
export work AEGIS ACME
R="$(id A)" B="$(body)" TS="$(now)"; export R B TS; r="$R"
seq 1 50 | xargs -P 50 -I{} bash -c 'partner "$AEGIS" acme "$ACME" POST /recharges "$R" "$B" "$TS"' > "$work/storm"
sleep 1
ok="$(grep -c '^201' "$work/storm" || true)"; busy="$(grep -c '^409' "$work/storm" || true)"
result P5 "50 copies of one request at once" "executed $(ran "$SHOP" "$r")× ($ok answered or replayed, $busy told in progress)" \
  "$([[ "$(ran "$SHOP" "$r")" == 1 ]] && echo 1 || echo 0)"

r="$(id A)"; acme POST /recharges "$r" "$(body)" >/dev/null
answer="$(acme POST /recharges "$r" "$(body 9990000)")"
result P6 "request ID reused for a different amount" "$answer, executed $(ran "$SHOP" "$r")×" "$([[ "$answer" == "422 REQUEST_ID_REUSED" && "$(ran "$SHOP" "$r")" == 1 ]] && echo 1 || echo 0)"

answer="$(acme POST /payouts "$(id A)" "$(body)")"
result P7 "operation not granted (/payouts)" "$answer" "$([[ "$answer" == "403 NOT_PERMITTED" ]] && echo 1 || echo 0)"

answer="$(acme DELETE /recharges "$(id A)" "")"
result P8 "method not granted (DELETE)" "$answer" "$([[ "$answer" == "403 NOT_PERMITTED" ]] && echo 1 || echo 0)"

# Refused by Aegis (403), or by the server before Aegis (400; 404 when it resolves outside /partner).
a1="$(acme POST /recharges/../../lab/partner-executions "$(id A)" "$(body)")"
a2="$(acme POST /recharges/..%2F..%2Flab "$(id A)" "$(body)")"
result P9 "path traversal (dot segments, encoded slashes)" "$a1; $a2; the shop not reached" \
  "$([[ "$a1" =~ ^(400|403|404) && "$a2" =~ ^(400|403|404) ]] && echo 1 || echo 0)"

# The flood partner: burst 10, 60 a minute; requests alternate between the two instances.
allowed=0; limited=0; flood_start="$(perl -MTime::HiRes=time -e 'printf "%.3f", time')"
for i in $(seq 1 30); do
  base="$AEGIS"; ((i % 2)) && base="$AEGIS_2"
  answer="$(partner "$base" flood "$FLOOD" POST /recharges "$(id F)" "$(body)")"
  [[ "$answer" == 201 ]] && allowed=$((allowed + 1))
  [[ "$answer" == "429 RATE_LIMIT" ]] && limited=$((limited + 1))
done
# One more is earned per second (60/min): the bound counts the seconds the flood took.
elapsed="$(perl -MTime::HiRes=time -e "printf '%d', time - $flood_start")"
bound=$((10 + elapsed + 1))
next="$(RETRY_AFTER=1 partner "$AEGIS" flood "$FLOOD" POST /recharges "$(id F)" "$(body)")"
result P10 "flood: 30 requests, rate 60/min, burst 10" "$allowed allowed in ${elapsed} s (bound $bound), $limited refused; next: $next" \
  "$([[ "$allowed" -le "$bound" && "$next" == "429 RATE_LIMIT Retry-After" ]] && echo 1 || echo 0)"
result P11 "the rate holds across two instances (shared Redis)" "$allowed allowed in total; two separate allowances would allow $((2 * 10 + 2 * elapsed))" \
  "$([[ "$allowed" -le "$bound" && "$allowed" -lt 20 ]] && echo 1 || echo 0)"
answer="$(acme POST /recharges "$(id R)" "$(body)")"
result P12 "another partner during the flood" "$answer" "$([[ "$answer" == 201 ]] && echo 1 || echo 0)"

# ---- failures --------------------------------------------------------------------------------

echo "── failures (expected: refused or unknown, never executed twice)"

r="$(id X)"
"${compose[@]}" pause redis >/dev/null 2>&1
down="$(acme POST /recharges "$r" "$(body)")"
"${compose[@]}" unpause redis >/dev/null 2>&1
after="$(acme POST /recharges "$r" "$(body)")"
result X1 "Redis unreachable, then back" "$down; after: $after, executed $(ran "$SHOP" "$r")×" \
  "$([[ "$down" == "503 LIMITS_UNAVAILABLE" && "$after" == 201 && "$(ran "$SHOP" "$r")" == 1 ]] && echo 1 || echo 0)"

r="$(id X)"
"${compose[@]}" stop shop-aegis >/dev/null 2>&1
down="$(acme POST /recharges "$r" "$(body)")"
"${compose[@]}" start shop-aegis >/dev/null 2>&1; wait_healthy shop-aegis
after="$(acme POST /recharges "$r" "$(body)")"
result X2 "shop down (never reached), then back" "$down; retry: $after, executed $(ran "$SHOP" "$r")×" \
  "$([[ "$down" == "503 APPLICATION_UNAVAILABLE" && "$after" == 201 && "$(ran "$SHOP" "$r")" == 1 ]] && echo 1 || echo 0)"

r="$(id X)"
"${compose[@]}" pause shop-aegis >/dev/null 2>&1
first="$(acme POST /recharges "$r" "$(body)")"
again="$(acme POST /recharges "$r" "$(body)")"
"${compose[@]}" unpause shop-aegis >/dev/null 2>&1
sleep 3
later="$(acme POST /recharges "$r" "$(body)")"
n="$(ran "$SHOP" "$r")"
result X3 "shop takes the request and does not answer" "$first; repeat: $again; after it recovers: $later; executed ${n}×" \
  "$([[ "$first" == "504 OUTCOME_UNKNOWN" && "$again" == "504 OUTCOME_UNKNOWN" && "$later" == "504 OUTCOME_UNKNOWN" && "$n" -le 1 ]] && echo 1 || echo 0)"

# ---- load ------------------------------------------------------------------------------------

echo "── load: $LOAD recharges, each sent twice at once, across both instances"
one() { # n
  local r ts b base
  r="L$1-$RUN"; ts="$(now)"; b="$(body)"; base="$AEGIS"; (($1 % 2)) && base="$AEGIS_2"
  partner "$base" acme "$ACME" POST /recharges "$r" "$b" "$ts" &
  partner "$AEGIS" acme "$ACME" POST /recharges "$r" "$b" "$ts"
  wait
}
clock() { perl -MTime::HiRes=time -e 'printf "%.3f", time'; }
export -f one partner sign now body
export AEGIS AEGIS_2 ACME work
RUN="$(id run)"; export RUN
start="$(clock)"
seq 1 "$LOAD" | xargs -P 32 -I{} bash -c 'one {}' > "$work/load"
took="$(awk -v a="$start" -v b="$(clock)" 'BEGIN { printf "%.2f", b - a }')"
once="$(curl -fsS "$SHOP/lab/partner-executions" | jq --arg run "$RUN" '[.byRequest | to_entries[] | select(.key | endswith("-" + $run)) | select(.value == 1)] | length')"
more="$(curl -fsS "$SHOP/lab/partner-executions" | jq --arg run "$RUN" '[.byRequest | to_entries[] | select(.key | endswith("-" + $run)) | select(.value > 1)] | length')"
rate="$(awk -v n="$LOAD" -v t="$took" 'BEGIN { printf "%d", 2 * n / t }')"
result Load "$LOAD recharges × 2 copies at once, 32 in parallel" "$once/$LOAD executed once, $more more than once, ${took} s, ${rate} requests/s" \
  "$([[ "$once" == "$LOAD" && "$more" == 0 ]] && echo 1 || echo 0)"

{
  echo "# Partner lab results"
  echo
  echo "Generated by \`lab/partners.sh\` (F27): partners call the shop's recharge API directly (a static"
  echo "API key) and through two Aegis instances sharing PostgreSQL and Redis"
  echo "([LLD §14](../docs/design/LLD.md#14-partner-pipeline-m4), D35, D36). D rows show the problem"
  echo "without Aegis: they pass when the attack works."
  echo
  echo "| # | Scenario | Observed | Result |"
  echo "|---|---|---|---|"
  printf '%s\n' "${rows[@]}"
} > lab/PARTNERS.md
echo "── written lab/PARTNERS.md"
((failed == 0))
