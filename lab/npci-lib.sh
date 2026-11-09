#!/usr/bin/env bash
# NPCI lab helpers (sourced by lab/npci.sh and lab/failures.sh): the shop's bill desk calling the
# lab's NPCI switch through Aegis (docs/design/NPCI.md), and what the desk, the switch and Aegis saw.
AEGIS="${AEGIS:-http://127.0.0.1:8080}"
SWITCH="${SWITCH:-http://127.0.0.1:8093}"
DESK="${DESK:-http://127.0.0.1:8092}"
DESK_KEY="lab-desk-api-key-for-aegis-not-real-0000001"
ADMIN_KEY="local-only-admin-key-000000000000"

id() { echo "$1$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"; }
ts() { date -u +%Y-%m-%dT%H:%M:%SZ | sed 's/Z$/+00:00/'; }

# Sends the desk's XML request through Aegis; prints "<HTTP status> <X-Aegis-Verdict>".
gw() { # connection path xml
  local out
  out="$(curl -s -o /dev/null -D - -w 'HTTP_CODE:%{http_code}' --max-time 15 -X POST "$AEGIS/gw/$1/$2" \
    -H "Authorization: Bearer $DESK_KEY" -H 'Content-Type: application/xml' --data-binary "$3")" || true
  echo "${out##*HTTP_CODE:} $(printf '%s' "$out" | grep -i '^x-aegis-verdict:' | cut -d' ' -f2- | tr -d '\r')"
}

plan() { # script [duplicates] [wrongAmount]
  curl -fsS -X PUT "$SWITCH/test/next" -H 'Content-Type: application/json' \
    -d "{\"script\":\"$1\",\"duplicates\":${2:-0},\"wrongAmount\":${3:-false}}"
}

received() { curl -fsS "$SWITCH/test/received" | jq -r ".$1 // 0"; }
attack() { curl -fsS -X POST "$SWITCH/test/attack?kind=$1&txn=$2" | jq -r .status; }
told() { curl -fsS "$DESK/npci/payments/$1" | jq -c .; }
verdicts() { curl -fsS -H "Authorization: Bearer $ADMIN_KEY" "$AEGIS/admin/payments?ref=$1" | jq -c '[.[].messages[] | .verdict]'; }

bbps_fetch() { # refId → "<status> <verdict>" (the switch answers ₹840)
  gw bbps validations "<bbps:BillFetchRequest xmlns:bbps=\"http://bbps.org/schema\"><Head ver=\"1.0\" ts=\"$(ts)\" origInst=\"LABCOU\" refId=\"$1\" msgId=\"F$(id F)\"/><BillDetails><Biller id=\"ELEC00000NAT01\"/><CustomerParams><Tag name=\"ConsumerNumber\" value=\"1234567890\"/></CustomerParams></BillDetails></bbps:BillFetchRequest>"
}
bbps_bill() { # → a new refId, its bill fetched through Aegis
  local ref; ref="$(id R)"
  [[ "$(bbps_fetch "$ref")" == "200 PASSED" ]] || return 1
  echo "$ref"
}
bbps_pay() { # refId txn [amount]
  gw bbps payments "<bbps:BillPaymentRequest xmlns:bbps=\"http://bbps.org/schema\"><Head ver=\"1.0\" ts=\"$(ts)\" origInst=\"LABCOU\" refId=\"$1\" msgId=\"P$2\"/><Txn txnReferenceId=\"$2\" ts=\"$(ts)\"/><BillDetails><Biller id=\"ELEC00000NAT01\"/></BillDetails><Amount><Amt amount=\"${3:-84000}\" currency=\"356\"/></Amount><PaymentMethod paymentMode=\"UPI\"/></bbps:BillPaymentRequest>"
}
bbps_status() { # refId txn
  gw bbps status "<bbps:TxnStatusRequest xmlns:bbps=\"http://bbps.org/schema\"><Head ver=\"1.0\" ts=\"$(ts)\" origInst=\"LABCOU\" refId=\"$1\" msgId=\"S$2\"/><TxnStatusReq txnReferenceId=\"$2\"/></bbps:TxnStatusRequest>"
}
upi_validation() { # → validation id (payee confirmed for ₹499.00)
  local v; v="$(id V)"
  [[ "$(gw upi validations "<upi:ReqValAdd xmlns:upi=\"http://npci.org/upi/schema/\"><Head ver=\"2.0\" ts=\"$(ts)\" orgId=\"LABPSP\" msgId=\"M$v\"/><Txn id=\"$v\" refId=\"$(id ORDER)\" type=\"ValAdd\" ts=\"$(ts)\"/><Payer addr=\"customer@psp\"/><Payee addr=\"shop@bank\"><Amount value=\"499.00\" curr=\"INR\"/></Payee></upi:ReqValAdd>")" == "200 PASSED" ]] || return 1
  echo "$v"
}
upi_pay() { # validation txn
  gw upi payments "<upi:ReqPay xmlns:upi=\"http://npci.org/upi/schema/\"><Head ver=\"2.0\" ts=\"$(ts)\" orgId=\"LABPSP\" msgId=\"P$2\"/><Txn id=\"$2\" refId=\"$1\" type=\"PAY\" ts=\"$(ts)\"/><Payer addr=\"customer@psp\"><Amount value=\"499.00\" curr=\"INR\"/></Payer><Payee addr=\"shop@bank\"/></upi:ReqPay>"
}

