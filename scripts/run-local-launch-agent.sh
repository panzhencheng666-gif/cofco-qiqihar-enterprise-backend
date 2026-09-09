#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
start_script="${1:-${backend_root}/scripts/start-local.sh}"
config_file="${COFCO_ENTERPRISE_LOCAL_ENV_FILE:-${HOME}/.config/cofco-qiqihar-enterprise/local-runtime.env}"

load_local_config() {
  local mode
  local line
  local key
  local value

  [[ -f "$config_file" ]] || return 0
  mode="$(stat -f '%Lp' "$config_file")"
  if (( (8#$mode & 077) != 0 )); then
    echo "Refusing to load local runtime config with group/world permissions: $config_file (mode $mode)" >&2
    return 1
  fi

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    if [[ "$line" != *=* ]]; then
      echo "Invalid local runtime config line (expected KEY=VALUE): $config_file" >&2
      return 1
    fi
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
      QIQIHAR_DB_URL | QIQIHAR_DB_USERNAME | QIQIHAR_DB_PASSWORD | \
        QIQIHAR_FLYWAY_USERNAME | QIQIHAR_FLYWAY_PASSWORD | \
        QIQIHAR_EVENT_CONSUMER_REGISTRAR_DB_URL | \
        QIQIHAR_EVENT_CONSUMER_REGISTRAR_DB_USERNAME | \
        QIQIHAR_EVENT_CONSUMER_REGISTRAR_DB_PASSWORD | \
        COFCO_ENTERPRISE_AUTH_MODE | \
        QIQIHAR_OIDC_ISSUER_URI | QIQIHAR_OIDC_CLIENT_ID | QIQIHAR_OIDC_CLIENT_SECRET | \
        QIQIHAR_OIDC_AUTHORIZATION_URI | QIQIHAR_OIDC_TOKEN_URI | \
        QIQIHAR_OIDC_JWK_SET_URI | QIQIHAR_OIDC_USER_INFO_URI | \
        QIQIHAR_OIDC_END_SESSION_URI | QIQIHAR_OIDC_REDIRECT_URI | \
        QIQIHAR_OIDC_POST_LOGOUT_REDIRECT_URI | QIQIHAR_OIDC_MFA_AMR_VALUES | \
        QIQIHAR_OIDC_MFA_ACR_VALUES | QIQIHAR_IDENTITY_INVITATION_ENCRYPTION_KEY | \
        QIQIHAR_IDENTITY_MANAGEMENT_URL | QIQIHAR_IDENTITY_DELIVERY_ENDPOINT | \
        QIQIHAR_IDENTITY_DELIVERY_BEARER_TOKEN | QIQIHAR_IDENTITY_ACTIVATION_URL | \
        QIQIHAR_IDENTITY_DELIVERY_WORKER_ENABLED)
        export "$key=$value"
        ;;
      *)
        echo "Unsupported key in local runtime config: $key" >&2
        return 1
        ;;
    esac
  done < "$config_file"
}

[[ -x "$start_script" ]] || {
  echo "Enterprise start script is not executable: $start_script" >&2
  exit 1
}

load_local_config
python3 "${backend_root}/scripts/validate-managed-identity-config.py"
if [[ "${COFCO_ENTERPRISE_AUTH_MODE:-local}" == oidc && $# -eq 0 ]]; then
  start_script="${backend_root}/scripts/start-oidc-backend.sh"
fi
exec /bin/bash "$start_script"
