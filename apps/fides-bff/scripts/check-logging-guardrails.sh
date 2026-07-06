#!/usr/bin/env bash
set -euo pipefail

APP_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BUSINESS_ROOT=$(cd "$APP_ROOT/../.." && pwd)
BFFKIT_ROOT="$BUSINESS_ROOT/packages/go/bffkit"
failed=0

check_absent() {
  local description=$1
  local pattern=$2
  shift 2
  if rg -n "$pattern" "$APP_ROOT" "$BFFKIT_ROOT" --glob '*.go' --glob '!**/*_test.go' "$@"; then
    echo "forbidden: $description" >&2
    failed=1
  fi
}

check_absent "Kratos v2 imports" 'github\.com/go-kratos/kratos/v2'
check_absent "direct fmt/log output" '\b(fmt\.Print|log\.Print)\w*\('

if rg -n '\bos\.(Stdout|Stderr)\b' "$APP_ROOT" "$BFFKIT_ROOT" --glob '*.go' --glob '!**/*_test.go' |
  grep -v 'cmd/fides-bff/main.go:'; then
  echo "forbidden: direct stdout/stderr outside Kratos logger bootstrap" >&2
  failed=1
fi

if rg -n 'github\.com/spark/fides-bff/internal/observability' "$APP_ROOT/internal/biz" "$APP_ROOT/internal/service" "$APP_ROOT/internal/data" --glob '*.go'; then
  echo "forbidden: application layers must not import observability implementation" >&2
  failed=1
fi

exit "$failed"
