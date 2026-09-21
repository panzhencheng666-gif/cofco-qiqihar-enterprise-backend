#!/usr/bin/env bash
set -euo pipefail

config_file="${HOME}/.config/cofco-qiqihar-risk-training-node/training-node.env"
trainer_port="${RISK_LLM_PORT:-63201}"
if [[ -f "$config_file" ]]; then
  mode="$(stat -f '%Lp' "$config_file")"
  (( (8#$mode & 077) == 0 )) || { echo "Unsafe training node config mode" >&2; exit 1; }
  while IFS='=' read -r key value; do
    [[ "$key" == "RISK_LLM_PORT" ]] && trainer_port="$value"
  done < "$config_file"
fi
payload="$(curl -fsS --max-time 4 "http://127.0.0.1:${trainer_port}/health")"
python3 -c 'import json,sys; p=json.load(sys.stdin); assert p.get("status")=="UP"; assert p.get("engine")=="mlx-lm"' <<<"$payload"
launchctl print "gui/$(id -u)/com.cofco.qiqihar.risk-training-node.local" >/dev/null
echo "RISK_TRAINING_NODE_HEALTH_OK url=http://127.0.0.1:${trainer_port}/health"
