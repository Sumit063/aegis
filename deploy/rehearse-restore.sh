#!/usr/bin/env bash
# Restores the newest backup of one environment into a throwaway copy and proves it is usable: the
# dump restores, Aegis starts on it (schema validated by Flyway) and every sampled evidence value
# opens with the environment's keys. The copy is removed afterwards, whatever the result.
#   deploy/rehearse-restore.sh <dev|staging|prod>   on the host, as the deploying user (workflow
#   "Restore rehearsal": weekly and on demand)
# The copy runs only the admin role: it receives nothing, calls no provider and delivers nothing to
# applications, so restored messages that were due cannot be sent twice.
set -euo pipefail

ENV_NAME="${1:?usage: rehearse-restore.sh <dev|staging|prod>}"
cd "${AEGIS_DEPLOY_ROOT:-$HOME/aegis}/$ENV_NAME"
PROJECT="aegis-$ENV_NAME-restore"
export AEGIS_ROLES=admin AEGIS_HTTP_PORT=0 AEGIS_MANAGEMENT_PORT=0
c() { docker compose --progress quiet --project-name "$PROJECT" "$@"; }
trap 'c down -v --remove-orphans >/dev/null 2>&1' EXIT
sql() { c exec -T postgres psql -U aegis -d aegis -tAc "$1"; }
live() { docker exec "aegis-$ENV_NAME-postgres-1" psql -U aegis -d aegis -tAc "$1"; }
counts="SELECT (SELECT count(*) FROM validations) || ' validations, ' || (SELECT count(*) FROM payments) || ' payments'"

dump="$(c run --rm --no-deps -T --entrypoint sh backup -c 'ls -1t /backups/aegis-*.dump 2>/dev/null | head -1')"
[[ -n "$dump" ]] || { echo "✗ no backup in $PWD/backups"; exit 1; }
echo "▶ rehearsing restore of $ENV_NAME from $dump"

c up -d --wait postgres >/dev/null
c run --rm --no-deps -T --entrypoint pg_restore backup --no-owner -d aegis "$dump"
echo "  ✓ restored: $(sql "$counts") (live now: $(live "$counts"))"

c up -d kafka aegis >/dev/null 2>&1
http="http://$(c port aegis 8080)"
management="http://$(c port aegis 8081)"
for _ in $(seq 1 60); do
  curl -fsS "$management/actuator/health/readiness" 2>/dev/null | grep -q '"UP"' && break
  sleep 3
done
curl -fsS "$management/actuator/health/readiness" 2>/dev/null | grep -q '"UP"' \
  || { echo "✗ Aegis did not start on the restored database"; c logs --tail=40 aegis; exit 1; }
echo "  ✓ Aegis is ready on the restored database (schema $(sql "SELECT max(version) FROM flyway_schema_history WHERE success"))"

admin_key="$(grep '^AEGIS_ADMIN_API_KEY=' .env | cut -d= -f2-)"
report="$(curl -fsS -H "Authorization: Bearer $admin_key" "$http/admin/evidence?sample=1000")"
echo "  evidence: $report"
unreadable="$(sed -n 's/.*"unreadable":\([0-9]*\).*/\1/p' <<<"$report")"
[[ "$unreadable" == 0 ]] || { echo "✗ $ENV_NAME: restored evidence cannot be opened with its keys"; exit 1; }
echo "✓ $ENV_NAME restores from $dump"
