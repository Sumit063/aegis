#!/usr/bin/env bash
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
# F4: requests that claim to come from localhost skip verification.
flaws F4
read -r shop gw < <(order 1000000)
code="$(send_webhook "$(payment_event payment.captured "$gw" 1000000)" -H "Host: localhost" -H "X-Razorpay-Event-Id: evt_forged$RANDOM")"
settle
n="$(fulfilments "$shop")"
verdict F4 "$([[ $n -gt 0 ]] && echo yes || echo no)" "unsigned webhook with Host: localhost → HTTP $code, fulfilments $n"
