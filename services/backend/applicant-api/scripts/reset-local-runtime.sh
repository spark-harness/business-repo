#!/usr/bin/env bash
set -euo pipefail

APP_DB_URL="${APP_DB_URL:-postgresql://forest:forest_dev_password@localhost:5432/app}"
REDIS_URL="${REDIS_URL:-redis://:forest_dev_password@localhost:6379}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-applicant-api-postgres-1}"
REDIS_CONTAINER="${REDIS_CONTAINER:-applicant-api-redis-1}"

require_local_reset_guard() {
  if [ "${ALLOW_LOCAL_RUNTIME_RESET:-}" != "true" ]; then
    echo "refusing destructive reset: set ALLOW_LOCAL_RUNTIME_RESET=true" >&2
    exit 1
  fi

  case "$APP_DB_URL" in
    postgresql://forest:forest_dev_password@localhost:5432/app|postgresql://forest:forest_dev_password@127.0.0.1:5432/app) ;;
    *)
      echo "refusing destructive reset for non-local APP_DB_URL: $APP_DB_URL" >&2
      exit 1
      ;;
  esac

  case "$REDIS_URL" in
    redis://:forest_dev_password@localhost:6379|redis://:forest_dev_password@127.0.0.1:6379) ;;
    *)
      echo "refusing destructive reset for non-local REDIS_URL: $REDIS_URL" >&2
      exit 1
      ;;
  esac
}

run_psql() {
  local sql="$1"
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD=forest_dev_password psql "$APP_DB_URL" -v ON_ERROR_STOP=1 -c "$sql"
  else
    docker exec -i "$POSTGRES_CONTAINER" psql -U forest -d app -v ON_ERROR_STOP=1 -c "$sql"
  fi
}

run_redis() {
  if command -v redis-cli >/dev/null 2>&1; then
    redis-cli -u "$REDIS_URL" "$@"
  else
    docker exec -i "$REDIS_CONTAINER" redis-cli -a forest_dev_password "$@"
  fi
}

require_local_reset_guard

run_psql "truncate table applicants;"
keys="$(run_redis --scan --pattern 'applicant-api:*' || true)"
if [ -n "$keys" ]; then
  while IFS= read -r key; do
    [ -n "$key" ] && run_redis DEL "$key" >/dev/null
  done <<< "$keys"
fi

echo "local applicant-api runtime reset"
