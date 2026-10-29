#!/bin/bash
# Creates one database per service.
#
# Mirrors the production model where each service owns its schema entirely and
# never shares a database with a neighbour. That isolation is what allows each
# schema to be migrated independently without lock-step deploys.
set -euo pipefail

if [ -z "${POSTGRES_MULTIPLE_DATABASES:-}" ]; then
  echo "POSTGRES_MULTIPLE_DATABASES not set; skipping"
  exit 0
fi

for db in $(echo "$POSTGRES_MULTIPLE_DATABASES" | tr ',' ' '); do
  echo "Creating database '$db'"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
      SELECT 'CREATE DATABASE "$db"'
      WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec
EOSQL
done

echo "Databases ready:"
psql --username "$POSTGRES_USER" --tuples-only --no-align \
  --command "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname;"
