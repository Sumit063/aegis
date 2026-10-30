#!/usr/bin/env bash
# Deploys one image tag to this host and rolls back automatically if it does not become healthy.
#   deploy/deploy.sh <image-tag>
# Runtime state lives in $AEGIS_DEPLOY_DIR (default ~/aegis/deploy): compose.yaml and .env.
set -euo pipefail

TAG="${1:?usage: deploy.sh <image-tag>}"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="${AEGIS_DEPLOY_DIR:-$HOME/aegis/deploy}"
HEALTH_URL="${AEGIS_HEALTH_URL:-http://127.0.0.1:8081/actuator/health/readiness}"

mkdir -p "$DEPLOY_DIR"
cp "$SRC_DIR/compose.yaml" "$DEPLOY_DIR/compose.yaml"
cd "$DEPLOY_DIR"

# First run on a host: create secrets locally. They never leave this machine.
if [[ ! -f .env ]]; then
  umask 077
  sed "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$(openssl rand -hex 24)/" "$SRC_DIR/.env.example" > .env
  echo "✓ created $DEPLOY_DIR/.env with a generated database password"
fi

set_tag() {
  if grep -q '^AEGIS_TAG=' .env; then
    sed -i.bak "s/^AEGIS_TAG=.*/AEGIS_TAG=$1/" .env && rm -f .env.bak
  else
    echo "AEGIS_TAG=$1" >> .env
  fi
}

wait_healthy() {
  for _ in $(seq 1 60); do
    if curl -fsS "$HEALTH_URL" 2>/dev/null | grep -q '"UP"'; then return 0; fi
    sleep 3
  done
  return 1
}

previous="$(grep '^AEGIS_TAG=' .env | cut -d= -f2- || true)"
echo "▶ deploying $TAG (previous: ${previous:-none})"
set_tag "$TAG"
docker compose pull aegis
docker compose up -d --remove-orphans

if wait_healthy; then
  echo "✓ $TAG is healthy"
  docker image prune -f --filter "until=168h" >/dev/null
  exit 0
fi

echo "✗ $TAG did not become healthy; recent logs:" >&2
docker compose logs --tail=80 aegis >&2 || true
if [[ -n "$previous" && "$previous" != "$TAG" ]]; then
  echo "↺ rolling back to $previous" >&2
  set_tag "$previous"
  docker compose up -d aegis
  wait_healthy && echo "✓ rollback to $previous is healthy" >&2
fi
exit 1
