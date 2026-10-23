#!/usr/bin/env bash
set -euo pipefail

for variable in CONTAINER_IMAGE CONTAINER_NAME DAST_DB_HOST DAST_DB_PORT \
  DAST_DB_USERNAME DAST_DB_PASSWORD GITHUB_WORKSPACE; do
  if [[ -z "${!variable:-}" ]]; then
    echo "::error::Missing required DAST variable: ${variable}"
    exit 1
  fi
done

export PGPASSWORD="${DAST_DB_PASSWORD}"
psql -h "${DAST_DB_HOST}" -p "${DAST_DB_PORT}" -U "${DAST_DB_USERNAME}" -d postgres \
  -c "CREATE DATABASE audit;"
psql -h "${DAST_DB_HOST}" -p "${DAST_DB_PORT}" -U "${DAST_DB_USERNAME}" -d audit \
  -f "${GITHUB_WORKSPACE}/dev-tools/db/init-roles.sql"

db_url="jdbc:postgresql://${DAST_DB_HOST}:${DAST_DB_PORT}/audit"

docker run --rm --network host \
  -e AUDIT_ROLE=provisioner \
  -e AUDIT_DB_URL="${db_url}" \
  -e AUDIT_DB_MIGRATOR_PASSWORD=migrator \
  -e AUDIT_PROVISIONER_TENANTS=sandbox,red-logistica \
  -e OTLP_METRICS_ENABLED=false \
  -e OTLP_LOGS_ENABLED=false \
  "${CONTAINER_IMAGE}"

docker run -d --name "${CONTAINER_NAME}" --network host \
  -e AUDIT_ROLE=all \
  -e AUDIT_DB_URL="${db_url}" \
  -e AUDIT_DB_WRITER_PASSWORD=writer \
  -e AUDIT_DB_READER_PASSWORD=reader \
  -e AUDIT_DB_ARCHIVER_PASSWORD=archiver \
  -e AUDIT_SECURITY_JWKS_URI=http://127.0.0.1:9/jwks \
  -e AUDIT_QUERY_CURSOR_SECRET=dast-cursor-secret-not-for-production-0001 \
  -e AUDIT_ARCHIVE_STORAGE=filesystem \
  -e AUDIT_ARCHIVE_FILESYSTEMROOT=/tmp/cold-storage \
  -e OTLP_METRICS_ENABLED=false \
  -e OTLP_LOGS_ENABLED=false \
  -e TRACING_SAMPLING_PROBABILITY=0 \
  "${CONTAINER_IMAGE}"