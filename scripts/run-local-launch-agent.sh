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
      QIQIHAR_DB_URL | QIQIHAR_DB_USERNAME | QIQIHAR_DB_PASSWORD)
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
exec /bin/bash "$start_script"
