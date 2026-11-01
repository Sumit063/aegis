#!/usr/bin/env bash
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
# F1: the webhook secret is empty, and an HMAC made with an empty key is accepted.
flaws F1
read -r shop gw < <(order 1000000)
body="$(payment_event payment.captured "$gw" 1000000)"
code="$(send_webhook "$body" -H "X-Razorpay-Signature: $(printf %s "$body" | hmac "")" -H "X-Razorpay-Event-Id: evt_forged$RANDOM")"
settle
n="$(fulfilments "$shop")"
verdict F1 "$([[ $n -gt 0 ]] && echo yes || echo no)" "forged payment.captured signed with an empty key → HTTP $code, fulfilments $n"
