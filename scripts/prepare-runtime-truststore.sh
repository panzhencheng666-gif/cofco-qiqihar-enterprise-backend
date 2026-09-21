#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
certificate="${COFCO_ENTERPRISE_CFCA_CERT:-${backend_root}/ops/trust/cfca-ev-root.pem}"
output="${1:?Usage: prepare-runtime-truststore.sh OUTPUT_PATH}"
expected_fingerprint="5CC3D78E4E1D5E45547A04E6873E64F90CF9536D1CCC2EF800F355C4C5FD70FD"
expected_subject="subject=CN=CFCA EV ROOT,O=China Financial Certification Authority,C=CN"

[[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/keytool" ]] || {
  echo "JAVA_HOME must point to the application JDK" >&2
  exit 1
}
[[ -f "$certificate" ]] || { echo "Pinned CFCA certificate is missing: $certificate" >&2; exit 1; }
# Homebrew's exported JAVA_HOME can be its prefix rather than the JVM home.
# Ask that selected JVM for its own truststore location instead of guessing.
truststore_java_home="$("${JAVA_HOME}/bin/java" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p')"
[[ -n "$truststore_java_home" && -f "${truststore_java_home}/lib/security/cacerts" ]] || {
  echo "Selected JVM default cacerts is missing" >&2
  exit 1
}

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/cofco-truststore.XXXXXX")"
trap 'rm -rf -- "$temporary_directory"' EXIT
normalized_certificate="${temporary_directory}/normalized.pem"
temporary_store="${temporary_directory}/cacerts"

openssl x509 -in "$certificate" -out "$normalized_certificate"
cmp -s "$certificate" "$normalized_certificate" || { echo "Pinned CFCA certificate contains unexpected content" >&2; exit 1; }
actual_fingerprint="$(openssl x509 -in "$certificate" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':')"
[[ "$actual_fingerprint" == "$expected_fingerprint" ]] || { echo "Pinned CFCA certificate fingerprint mismatch" >&2; exit 1; }
[[ "$(openssl x509 -in "$certificate" -noout -subject -nameopt RFC2253)" == "$expected_subject" ]] || {
  echo "Pinned CFCA certificate subject mismatch" >&2
  exit 1
}
openssl x509 -in "$certificate" -checkend 0 -noout >/dev/null || { echo "Pinned CFCA certificate is expired" >&2; exit 1; }
openssl verify -CAfile "$certificate" "$certificate" >/dev/null

cp "${truststore_java_home}/lib/security/cacerts" "$temporary_store"
"${JAVA_HOME}/bin/keytool" -importcert -noprompt -trustcacerts \
  -alias cofco-cfca-ev-root -file "$certificate" -keystore "$temporary_store" \
  -storepass changeit >/dev/null 2>&1
mkdir -p "$(dirname "$output")"
install -m 600 "$temporary_store" "$output"
