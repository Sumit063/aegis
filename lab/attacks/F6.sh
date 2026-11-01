#!/usr/bin/env bash
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
# F6: a late payment.failed (attempt 1) cancels an order already paid by attempt 2.
flaws F6
read -r shop gw < <(order 50000)
failed="$(pay "{\"key_id\":\"$KEY_ID\",\"order_id\":\"$gw\",\"outcome\":\"failed\",\"webhook\":false}" | jq -r .event_id)"
pay "{\"key_id\":\"$KEY_ID\",\"order_id\":\"$gw\"}" >/dev/null
curl -fsS -X POST "$GATEWAY/test/events/$failed/redeliver" >/dev/null
s="$(status "$shop")"
verdict F6 "$([[ $s == CANCELLED ]] && echo yes || echo no)" "late payment.failed after success → order $s"
