#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env
set +a
export DB_URL=jdbc:postgresql://localhost:5432/dispatchgrid KAFKA_BOOTSTRAP_SERVERS=localhost:29092 REDIS_HOST=localhost DISPATCHGRID_ALLOW_PRIVATE_DESTINATIONS=localhost COOKIE_SECURE=false
app="${1:-api}"
case "$app" in api|worker) ;; *) echo 'Choose api or worker'; exit 1;; esac
exec java -jar "backend/dispatchgrid-$app/target/dispatchgrid-$app-1.0.0-SNAPSHOT.jar"
