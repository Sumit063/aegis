#!/usr/bin/env bash
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
# F3: the shop trusts notes.shop_order and never compares the amount. A real ₹10 payment,
# made without an order, names the ₹10,000 order in its notes.
flaws F3
read -r shop _ < <(order 1000000)
result="$(pay "{\"key_id\":\"$KEY_ID\",\"amount\":1000,\"notes\":{\"shop_order\":\"$shop\"}}")"
n="$(fulfilments "$shop")"
verdict F3 "$([[ $n -gt 0 ]] && echo yes || echo no)" "genuine ₹10 payment naming a ₹10,000 order → webhook HTTP $(jq -r .webhook_status <<<"$result"), fulfilments $n"
