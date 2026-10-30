#!/usr/bin/env bash
# Installs the deploy runner on the production host, as the unprivileged deploy user.
#   RUNNER_TOKEN=<registration token> deploy/runner/install.sh <owner/repo>
# Get a token with: gh api -X POST repos/<owner/repo>/actions/runners/registration-token --jq .token
set -euo pipefail

REPO="${1:?usage: install.sh <owner/repo>}"
: "${RUNNER_TOKEN:?set RUNNER_TOKEN}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="$HOME/aegis/deploy"
DOCKER_GID="$(getent group docker | cut -d: -f3)"

mkdir -p "$DEPLOY_DIR"
docker build -t aegis-runner:local "$HERE"
docker rm -f aegis-runner >/dev/null 2>&1 || true
docker volume create aegis-runner-state >/dev/null

# Host networking so the health check reaches 127.0.0.1:8081; the deploy dir is mounted at the
# same path so compose state survives across jobs.
docker run -d --name aegis-runner --restart unless-stopped \
  --network host \
  --group-add "$DOCKER_GID" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v aegis-runner-state:/home/runner \
  -v "$DEPLOY_DIR:$DEPLOY_DIR" \
  -e AEGIS_DEPLOY_DIR="$DEPLOY_DIR" \
  -e RUNNER_URL="https://github.com/$REPO" \
  -e RUNNER_TOKEN="$RUNNER_TOKEN" \
  aegis-runner:local >/dev/null

echo "✓ runner container started; check: docker logs -f aegis-runner"
