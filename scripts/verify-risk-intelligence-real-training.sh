#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
full_config="${HOME}/.config/cofco-qiqihar-risk-intelligence/local-runtime.env"
node_config="${HOME}/.config/cofco-qiqihar-risk-training-node/training-node.env"
if [[ -f "$node_config" ]]; then
  config_file="$node_config"
elif [[ -f "$full_config" ]]; then
  config_file="$full_config"
else
  echo "Local risk training configuration is missing" >&2; exit 1
fi

while IFS='=' read -r key value; do
  case "$key" in
    RISK_LLM_TRAINER_URL|RISK_LLM_BEARER_TOKEN|RISK_LLM_BASE_MODEL|RISK_LLM_PORT|RISK_SERVER_PORT)
      export "$key=$value" ;;
  esac
done < "$config_file"

export RISK_LLM_BASE_MODEL="${RISK_LLM_BASE_MODEL:-mlx-community/Qwen3.8-27B-4bit}"
[[ "$RISK_LLM_BASE_MODEL" == "mlx-community/Qwen3.8-27B-4bit" ]] || {
  echo "Real training acceptance requires mlx-community/Qwen3.8-27B-4bit" >&2
  exit 1
}
trainer_health="${RISK_LLM_TRAINER_URL%/v1/train}/health"
curl -fsS --max-time 5 "$trainer_health" >/dev/null
python3 "${backend_root}/tools/risk-mlx-trainer/verify_real_training.py"

if lsof -tiTCP:63182 -sTCP:LISTEN -P -n >/dev/null 2>&1; then
  curl -fsS --max-time 5 http://127.0.0.1:63182/actuator/health >/dev/null
  echo "EXISTING_LOCAL_BUSINESS_HEALTH_OK port=63182"
fi
echo "RISK_REAL_TRAINING_ACCEPTANCE_OK"
