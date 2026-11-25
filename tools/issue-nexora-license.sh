#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <private-ed25519.pem> <server-id-uuid> <license-id>" >&2
  exit 2
fi

PRIVATE_KEY="$1"
SERVER_ID="$2"
LICENSE_ID="$3"

if [[ ! -f "$PRIVATE_KEY" ]]; then
  echo "Private key file not found: $PRIVATE_KEY" >&2
  exit 2
fi
if [[ ! "$SERVER_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  echo "Invalid Server ID UUID" >&2
  exit 2
fi
if [[ ! "$LICENSE_ID" =~ ^[A-Za-z0-9._-]{1,96}$ ]]; then
  echo "License ID must contain 1-96 letters, digits, dots, underscores or hyphens" >&2
  exit 2
fi
if ! openssl pkey -in "$PRIVATE_KEY" -noout >/dev/null 2>&1; then
  echo "Could not read Ed25519 private key" >&2
  exit 2
fi

# Long.MAX_VALUE is used as the signed expiry for a perpetual offline license.
EXPIRES_AT=9223372036854775807
PAYLOAD="NEXORA-LICENSE|1|$LICENSE_ID|$SERVER_ID|$EXPIRES_AT"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
printf '%s' "$PAYLOAD" > "$TMP_DIR/payload"
openssl pkeyutl -sign -rawin -inkey "$PRIVATE_KEY" -in "$TMP_DIR/payload" -out "$TMP_DIR/signature"

base64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}
PAYLOAD_B64="$(base64url < "$TMP_DIR/payload")"
SIGNATURE_B64="$(base64url < "$TMP_DIR/signature")"
printf '%s.%s\n' "$PAYLOAD_B64" "$SIGNATURE_B64"
