#!/usr/bin/env bash
set -euo pipefail

release_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
config_file="${HOME}/.config/cofco-qiqihar-risk-training-node/training-node.env"
runtime_home="${HOME}/Library/Application Support/COFCO Qiqihar Risk Training Node"
runtime_state="${runtime_home}/state"
trainer_pid_file="${runtime_state}/risk-mlx-trainer.pid"
python_bin="${runtime_home}/mlx-venv/bin/python3"
source "${release_root}/local-process-ownership.sh"

load_config() {
  local mode line key value
  [[ -f "$config_file" ]] || { echo "Training node config is missing" >&2; return 1; }
  mode="$(stat -f '%Lp' "$config_file")"
  (( (8#$mode & 077) == 0 )) || { echo "Training node config permissions are unsafe" >&2; return 1; }
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" == *=* ]] || { echo "Invalid training node config line" >&2; return 1; }
    key="${line%%=*}"; value="${line#*=}"
    case "$key" in
      RISK_TRAINING_CLOUD_URL|RISK_TRAINING_NODE_TOKEN|RISK_TRAINING_NODE_ID|\
        RISK_TRAINING_NODE_STATE_ROOT|RISK_TRAINING_NODE_POLL_SECONDS|\
        RISK_TRAINING_NODE_HEARTBEAT_SECONDS|RISK_LLM_TRAINER_URL|\
        RISK_LLM_BEARER_TOKEN|RISK_LLM_PORT|RISK_LLM_ARTIFACT_ROOT|RISK_LLM_TRAIN_ITERS)
        export "$key=$value" ;;
      *) echo "Unsupported training node config key: $key" >&2; return 1 ;;
    esac
  done < "$config_file"
}

load_config
trainer_port="${RISK_LLM_PORT:-63201}"
[[ -x "$python_bin" ]] || { echo "MLX Python runtime is unavailable" >&2; exit 1; }
mkdir -p "$runtime_state" "$RISK_LLM_ARTIFACT_ROOT" "$RISK_TRAINING_NODE_STATE_ROOT"
chmod 700 "$runtime_state" "$RISK_LLM_ARTIFACT_ROOT" "$RISK_TRAINING_NODE_STATE_ROOT"

occupied="$(pid_listening_on_port "$trainer_port" || true)"
[[ -z "$occupied" ]] || { echo "Training port is already owned by pid=$occupied" >&2; exit 1; }

trainer_pid=""; worker_pid=""
cleanup() {
  local code=$?
  trap - EXIT INT TERM
  [[ -z "$worker_pid" ]] || kill "$worker_pid" 2>/dev/null || true
  [[ -z "$trainer_pid" ]] || kill "$trainer_pid" 2>/dev/null || true
  [[ -z "$worker_pid" ]] || wait "$worker_pid" 2>/dev/null || true
  [[ -z "$trainer_pid" ]] || wait "$trainer_pid" 2>/dev/null || true
  rm -f "$trainer_pid_file"
  exit "$code"
}
trap cleanup EXIT INT TERM

"$python_bin" "${release_root}/risk-mlx-trainer.py" &
trainer_pid=$!
for _ in {1..120}; do
  listener="$(pid_listening_on_port "$trainer_port" || true)"
  if [[ "$listener" == "$trainer_pid" ]]; then
    record_owned_process "$trainer_pid_file" "$$" "$trainer_pid" "$trainer_port" "risk-mlx-trainer"
    break
  fi
  kill -0 "$trainer_pid" 2>/dev/null || { wait "$trainer_pid"; exit $?; }
  sleep 0.25
done
owned_listener_matches_port_and_service "$trainer_pid_file" "$trainer_pid" \
  "$trainer_port" "risk-mlx-trainer" || { echo "MLX trainer did not start" >&2; exit 1; }

"$python_bin" "${release_root}/remote_worker.py" &
worker_pid=$!
while kill -0 "$trainer_pid" 2>/dev/null && kill -0 "$worker_pid" 2>/dev/null; do sleep 2; done
echo "Training node child exited; launchd will restart the complete node" >&2
exit 1
