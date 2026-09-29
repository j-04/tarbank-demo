#!/usr/bin/env bash
set -euo pipefail

project_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project_name="tarbank-redis-persistence-${RANDOM}-$$"
sentinel="jwt:invalidated:persistence-regression"

export TARBANK_DATABASE_NAME=unused
export TARBANK_DATABASE_USERNAME=unused
export TARBANK_DATABASE_PASSWORD=unused-database-password
export TARBANK_REDIS_PASSWORD=redis-persistence-test-password

cleanup() {
    docker compose --project-directory "$project_directory" --project-name "$project_name" down \
        --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker compose --project-directory "$project_directory" --project-name "$project_name" up \
    --detach --wait redis >/dev/null
docker compose --project-directory "$project_directory" --project-name "$project_name" exec -T redis \
    redis-cli --no-auth-warning -a "$TARBANK_REDIS_PASSWORD" set "$sentinel" 1 EX 3600 >/dev/null

docker compose --project-directory "$project_directory" --project-name "$project_name" down >/dev/null
docker compose --project-directory "$project_directory" --project-name "$project_name" up \
    --detach --wait redis >/dev/null

persisted="$(docker compose --project-directory "$project_directory" --project-name "$project_name" exec -T redis \
    redis-cli --no-auth-warning -a "$TARBANK_REDIS_PASSWORD" exists "$sentinel" | tr -d '\r')"
if [[ "$persisted" != "1" ]]; then
    echo "Redis did not preserve the logout-invalidation marker across Compose recreation." >&2
    exit 1
fi

echo "Redis persistence regression passed."
