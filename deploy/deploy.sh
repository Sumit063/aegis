#!/usr/bin/env bash
# Deploys one image tag to one environment on this host; rolls back if it does not become healthy.
#   deploy/deploy.sh <dev|staging|prod> <image-tag>
# Each environment is an isolated compose project (aegis-<env>) with its own database, Kafka,
# ports and secrets in $AEGIS_DEPLOY_ROOT/<env>/.env.
set -euo pipefail

ENV_NAME="${1:?usage: deploy.sh <dev|staging|prod> <image-tag>}"
TAG="${2:?usage: deploy.sh <dev|staging|prod> <image-tag>}"
case "$ENV_NAME" in
  prod)    HTTP_PORT=8080; MANAGEMENT_PORT=8081; PROMETHEUS_PORT=9090; GRAFANA_PORT=3000 ;;
  staging) HTTP_PORT=8180; MANAGEMENT_PORT=8181; PROMETHEUS_PORT=9190; GRAFANA_PORT=3100 ;;
  dev)     HTTP_PORT=8280; MANAGEMENT_PORT=8281; PROMETHEUS_PORT=9290; GRAFANA_PORT=3200 ;;
  *) echo "unknown environment: $ENV_NAME (expected dev, staging or prod)" >&2; exit 2 ;;
esac

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="${AEGIS_DEPLOY_ROOT:-$HOME/aegis}/$ENV_NAME"
mkdir -p "$DEPLOY_DIR"
cp "$SRC_DIR/compose.yaml" "$SRC_DIR/compose.observability.yaml" "$DEPLOY_DIR/"
rm -rf "$DEPLOY_DIR/observability" && cp -R "$SRC_DIR/observability" "$DEPLOY_DIR/observability"
cd "$DEPLOY_DIR"

# First deploy of this environment: create its secrets locally. They never leave this machine.
if [[ ! -f .env ]]; then
  umask 077
  sed -e "s/^SPRING_PROFILES_ACTIVE=.*/SPRING_PROFILES_ACTIVE=$ENV_NAME/" \
      -e "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$(openssl rand -hex 24)/" \
      -e "s/^AEGIS_HTTP_PORT=.*/AEGIS_HTTP_PORT=$HTTP_PORT/" \
      -e "s/^AEGIS_MANAGEMENT_PORT=.*/AEGIS_MANAGEMENT_PORT=$MANAGEMENT_PORT/" \
      "$SRC_DIR/.env.example" > .env
  echo "✓ created $DEPLOY_DIR/.env (profile $ENV_NAME, ports $HTTP_PORT/$MANAGEMENT_PORT)"
fi

# Connections and applications: created once from the template, then edited on the host.
mkdir -p backups && chmod 700 backups

if [[ ! -f aegis.yaml ]]; then
  cp "$SRC_DIR/aegis.example.yaml" aegis.yaml
  echo "✓ created $DEPLOY_DIR/aegis.yaml (no connections yet)"
fi

env_value() { grep "^$1=" .env | cut -d= -f2- || true; }

# Secrets added after an environment was created are generated once, here, and never leave the host.
ensure_secret() { grep -q "^$1=" .env || { echo "$1=$2" >> .env; echo "✓ generated $1"; }; }
ensure_secret AEGIS_EVIDENCE_KEY "$(openssl rand -base64 32)"
ensure_secret AEGIS_ADMIN_API_KEY "$(openssl rand -hex 32)"
ensure_secret GRAFANA_ADMIN_PASSWORD "$(openssl rand -hex 16)"
ensure_secret PROMETHEUS_PORT "$PROMETHEUS_PORT"
ensure_secret GRAFANA_PORT "$GRAFANA_PORT"
set_tag() {
  if grep -q '^AEGIS_TAG=' .env; then
    sed -i.bak "s/^AEGIS_TAG=.*/AEGIS_TAG=$1/" .env && rm -f .env.bak
  else
    echo "AEGIS_TAG=$1" >> .env
  fi
}

HEALTH_URL="http://127.0.0.1:$(env_value AEGIS_MANAGEMENT_PORT)/actuator/health/readiness"
wait_healthy() {
  for _ in $(seq 1 60); do
    if curl -fsS "$HEALTH_URL" 2>/dev/null | grep -q '"UP"'; then return 0; fi
    sleep 3
  done
  return 1
}

# Every server runs with metrics and alerts (compose.observability.yaml).
compose() { docker compose --project-name "aegis-$ENV_NAME" -f compose.yaml -f compose.observability.yaml "$@"; }

previous="$(env_value AEGIS_TAG)"
echo "▶ deploying $TAG to $ENV_NAME (previous: ${previous:-none})"
set_tag "$TAG"
compose pull aegis
compose up -d --remove-orphans

if wait_healthy; then
  echo "✓ $ENV_NAME is healthy on $TAG"
  docker image prune -f --filter "until=168h" >/dev/null
  exit 0
fi

echo "✗ $TAG did not become healthy in $ENV_NAME; recent logs:" >&2
compose logs --tail=80 aegis >&2 || true
if [[ -n "$previous" && "$previous" != "$TAG" ]]; then
  echo "↺ rolling back $ENV_NAME to $previous" >&2
  set_tag "$previous"
  compose up -d aegis
  wait_healthy && echo "✓ rollback to $previous is healthy" >&2
fi
exit 1
