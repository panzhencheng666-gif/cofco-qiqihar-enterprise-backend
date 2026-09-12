#!/usr/bin/env bash
set -euo pipefail
backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export COFCO_ENTERPRISE_AUTH_MODE=oidc
python3 "${backend_root}/scripts/validate-managed-identity-config.py"
source "${backend_root}/scripts/jdk21-env.sh"
cd "$backend_root"
# A dedicated supervisor owns this foreground process. Never attach to an
# existing local listener or start the Vite local-identity proxy here.
exec env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u MAVEN_OPTS \
  SERVER_ADDRESS=127.0.0.1 QIQIHAR_SESSION_COOKIE_SECURE=true \
  QIQIHAR_IDENTITY_PUBLIC_SELF_REGISTRATION_ENABLED=false \
  mvn spring-boot:run -Dspring-boot.run.profiles=oidc
