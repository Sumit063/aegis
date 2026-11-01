#!/usr/bin/env bash
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
# F9: callback and webhook each fulfil the same payment.
flaws F9
read -r shop gw < <(order 50000)
r="$(pay "{\"key_id\":\"$KEY_ID\",\"order_id\":\"$gw\"}")"
curl -s -o /dev/null -X POST "$(callback_url "$shop")" \
  --data-urlencode "razorpay_order_id=$(jq -r .razorpay_order_id <<<"$r")" \
  --data-urlencode "razorpay_payment_id=$(jq -r .razorpay_payment_id <<<"$r")" \
  --data-urlencode "razorpay_signature=$(jq -r .razorpay_signature <<<"$r")"
settle
n="$(fulfilments "$shop")"
verdict F9 "$([[ $n -gt 1 ]] && echo yes || echo no)" "webhook and callback for one payment → fulfilments $n"
