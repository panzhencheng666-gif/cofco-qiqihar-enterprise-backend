#!/usr/bin/env bash
set -euo pipefail

config_file="${COFCO_ENTERPRISE_LOCAL_ENV_FILE:-${HOME}/.config/cofco-qiqihar-enterprise/local-runtime.env}"
[[ -f "$config_file" ]] || {
  echo "Enterprise local runtime config is missing: $config_file" >&2
  exit 1
}
mode="$(stat -f '%Lp' "$config_file")"
if (( (8#$mode & 077) != 0 )); then
  echo "Refusing to edit enterprise config with unsafe mode: $mode" >&2
  exit 1
fi

temporary="${config_file}.risk-worker.$$"
umask 077
awk '!/^QIQIHAR_RISK_TRAINING_ENABLED=/' "$config_file" > "$temporary"
printf 'QIQIHAR_RISK_TRAINING_ENABLED=false\n' >> "$temporary"
chmod 600 "$temporary"
mv "$temporary" "$config_file"

echo "LEGACY_RISK_TRAINING_WORKER_DISABLED config=$config_file"
