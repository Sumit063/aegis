#!/usr/bin/env bash
# Checks one running environment and prints a pass/fail report; exits 1 if anything failed.
#   deploy/check.sh <local|dev|staging|prod> [expected-version]
# Runs where the environment lives: on a laptop for `local` (service started with spring-boot:run),
# on the host for servers. CD runs it after every deploy; scripts/check.sh runs it over SSH.
set -uo pipefail

ENV_NAME="${1:?usage: check.sh <local|dev|staging|prod> [expected-version]}"
EXPECTED="${2:-}"
PROJECT="aegis-$ENV_NAME"
failed=0
pass() { printf '  ✓ %s\n' "$1"; }
fail() { printf '  ✗ %s\n' "$1"; failed=1; }

echo "Aegis · $ENV_NAME"

# 1. Containers: running, and healthy where they have a health check. Locally the service runs
#    from the IDE, so only its dependencies are containers.
services=(postgres kafka)
[[ "$ENV_NAME" != local ]] && services+=(redis aegis backup prometheus grafana)
for s in "${services[@]}"; do
  state="$(docker inspect -f '{{.State.Status}}{{if .State.Health}} {{.State.Health.Status}}{{end}}' "$PROJECT-$s-1" 2>/dev/null)"
  case "$state" in
    running | "running healthy") pass "$s: $state" ;;
    *) fail "$s: ${state:-not found}" ;;
  esac
done

# Servers keep a daily database dump (RUNBOOK §5); the newest must be under 26 hours old.
if [[ "$ENV_NAME" != local ]]; then
  if docker exec "$PROJECT-backup-1" find /backups -name 'aegis-*.dump' -mmin -1560 2>/dev/null | grep -q .; then
    pass "backup: taken in the last 26 h"
  else
    fail "backup: none in the last 26 h"
  fi
fi

# 2. The service, through its management port (never exposed beyond 127.0.0.1).
if [[ "$ENV_NAME" == local ]]; then
  base="http://127.0.0.1:8081"
else
  base="http://$(docker port "$PROJECT-aegis-1" 8081 2>/dev/null | head -1)"
fi
get() { curl -fsS --max-time 5 "$base/actuator/$1" 2>/dev/null; }
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

[[ "$(get health/liveness)" == *'"UP"'* ]] && pass "alive" || fail "not alive ($base)"
[[ "$(get health/readiness)" == *'"UP"'* ]] && pass "ready (database reachable)" || fail "not ready ($base)"

info="$(get info)"
environment="$(field environment <<<"$info")"
version="$(field version <<<"$info")"
[[ "$environment" == "$ENV_NAME" ]] && pass "profile: $environment" || fail "profile: ${environment:-unknown}, expected $ENV_NAME"
if [[ -z "$EXPECTED" ]]; then
  [[ -n "$version" ]] && pass "version: $version" || fail "version: unknown"
elif [[ "$version" == "$EXPECTED" ]]; then
  pass "version: $version (expected)"
else
  fail "version: ${version:-unknown}, expected $EXPECTED"
fi
roles="$(field roles <<<"$info")"
[[ -n "$roles" ]] && pass "roles: $roles"

# 3. Metrics: Prometheus scrapes Aegis and has loaded the alert rules (servers only).
if [[ "$ENV_NAME" != local ]]; then
  prom="http://$(docker port "$PROJECT-prometheus-1" 9090 2>/dev/null | head -1)"
  up="$(curl -fsS --max-time 5 "$prom/api/v1/query?query=up%7Bjob%3D%22aegis%22%7D" 2>/dev/null | sed -n 's/.*"value":\[[^,]*,"\([0-9]\)".*/\1/p')"
  [[ "$up" == 1 ]] && pass "metrics: Prometheus scrapes Aegis" || fail "metrics: Prometheus does not scrape Aegis (${up:-no answer})"
  rules="$(curl -fsS --max-time 5 "$prom/api/v1/rules" 2>/dev/null | grep -o '"type":"alerting"' | wc -l | tr -d ' ')"
  ((rules > 0)) && pass "alerts: $rules rules loaded" || fail "alerts: no rules loaded"
fi

if ((failed)); then echo "✗ $ENV_NAME has problems"; exit 1; fi
echo "✓ $ENV_NAME is healthy"
