#!/usr/bin/env bash
set -euo pipefail

project_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_directory"

: "${MANAGER_PASSWORD:?Set MANAGER_PASSWORD to the configured demo manager password.}"

export BASE_URL="${BASE_URL:-http://host.docker.internal:${TARBANK_APP_PORT:-8080}}"
export MANAGER_USERNAME="${MANAGER_USERNAME:-manager}"
export SEED_USERS="${SEED_USERS:-50}"

mkdir -p load-test/results
summary_file="$project_directory/load-test/results/latest.json"
: > "$summary_file"
chmod 0666 "$summary_file"
load_status=0
docker run --rm \
    --add-host=host.docker.internal:host-gateway \
    --env BASE_URL \
    --env MANAGER_USERNAME \
    --env MANAGER_PASSWORD \
    --env SEED_USERS \
    --volume "$project_directory:/work" \
    --workdir /work \
    grafana/k6:2.3.0 run \
    --summary-trend-stats='avg,med,p(95),p(99),max' \
    --summary-export=/work/load-test/results/latest.json \
    /work/load-test/k6.js || load_status=$?

docker run --rm \
    --volume "$project_directory:/work" \
    --workdir /work/load-test \
    grafana/k6:2.3.0 run \
    --quiet \
    /work/load-test/verify-summary.js

echo "Verified exported p50, p95, and p99 statistics for every measured endpoint group."

if ((load_status != 0)); then
    echo "The load test crossed at least one acceptance threshold." >&2
    exit "$load_status"
fi
