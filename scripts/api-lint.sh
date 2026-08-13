#!/usr/bin/env bash
# api-lint: regenerate the frontend OpenAPI schema and fail if the
# committed `frontend/src/api/generated/schema.d.ts` is not
# byte-identical to the freshly generated one. This is the gate the
# CI `api-lint` job runs.
#
# Requirements:
#   - Backend running and exposing /v3/api-docs on $API_BASE_URL
#     (default: http://localhost:8080).
#   - frontend/node_modules installed.
#
# Usage:
#   ./scripts/api-lint.sh                # check only (exits 1 on diff)
#   ./scripts/api-lint.sh --update       # overwrite the committed file
set -euo pipefail

API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
FRONTEND_DIR="${FRONTEND_DIR:-$(cd "$(dirname "$0")/.." && pwd)/frontend}"
SCHEMA_FILE="${FRONTEND_DIR}/src/api/generated/schema.d.ts"

UPDATE=0
for arg in "$@"; do
  case "$arg" in
    --update) UPDATE=1 ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
  echo "Installing frontend dependencies..."
  (cd "$FRONTEND_DIR" && npm ci)
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "Fetching /v3/api-docs from ${API_BASE_URL}..."
curl -fsS "${API_BASE_URL}/v3/api-docs" -o "${TMP_DIR}/openapi.json"

echo "Generating schema.d.ts..."
(cd "$FRONTEND_DIR" && npx openapi-typescript "${TMP_DIR}/openapi.json" -o "${TMP_DIR}/schema.d.ts") >/dev/null

if [[ "$UPDATE" == "1" ]]; then
  cp "${TMP_DIR}/schema.d.ts" "$SCHEMA_FILE"
  echo "Updated ${SCHEMA_FILE}."
  exit 0
fi

if ! diff -u "$SCHEMA_FILE" "${TMP_DIR}/schema.d.ts" >/dev/null; then
  echo "schema.d.ts is out of date. Run: $0 --update" >&2
  diff -u "$SCHEMA_FILE" "${TMP_DIR}/schema.d.ts" | head -40 >&2
  exit 1
fi

echo "schema.d.ts is in sync with the live API."
