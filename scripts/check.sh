#!/usr/bin/env bash
# Validates an environment from your machine, e.g. after a merge.
#   scripts/check.sh            local: containers + the service started with spring-boot:run
#   scripts/check.sh staging    the server: what runs must be the tip of origin/staging
# Servers are reached over SSH: $AEGIS_HOST (default: the `aegis-server` alias).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

ENV_NAME="${1:-local}"
[[ "$ENV_NAME" == local ]] && exec deploy/check.sh local

branch="$ENV_NAME"
[[ "$ENV_NAME" == prod ]] && branch=master
git fetch -q origin "$branch"
expected="$(git rev-parse "origin/$branch")"

if command -v gh >/dev/null; then
  echo "Last deploy: $(gh run list --workflow CD -L 1 --json status,conclusion,createdAt,url \
    --jq '.[0] | "\(.status) \(.conclusion // "") · \(.createdAt) · \(.url)"' 2>/dev/null)"
fi
ssh "${AEGIS_HOST:-aegis-server}" bash -s -- "$ENV_NAME" "$expected" <deploy/check.sh
