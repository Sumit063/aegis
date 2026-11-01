#!/usr/bin/env bash
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
# F5: no deduplication. One genuine payment, its webhook delivered five times.
flaws F5
read -r shop gw < <(order 50000)
event="$(pay "{\"key_id\":\"$KEY_ID\",\"order_id\":\"$gw\"}" | jq -r .event_id)"
for _ in 1 2 3 4; do curl -fsS -X POST "$GATEWAY/test/events/$event/redeliver" >/dev/null; done
settle
n="$(fulfilments "$shop")"
verdict F5 "$([[ $n -gt 1 ]] && echo yes || echo no)" "genuine webhook delivered 5× → fulfilments $n"
