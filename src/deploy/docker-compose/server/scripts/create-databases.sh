#!/bin/bash
# Called by the PostgreSQL entrypoint only for an empty data directory.
set +x
set -euo pipefail

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${IAM_DB_PASSWORD:?IAM_DB_PASSWORD is required}"
: "${MOJ_DB_PASSWORD:?MOJ_DB_PASSWORD is required}"

# Read secrets inside psql, not through command-line arguments. Quoted psql
# variables escape SQL literals without delimiter parsing. Hide statement errors,
# which may contain the expanded password literal, from initialization logs.
if ! psql -X --quiet --set=ON_ERROR_STOP=1 --set=ECHO=none \
    --username "$POSTGRES_USER" --dbname postgres 2>/dev/null <<'SQL'
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
\getenv iam_password IAM_DB_PASSWORD
\getenv moj_password MOJ_DB_PASSWORD
CREATE USER iam WITH PASSWORD :'iam_password';
CREATE DATABASE iam OWNER iam;
CREATE USER moj WITH PASSWORD :'moj_password';
CREATE DATABASE moj OWNER moj;
SQL
then
    echo "Database initialization failed; check database state and required environment values." >&2
    exit 1
fi
echo "IAM and MoJ databases created."
