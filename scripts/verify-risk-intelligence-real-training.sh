#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
config_file="${HOME}/.config/cofco-qiqihar-risk-intelligence/local-runtime.env"
[[ -f "$config_file" ]] || { echo "Local risk runtime config is missing" >&2; exit 1; }

while IFS='=' read -r key value; do
  case "$key" in
    RISK_LLM_TRAINER_URL|RISK_LLM_BEARER_TOKEN|RISK_LLM_BASE_MODEL|RISK_SERVER_PORT)
      export "$key=$value" ;;
  esac
done < "$config_file"

curl -fsS --max-time 5 "http://127.0.0.1:${RISK_SERVER_PORT:-63184}/actuator/health" >/dev/null
python3 "${backend_root}/tools/risk-mlx-trainer/verify_real_training.py"

if lsof -tiTCP:63182 -sTCP:LISTEN -P -n >/dev/null 2>&1; then
  curl -fsS --max-time 5 http://127.0.0.1:63182/actuator/health >/dev/null
  echo "EXISTING_LOCAL_BUSINESS_HEALTH_OK port=63182"
fi
echo "RISK_REAL_TRAINING_ACCEPTANCE_OK"
