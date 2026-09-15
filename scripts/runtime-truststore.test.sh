#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_root="$(mktemp -d)"
trap 'rm -rf -- "$temporary_root"' EXIT
jdk_home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

JAVA_HOME="$jdk_home" \
  "$backend_root/scripts/prepare-runtime-truststore.sh" "$temporary_root/Application Support/runtime-cacerts"

runtime_store="$temporary_root/Application Support/runtime-cacerts"
[[ -f "$runtime_store" ]]
[[ "$(stat -f '%Lp' "$runtime_store")" == 600 ]]
"$jdk_home/bin/keytool" -list -keystore "$runtime_store" \
  -storepass changeit -alias cofco-cfca-ev-root >/dev/null

cp "$backend_root/ops/trust/cfca-ev-root.pem" "$temporary_root/tampered.pem"
printf '\ninvalid\n' >> "$temporary_root/tampered.pem"
if COFCO_ENTERPRISE_CFCA_CERT="$temporary_root/tampered.pem" \
  JAVA_HOME="$jdk_home" \
  "$backend_root/scripts/prepare-runtime-truststore.sh" "$temporary_root/rejected-cacerts" >/dev/null 2>&1; then
  echo "tampered CFCA certificate was accepted" >&2
  exit 1
fi

echo "[OK] application truststore preserves Java defaults and imports only the pinned CFCA root"
