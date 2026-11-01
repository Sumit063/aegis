#!/usr/bin/env bash
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
# F8: the callback fulfils the order named in the URL, not the order that was signed.
flaws F8
read -r big _ < <(order 1000000)
read -r _ small_gw < <(order 1000)
r="$(pay "{\"key_id\":\"$KEY_ID\",\"order_id\":\"$small_gw\",\"webhook\":false}")"
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$(callback_url "$big")" \
  --data-urlencode "razorpay_order_id=$(jq -r .razorpay_order_id <<<"$r")" \
  --data-urlencode "razorpay_payment_id=$(jq -r .razorpay_payment_id <<<"$r")" \
  --data-urlencode "razorpay_signature=$(jq -r .razorpay_signature <<<"$r")")"
settle
n="$(fulfilments "$big")"
verdict F8 "$([[ $n -gt 0 ]] && echo yes || echo no)" "genuine ₹10 callback posted for a ₹10,000 order → HTTP $code, big order fulfilments $n"
