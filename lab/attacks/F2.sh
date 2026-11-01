#!/usr/bin/env bash
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
# F2: one event type (order.paid) skips signature verification.
flaws F2
read -r shop gw < <(order 1000000)
code="$(send_webhook "$(payment_event order.paid "$gw" 1000000)" -H "X-Razorpay-Event-Id: evt_forged$RANDOM")"
settle
n="$(fulfilments "$shop")"
verdict F2 "$([[ $n -gt 0 ]] && echo yes || echo no)" "unsigned order.paid → HTTP $code, fulfilments $n"
