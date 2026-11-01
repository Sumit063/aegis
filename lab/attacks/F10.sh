#!/usr/bin/env bash
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
# F10: an old genuine webhook is replayed after the shop has forgotten its event ID.
# The lab shop remembers IDs for 3 s, standing in for a real shop's days.
flaws F10
read -r shop gw < <(order 50000)
event="$(pay "{\"key_id\":\"$KEY_ID\",\"order_id\":\"$gw\"}" | jq -r .event_id)"
captured="$(curl -fsS "$GATEWAY/test/events/$event")"
sleep 4
code="$(send_webhook "$(jq -r .body <<<"$captured")" \
  -H "X-Razorpay-Signature: $(jq -r '.headers["X-Razorpay-Signature"]' <<<"$captured")" \
  -H "X-Razorpay-Event-Id: $event")"
settle
n="$(fulfilments "$shop")"
verdict F10 "$([[ $n -gt 1 ]] && echo yes || echo no)" "captured webhook replayed later → HTTP $code, fulfilments $n"
