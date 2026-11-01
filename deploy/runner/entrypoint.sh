#!/usr/bin/env bash
# Registers the runner once (state kept in a volume), then runs it.
set -euo pipefail
cd /home/runner

if [[ ! -f .runner ]]; then
  : "${RUNNER_URL:?}" "${RUNNER_TOKEN:?registration token required for first start}"
  ./config.sh --unattended --replace \
    --url "$RUNNER_URL" --token "$RUNNER_TOKEN" \
    --name "${RUNNER_NAME:-aegis-host}" \
    --labels "${RUNNER_LABELS:-aegis-host}" \
    --work _work
fi

exec ./run.sh
