#!/usr/bin/env bash
# Shared helpers for the attack scripts. Each attack sources this file and calls `verdict` once.
#   TARGET=direct   the shop verifies provider messages itself, with the flaw under test on (default)
#   TARGET=careful  the same shop with every flaw off: the control, each attack must fail
#   TARGET=aegis    provider messages go to Aegis; the shop sits behind it, flaw still on
set -euo pipefail

TARGET="${TARGET:-direct}"
GATEWAY="${GATEWAY:-http://127.0.0.1:8090}"
KEY_ID="rzp_test_lab"
WEBHOOK_SECRET_KNOWN_TO_ATTACKER=""   # attackers never have the real secret (D18 covers the leak case)

case "$TARGET" in
  direct | careful)
    SHOP="${SHOP:-http://127.0.0.1:8091}"
    WEBHOOK_URL="$SHOP/webhooks/razorpay"
    callback_url() { echo "$SHOP/payment/callback?order=$1"; }
    ;;
  aegis)
    SHOP="${SHOP:-http://127.0.0.1:8092}"
    KEY_ID="rzp_test_aegis"
    WEBHOOK_URL="${AEGIS:-http://127.0.0.1:8080}/in/razorpay"
    callback_url() { echo "${AEGIS:-http://127.0.0.1:8080}/in/razorpay/callback"; }
    ;;
  *) echo "unknown TARGET $TARGET" >&2; exit 2 ;;
esac

# Switches the shop's flaws for this attack: only the flaw under test is on.
flaws() {
  local list=""
  if [[ "$TARGET" != careful && $# -gt 0 ]]; then list="$(printf '"%s",' "$@" | sed 's/,$//')"; fi
  curl -fsS -X PUT "$SHOP/flaws" -H 'Content-Type: application/json' -d "{\"flaws\":[$list]}" >/dev/null
}

# Places an order of AMOUNT paise; prints "<shop id> <gateway order id>".
order() { curl -fsS -X POST "$SHOP/orders" -H 'Content-Type: application/json' -d "{\"amount\":$1}" | jq -r '"\(.id) \(.gateway_order_id)"'; }

# The customer pays in Checkout. JSON fields as for the mock's /test/checkout; prints its JSON result.
pay() { curl -fsS -X POST "$GATEWAY/test/checkout" -H 'Content-Type: application/json' -d "$1"; }

# Number of times an order was fulfilled, and its status.
fulfilments() { curl -fsS "$SHOP/orders/$1" | jq -r '.fulfilments'; }
status() { curl -fsS "$SHOP/orders/$1" | jq -r '.status'; }

# Hex HMAC-SHA256 of stdin with KEY ("" signs with an empty key, which HMAC pads to zeros).
hmac() { if [[ -z "$1" ]]; then openssl dgst -sha256 -mac HMAC -macopt hexkey:00 | awk '{print $NF}'; else openssl dgst -sha256 -hmac "$1" | awk '{print $NF}'; fi; }

# POSTs a webhook body with optional extra curl headers; prints the HTTP status.
send_webhook() { local body="$1"; shift; curl -s -o /dev/null -w '%{http_code}' -X POST "$WEBHOOK_URL" -H 'Content-Type: application/json' "$@" --data-binary "$body"; }

# A payment event as Razorpay would send it (forged unless signed with the real secret).
payment_event() { # event order_id amount [payment_id]
  printf '{"entity":"event","account_id":"acc_lab","event":"%s","contains":["payment"],"payload":{"payment":{"entity":{"id":"%s","entity":"payment","amount":%s,"currency":"INR","status":"captured","order_id":"%s","method":"upi","notes":{}}}},"created_at":%s}' \
    "$1" "${4:-pay_forged$RANDOM}" "$3" "$2" "$(date +%s)"
}

# Aegis delivers asynchronously (Kafka → Checker → Forwarder), so look at the shop only after
# anything that was going to arrive has arrived.
settle() { [[ "$TARGET" == aegis ]] && sleep 3 || true; }

# Prints one result line. LOSS is "yes" when the shop lost something; DETAIL says what happened.
verdict() { # id loss detail
  local result; [[ "$2" == yes ]] && result=exploited || result=blocked
  printf '%-4s %-9s %s\n' "$1" "$result" "$3"
}
