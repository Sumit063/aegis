#!/usr/bin/env bash
# Generates the lab's NPCI keys once per checkout into lab/.env (git-ignored; compose reads it):
# the switch's key pair and Aegis's signing key pair, RSA 2048, single-line base64.
#   lab/keys.sh            (run by lab/run.sh, lab/failures.sh and lab/npci.sh)
set -euo pipefail
cd "$(dirname "$0")"
[[ -s .env ]] && grep -q '^NPCI_SWITCH_KEY=' .env && exit 0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
pair() { # name → <NAME>_KEY (PKCS#8) and <NAME>_PUBLIC (SubjectPublicKeyInfo)
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$work/$1.pem" 2>/dev/null
  echo "$2_KEY=$(openssl pkcs8 -topk8 -nocrypt -in "$work/$1.pem" -outform DER | base64 | tr -d '\n')"
  echo "$2_PUBLIC=$(openssl pkey -in "$work/$1.pem" -pubout -outform DER | base64 | tr -d '\n')"
}
umask 077
{ pair switch NPCI_SWITCH; pair aegis AEGIS_NPCI; } > .env
echo "✓ generated lab NPCI keys (lab/.env)"
