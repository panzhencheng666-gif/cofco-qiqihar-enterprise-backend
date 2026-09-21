#!/usr/bin/env bash
set -euo pipefail

release_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
config_file="${HOME}/.config/cofco-qiqihar-risk-intelligence/local-runtime.env"
runtime_state="${HOME}/Library/Application Support/COFCO Qiqihar Risk Intelligence/state"
pid_file="${runtime_state}/risk-intelligence.pid"
trainer_pid_file="${runtime_state}/risk-mlx-trainer.pid"
trainer_python="${HOME}/Library/Application Support/COFCO Qiqihar Risk Intelligence/mlx-venv/bin/python3"
source "${release_root}/local-process-ownership.sh"

load_config() {
  local mode line key value
  [[ -f "$config_file" ]] || {
    echo "Risk runtime config is missing: $config_file" >&2
    return 1
  }
  mode="$(stat -f '%Lp' "$config_file")"
  if (( (8#$mode & 077) != 0 )); then
    echo "Refusing to load risk runtime config with group/world permissions: $config_file (mode $mode)" >&2
    return 1
  fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" == *=* ]] || {
      echo "Invalid risk runtime config line: $config_file" >&2
      return 1
    }
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
      RISK_DB_URL|RISK_DB_USERNAME|RISK_DB_PASSWORD|RISK_EXPECTED_DATABASE|\
        RISK_INGESTION_KEY|RISK_SERVER_PORT|RISK_TRAINING_ENABLED|\
        RISK_MODEL_ARTIFACT_ROOT|RISK_TRAINING_POLL_DELAY|\
        RISK_TRAINING_SCHEDULE_RECONCILE_DELAY|RISK_TRAINING_LIFECYCLE_DELAY|\
        RISK_LLM_TRAINER_URL|RISK_LLM_SCORING_URL|RISK_LLM_BEARER_TOKEN|\
        RISK_LLM_BASE_MODEL|RISK_LLM_PORT|RISK_LLM_ARTIFACT_ROOT|JAVA_HOME)
        export "$key=$value"
        ;;
      *)
        echo "Unsupported key in risk runtime config: $key" >&2
        return 1
        ;;
    esac
  done < "$config_file"
}

load_config
port="${RISK_SERVER_PORT:-63184}"
trainer_port="${RISK_LLM_PORT:-63201}"
listener_pid="$(pid_listening_on_port "$port" || true)"
if [[ -n "$listener_pid" ]]; then
  echo "Refusing to start risk intelligence: port $port is already owned by pid=$listener_pid" >&2
  exit 1
fi
trainer_listener_pid="$(pid_listening_on_port "$trainer_port" || true)"
if [[ -n "$trainer_listener_pid" ]]; then
  echo "Refusing to start MLX trainer: port $trainer_port is already owned by pid=$trainer_listener_pid" >&2
  exit 1
fi

java_bin="${JAVA_HOME:-}/bin/java"
if [[ ! -x "$java_bin" ]]; then
  java_bin="$(command -v java || true)"
fi
[[ -x "$java_bin" ]] || {
  echo "Java 21 executable is unavailable" >&2
  exit 1
}
[[ -x "$trainer_python" && -f "${release_root}/risk-mlx-trainer.py" ]] || {
  echo "Persistent MLX trainer runtime is unavailable" >&2
  exit 1
}

mkdir -p "$runtime_state"
chmod 700 "$runtime_state"

child_pid=""
trainer_pid=""
cleanup() {
  local code=$?
  trap - EXIT INT TERM
  if [[ -n "$child_pid" ]] && kill -0 "$child_pid" 2>/dev/null; then
    kill "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  fi
  if [[ -n "$trainer_pid" ]] && kill -0 "$trainer_pid" 2>/dev/null; then
    kill "$trainer_pid" 2>/dev/null || true
    wait "$trainer_pid" 2>/dev/null || true
  fi
  if load_owned_process "$pid_file" 2>/dev/null &&
    [[ "$COFCO_OWNED_ROOT_PID" == "$$" ]]; then
    rm -f "$pid_file"
  fi
  if load_owned_process "$trainer_pid_file" 2>/dev/null &&
    [[ "$COFCO_OWNED_ROOT_PID" == "$$" ]]; then
    rm -f "$trainer_pid_file"
  fi
  exit "$code"
}
trap cleanup EXIT INT TERM

"$trainer_python" "${release_root}/risk-mlx-trainer.py" &
trainer_pid=$!
for _ in {1..120}; do
  trainer_listener_pid="$(pid_listening_on_port "$trainer_port" || true)"
  if [[ "$trainer_listener_pid" == "$trainer_pid" ]]; then
    record_owned_process "$trainer_pid_file" "$$" "$trainer_pid" "$trainer_port" "risk-mlx-trainer"
    break
  fi
  if [[ -n "$trainer_listener_pid" && "$trainer_listener_pid" != "$trainer_pid" ]]; then
    echo "MLX trainer port $trainer_port was claimed by unexpected pid=$trainer_listener_pid" >&2
    exit 1
  fi
  kill -0 "$trainer_pid" 2>/dev/null || {
    wait "$trainer_pid"
    exit $?
  }
  sleep 0.25
done
owned_listener_matches_port_and_service \
  "$trainer_pid_file" "$trainer_pid" "$trainer_port" "risk-mlx-trainer" || {
  echo "MLX trainer did not bind port $trainer_port within 30 seconds" >&2
  exit 1
}

"$java_bin" -jar "${release_root}/risk-intelligence-service.jar" &
child_pid=$!

for _ in {1..120}; do
  listener_pid="$(pid_listening_on_port "$port" || true)"
  if [[ "$listener_pid" == "$child_pid" ]]; then
    record_owned_process "$pid_file" "$$" "$child_pid" "$port" "risk-intelligence"
    while kill -0 "$child_pid" 2>/dev/null && kill -0 "$trainer_pid" 2>/dev/null; do
      sleep 2
    done
    echo "Risk intelligence managed process exited; restarting the complete pair" >&2
    exit 1
  fi
  if [[ -n "$listener_pid" && "$listener_pid" != "$child_pid" ]]; then
    echo "Risk port $port was claimed by unexpected pid=$listener_pid" >&2
    exit 1
  fi
  kill -0 "$child_pid" 2>/dev/null || {
    wait "$child_pid"
    exit $?
  }
  sleep 0.25
done

echo "Risk intelligence did not bind port $port within 30 seconds" >&2
exit 1
