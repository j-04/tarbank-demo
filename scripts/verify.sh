#!/usr/bin/env bash
set -euo pipefail

project_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_directory"

./gradlew --no-daemon clean check
docker compose config --quiet
./scripts/verify-redis-persistence.sh
git diff --check

echo "Verification passed: tests, migrations, configuration binding, Compose persistence, and diff checks are clean."
