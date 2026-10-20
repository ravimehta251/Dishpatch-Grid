#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ -f .env ] && [ "${1:-}" != "--force" ]; then
  echo '.env already exists; keeping existing secrets.'
  exit 0
fi
command -v openssl >/dev/null 2>&1 || { echo 'openssl is required.' >&2; exit 1; }
umask 077
{
  printf 'DB_USERNAME=dispatchgrid\n'
  printf 'DB_PASSWORD=%s\n' "$(openssl rand -base64 32)"
  printf 'JWT_SECRET=%s\n' "$(openssl rand -base64 48 | tr -d '\n')"
  printf 'ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)"
  printf 'MONITORING_TOKEN=%s\n' "$(openssl rand -base64 32)"
  printf 'GRAFANA_ADMIN_PASSWORD=%s\n' "$(openssl rand -base64 32)"
  printf 'RECEIVER_CONTROL_TOKEN=%s\n' "$(openssl rand -base64 32)"
  printf 'API_PORT=8080\nDASHBOARD_PORT=3000\nGRAFANA_PORT=3001\nPROMETHEUS_PORT=9090\nRECEIVER_PORT=8090\nWORKER_CONCURRENCY=8\n'
} > .env
echo 'Created .env with independent cryptographically random development secrets. Keep this file private.'
