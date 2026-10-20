#!/usr/bin/env bash
# LOCAL DEVELOPMENT ONLY. Prints a valid IssuanceCompleted event (catalogue 2.2.0) with a fresh
# event_id and occurred_at = now, for trying the API by hand (docs/local-testing.md).
#
#   dev-tools/sample-event.sh <tenant> [correlation_id] [holder_identifier]
#   ORGANIZATION=VATES-A11111111 dev-tools/sample-event.sh red-logistica   # another organization
#
# Works in Git Bash, Linux and macOS (only od, awk and date).
set -euo pipefail

tenant="${1:?usage: sample-event.sh <tenant> [correlation_id] [holder_identifier]}"
correlation="${2:-corr-$(date +%s)}"
holder="${3:-12345678Z}"
organization="${ORGANIZATION:-VATES-B00000000}"
event_id="$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n' \
  | awk '{print substr($0,1,8)"-"substr($0,9,4)"-4"substr($0,14,3)"-a"substr($0,18,3)"-"substr($0,21,12)}')"
occurred_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

cat <<EOF
{
  "event_id": "${event_id}",
  "event_type": "IssuanceCompleted",
  "event_version": "2.2.0",
  "occurred_at": "${occurred_at}",
  "tenant": "${tenant}",
  "organization": "${organization}",
  "source": "issuer",
  "actor": { "kind": "System", "id": "issuer" },
  "action": "Issue",
  "resource": "issuance:${correlation}",
  "result": "Success",
  "correlation_id": "${correlation}",
  "details": {
    "provider_legal_name": "Red Logística S.L.",
    "provider_registration_id": "VATES-B00000000",
    "attestation_type": "employee.sd.1",
    "delivery_method": "email",
    "holder": { "identifier": "${holder}", "name": "María García López", "email": "maria.garcia@example.org" },
    "issued_attributes": ["given_name", "family_name"],
    "signing_certificate": "sha256:9f2c",
    "signature_algorithm": "ES256",
    "attestation_hash": "sha256:41ab",
    "wallet_interaction": { "protocol": "oid4vci", "format": "dc+sd-jwt" },
    "accepted_by_holder": true
  }
}
EOF
