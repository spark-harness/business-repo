#!/usr/bin/env bash
set -euo pipefail

APP_DB_URL="${APP_DB_URL:-postgresql://forest:forest_dev_password@localhost:5432/app}"
REDIS_URL="${REDIS_URL:-redis://:forest_dev_password@localhost:6379}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-applicant-api-postgres-1}"
REDIS_CONTAINER="${REDIS_CONTAINER:-applicant-api-redis-1}"
HTTP_BASE="${HTTP_BASE:-http://localhost:8080}"
GRPC_ADDR="${GRPC_ADDR:-localhost:9090}"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing required command: $1" >&2
    exit 1
  }
}

run_psql() {
  local sql="$1"
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD=forest_dev_password psql "$APP_DB_URL" -At -v ON_ERROR_STOP=1 -c "$sql"
  else
    docker exec -i "$POSTGRES_CONTAINER" psql -U forest -d app -At -v ON_ERROR_STOP=1 -c "$sql"
  fi
}

run_redis() {
  if command -v redis-cli >/dev/null 2>&1; then
    redis-cli -u "$REDIS_URL" "$@"
  else
    docker exec -i "$REDIS_CONTAINER" redis-cli -a forest_dev_password "$@"
  fi
}

json_field() {
  local name="$1"
  sed -n "s/.*\"$name\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p"
}

need curl
need grpcurl
need docker

curl --fail --silent "$HTTP_BASE/ready" >/tmp/applicant-api-ready.json
send_response="$(grpcurl -plaintext -d '{"country_code":"+852","phone":"91234567","idempotency_key":"local-smoke-send"}' \
  "$GRPC_ADDR" vesta.lendora.applicant.v1.ApplicantAuthService/SendOtp)"
challenge_id="$(printf '%s' "$send_response" | json_field challengeId)"
if [ -z "$challenge_id" ]; then
  echo "SendOtp did not return challengeId" >&2
  printf '%s\n' "$send_response" >&2
  exit 1
fi

verify_response="$(grpcurl -plaintext -d "{\"challenge_id\":\"$challenge_id\",\"code\":\"123456\",\"idempotency_key\":\"local-smoke-verify\"}" \
  "$GRPC_ADDR" vesta.lendora.applicant.v1.ApplicantAuthService/VerifyOtp)"
applicant_id="$(printf '%s' "$verify_response" | json_field applicantId)"
if [ -z "$applicant_id" ]; then
  echo "VerifyOtp did not return applicantId" >&2
  printf '%s\n' "$verify_response" >&2
  exit 1
fi

applicant_count="$(run_psql "select count(*) from applicants where applicant_id = '$applicant_id';")"
if [ "$applicant_count" != "1" ]; then
  echo "PostgreSQL applicant row not found for $applicant_id" >&2
  exit 1
fi

redis_key_count="$(run_redis --scan --pattern 'applicant-api:*' | wc -l | tr -d ' ')"
if [ "$redis_key_count" = "0" ]; then
  echo "Redis applicant-api runtime keys not found" >&2
  exit 1
fi

echo "local smoke passed: applicant_id=$applicant_id redis_keys=$redis_key_count"
