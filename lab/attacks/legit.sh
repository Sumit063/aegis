#!/usr/bin/env bash
# Zero false blocks: N genuine payments (webhook and browser callback) through the target, every flaw
# off; each order must be fulfilled exactly once.
#   TARGET=aegis lab/attacks/legit.sh 100
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
count="${1:-100}"
flaws
shops=()
for _ in $(seq 1 "$count"); do
  read -r shop gw < <(order 49900)
  r="$(pay "{\"key_id\":\"$KEY_ID\",\"order_id\":\"$gw\"}")"
  curl -s -o /dev/null -X POST "$(callback_url "$shop")" \
    --data-urlencode "razorpay_order_id=$(jq -r .razorpay_order_id <<<"$r")" \
    --data-urlencode "razorpay_payment_id=$(jq -r .razorpay_payment_id <<<"$r")" \
    --data-urlencode "razorpay_signature=$(jq -r .razorpay_signature <<<"$r")"
  shops+=("$shop")
done
settle
exact=0
for shop in "${shops[@]}"; do [[ "$(fulfilments "$shop")" == 1 ]] && exact=$((exact + 1)); done
echo "legit $exact/$count fulfilled exactly once"
[[ $exact == "$count" ]]
