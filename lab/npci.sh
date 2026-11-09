#!/usr/bin/env bash
# NPCI lab (F24, docs/design/NPCI.md §4): bill payments (BBPS) and UPI payments go through Aegis to
# the lab's NPCI switch; the switch's results come back through Aegis to the shop's bill desk.
# Legitimate flows must reach the desk exactly as they happened; every attack must be stopped.
# Writes lab/NPCI.md; exits 1 if any scenario fails.
#   lab/npci.sh           KEEP=1 lab/npci.sh leaves the lab running
#   LOAD=300 lab/npci.sh  payments in the load scenario (half BBPS, half UPI)
set -euo pipefail
cd "$(dirname "$0")/.."

if docker compose version >/dev/null 2>&1; then compose=(docker compose); else compose=(docker-compose); fi
compose+=(-f lab/compose.yaml)
LOAD="${LOAD:-300}"

lab/keys.sh >/dev/null
./mvnw -q -B -ntp -DskipTests package -pl lab/mock-gateway,lab/vulnshop,lab/npci-switch,aegis-service -am
"${compose[@]}" up -d --build --wait --quiet-pull >/dev/null
[[ "${KEEP:-}" == 1 ]] || trap '"${compose[@]}" down -v >/dev/null 2>&1' EXIT

work="$(mktemp -d)"
rows=()
failed=0

# ---- helpers -------------------------------------------------------------------------------

# shellcheck source=npci-lib.sh
source lab/npci-lib.sh

# Waits until the desk was told exactly EXPECTED (JSON list) about TXN, or 30 s pass.
await_told() {
  local deadline=$((SECONDS + 30))
  until [[ "$(told "$1")" == "$2" ]] || ((SECONDS >= deadline)); do sleep 0.3; done
  [[ "$(told "$1")" == "$2" ]]
}

# Waits until Aegis recorded a verdict containing REASON for TXN.
await_verdict() {
  local deadline=$((SECONDS + 30))
  until verdicts "$1" | grep -q "$2" || ((SECONDS >= deadline)); do sleep 0.3; done
  verdicts "$1" | grep -q "$2"
}

result() { # id expected outcome ok
  local mark="PASS"
  [[ "$4" == 1 ]] || { mark="FAIL"; failed=$((failed + 1)); }
  printf '%-4s %-4s %-60s %s\n' "$mark" "$1" "$2" "$3"
  rows+=("| $1 | $2 | $3 | $mark |")
}

check() { "$@" >/dev/null 2>&1 && echo 1 || echo 0; }

# A payment the switch never answers: the target of attacks (no genuine result to confuse things).
silent_upi() { local v t; v="$(upi_validation)"; t="$(id T)"; plan SILENT >/dev/null; upi_pay "$v" "$t" >/dev/null; echo "$t"; }

# ---- legitimate traffic --------------------------------------------------------------------

echo "── legitimate payments (expected: the desk hears exactly what happened)"
legit() { # id label network script expected
  local txn ref
  plan "$4" >/dev/null
  txn="$(id T)"
  if [[ "$3" == bbps ]]; then ref="$(bbps_bill)"; bbps_pay "$ref" "$txn" >/dev/null; else ref="$(upi_validation)"; upi_pay "$ref" "$txn" >/dev/null; fi
  result "$1" "$2" "desk told $(await_told "$txn" "$5" && echo "$5" || told "$txn")" "$(check await_told "$txn" "$5")"
}
legit L1 "BBPS bill paid" bbps SUCCESS '["SUCCESS"]'
legit L2 "BBPS deemed, then paid" bbps DEEMED_THEN_SUCCESS '["PENDING","SUCCESS"]'
legit L3 "BBPS payment failed" bbps FAILURE '["FAILED"]'
legit L4 "UPI payment succeeded" upi SUCCESS '["SUCCESS"]'
legit L5 "UPI deemed, then reversed" upi DEEMED_THEN_REVERSAL '["PENDING","REVERSED"]'
legit L6 "UPI payment failed" upi FAILURE '["FAILED"]'

# ---- attacks ---------------------------------------------------------------------------------

echo "── attacks (expected: stopped; the desk is told nothing false)"
refused() { # id label kind
  local txn status
  txn="$(silent_upi)"
  status="$(attack "$3" "$txn")"
  sleep 1
  result "$1" "$2" "HTTP $status, desk told $(told "$txn")" "$([[ "$status" == 401 && "$(told "$txn")" == "[]" ]] && echo 1 || echo 0)"
}
refused N1 "forged result (attacker's key)" forged
refused N2 "signature wrapping" wrapped
refused N3 "amount changed after signing" tampered
refused N4 "XXE / DOCTYPE" xxe
refused N5a "algorithm swap: RSA-SHA1" sha1
refused N5b "key confusion: HMAC keyed with the public key" hmac
refused N6 "KeyInfo carrying the attacker's key" keyinfo
refused N6b "unsigned result" unsigned

txn="$(silent_upi)"
status="$(attack stale "$txn")"
ok=0; [[ "$status" == 200 ]] && await_verdict "$txn" REPLAY && [[ "$(told "$txn")" == "[]" ]] && ok=1
result N7 "result signed 2 hours ago" "HTTP $status, $(verdicts "$txn"), desk told $(told "$txn")" "$ok"

plan SUCCESS 4 >/dev/null
ref="$(bbps_bill)"; txn="$(id T)"; bbps_pay "$ref" "$txn" >/dev/null
await_told "$txn" '["SUCCESS"]' || true
sleep 2
status="$(attack replay "$txn")"
sleep 1
ok=0; [[ "$(told "$txn")" == '["SUCCESS"]' && "$status" == 200 ]] && ok=1
result N8 "result delivered 5×, then replayed" "Acked, desk told $(told "$txn")" "$ok"

plan SILENT >/dev/null
ref="$(bbps_bill)"; first="$(id T)"
bbps_pay "$ref" "$first" >/dev/null
before="$(received BillPaymentRequest)"
answer="$(bbps_pay "$ref" "$(id T)")"
ok=0; [[ "$answer" == "409 BLOCKED DUPLICATE_PAYMENT" && "$(received BillPaymentRequest)" == "$before" ]] && ok=1
result N9 "second payment while one is in flight" "$answer, switch saw no second request" "$ok"

before="$(received TxnStatusRequest)"
answer="$(bbps_status "$ref" "$first")"
ok=0; [[ "$answer" == "429 BLOCKED RATE_LIMIT" && "$(received TxnStatusRequest)" == "$before" ]] && ok=1
result N10 "status check right after the request" "$answer, switch not called" "$ok"

plan SUCCESS 0 true >/dev/null
ref="$(bbps_bill)"; txn="$(id T)"; bbps_pay "$ref" "$txn" >/dev/null
ok=0; await_verdict "$txn" AMOUNT_MISMATCH && [[ "$(told "$txn")" == "[]" ]] && ok=1
result N11 "genuine result for one paisa" "$(verdicts "$txn"), desk told $(told "$txn")" "$ok"

plan SUCCESS_THEN_FAILURE >/dev/null
ref="$(bbps_bill)"; txn="$(id T)"; bbps_pay "$ref" "$txn" >/dev/null
ok=0; await_verdict "$txn" DOWNGRADE && [[ "$(told "$txn")" == '["SUCCESS"]' ]] && ok=1
result N12 "FAILURE after SUCCESS" "$(verdicts "$txn"), desk told $(told "$txn")" "$ok"

before="$(received BillFetchRequest)"
answer="$(bbps_fetch "$ref")"
ok=0; [[ "$answer" == "409 BLOCKED DUPLICATE" && "$(received BillFetchRequest)" == "$before" ]] && ok=1
result N13 "bill reference fetched again" "$answer, switch not called" "$ok"

plan SUCCESS >/dev/null
ref="$(bbps_bill)"; txn="$(id T)"; bbps_pay "$ref" "$txn" >/dev/null
await_told "$txn" '["SUCCESS"]' || true
other="$(bbps_bill)"
before="$(received BillPaymentRequest)"
answer="$(bbps_pay "$other" "$txn")"
ok=0; [[ "$answer" == "409 BLOCKED DUPLICATE_PAYMENT" && "$(received BillPaymentRequest)" == "$before" ]] && ok=1
result N14 "transaction ID reused for another bill" "$answer, switch not called" "$ok"

# ---- load --------------------------------------------------------------------------------

echo "── load: $LOAD payments at once (half BBPS, half UPI)"
one() { # n → prints the transaction ID
  local txn ref
  txn="$(id L)"
  if (($1 % 2)); then ref="$(bbps_bill)"; bbps_pay "$ref" "$txn" >/dev/null; else ref="$(upi_validation)"; upi_pay "$ref" "$txn" >/dev/null; fi
  echo "$txn"
}
export -f one id ts gw bbps_fetch bbps_bill bbps_pay upi_validation upi_pay
export AEGIS DESK_KEY
start=$SECONDS
seq 1 "$LOAD" | xargs -P 16 -I{} bash -c 'one {}' > "$work/load"
deadline=$((SECONDS + 120))
while :; do
  settled="$(curl -fsS "$DESK/npci/payments" | jq --rawfile ids "$work/load" \
    '[($ids | split("\n") | map(select(length > 0)))[] as $t | select(.[$t] == ["SUCCESS"])] | length')"
  ((settled == LOAD || SECONDS >= deadline)) && break
  sleep 1
done
twice="$(curl -fsS "$DESK/npci/payments" | jq --rawfile ids "$work/load" \
  '[($ids | split("\n") | map(select(length > 0)))[] as $t | select((.[$t] // []) | length > 1)] | length')"
result Load "$LOAD payments, results through Aegis" "$settled/$LOAD told SUCCESS once, $twice told more than once, $((SECONDS - start)) s" \
  "$([[ "$settled" == "$LOAD" && "$twice" == 0 ]] && echo 1 || echo 0)"

{
  echo "# NPCI lab results"
  echo
  echo "Generated by \`lab/npci.sh\` (F24): BBPS and UPI through Aegis to the lab's NPCI switch"
  echo "([docs/design/NPCI.md](../docs/design/NPCI.md)); results back through Aegis to the shop's bill desk."
  echo
  echo "| # | Scenario | Observed | Result |"
  echo "|---|---|---|---|"
  printf '%s\n' "${rows[@]}"
} > lab/NPCI.md
echo "── written lab/NPCI.md"
((failed == 0))
