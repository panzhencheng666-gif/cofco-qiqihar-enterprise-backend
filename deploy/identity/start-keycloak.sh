#!/bin/bash
set -euo pipefail
# No end-user passwords or bootstrap users are embedded in deployment files.
export KC_DB_PASSWORD="$(cat /run/secrets/database_password)"
exec /opt/keycloak/bin/kc.sh start
