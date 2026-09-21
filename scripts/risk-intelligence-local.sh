#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
label="com.cofco.qiqihar.risk-intelligence.local"
domain="gui/$(id -u)"
service_target="${domain}/${label}"
runtime_home="${HOME}/Library/Application Support/COFCO Qiqihar Risk Intelligence"
releases_dir="${runtime_home}/releases"
current_release="${runtime_home}/current"
runtime_state="${runtime_home}/state"
marker="${runtime_home}/.risk-intelligence-runtime"
config_dir="${HOME}/.config/cofco-qiqihar-risk-intelligence"
config_file="${config_dir}/local-runtime.env"
log_dir="${HOME}/Library/Logs/COFCO Qiqihar Risk Intelligence"
source_plist="${backend_root}/ops/launchd/${label}.plist"
installed_plist="${HOME}/Library/LaunchAgents/${label}.plist"
port="${RISK_SERVER_PORT:-63184}"
trainer_port="${RISK_LLM_PORT:-63201}"

usage() {
  echo "Usage: $0 {install|upgrade|start|stop|restart|status|uninstall}"
}

agent_is_loaded() {
  launchctl print "$service_target" >/dev/null 2>&1
}

listener_pid() {
  lsof -tiTCP:"$port" -sTCP:LISTEN -P -n 2>/dev/null | head -n 1
}

trainer_listener_pid() {
  lsof -tiTCP:"$trainer_port" -sTCP:LISTEN -P -n 2>/dev/null | head -n 1
}

wait_for_port_release() {
  for _ in {1..80}; do
    [[ -z "$(listener_pid)" && -z "$(trainer_listener_pid)" ]] && return 0
    sleep 0.25
  done
  echo "Risk port $port was not released" >&2
  return 1
}

wait_for_health() {
  for _ in {1..120}; do
    if curl -fsS --max-time 2 "http://127.0.0.1:${port}/actuator/health" >/dev/null 2>&1 &&
      curl -fsS --max-time 2 "http://127.0.0.1:${trainer_port}/health" >/dev/null 2>&1; then
      "${current_release}/healthcheck-risk-intelligence-local.sh"
      return 0
    fi
    sleep 0.5
  done
  echo "Risk intelligence did not become healthy within 60 seconds" >&2
  echo "Inspect logs under: $log_dir" >&2
  return 1
}

assert_runtime_home_safe() {
  if [[ -d "$runtime_home" && ! -f "$marker" ]]; then
    if find "$runtime_home" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
      echo "Refusing to replace an unrecognized runtime directory: $runtime_home" >&2
      return 1
    fi
  fi
}

require_install_environment() {
  local name
  for name in RISK_DB_URL RISK_DB_USERNAME RISK_DB_PASSWORD RISK_EXPECTED_DATABASE \
    RISK_INGESTION_KEY RISK_LLM_BEARER_TOKEN RISK_LLM_BASE_MODEL \
    RISK_TRAINING_NODE_TOKEN; do
    if [[ -z "${!name:-}" ]]; then
      echo "Required install environment variable is missing: $name" >&2
      return 1
    fi
  done
  [[ "$RISK_DB_USERNAME" == "qiqihar_risk_runtime_login" ]] || {
    echo "Risk service must use qiqihar_risk_runtime_login" >&2
    return 1
  }
}

write_runtime_config() {
  local java_home_value="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
  mkdir -p "$config_dir"
  chmod 700 "$config_dir"
  umask 077
  {
    printf 'RISK_DB_URL=%s\n' "$RISK_DB_URL"
    printf 'RISK_DB_USERNAME=%s\n' "$RISK_DB_USERNAME"
    printf 'RISK_DB_PASSWORD=%s\n' "$RISK_DB_PASSWORD"
    printf 'RISK_EXPECTED_DATABASE=%s\n' "$RISK_EXPECTED_DATABASE"
    printf 'RISK_INGESTION_KEY=%s\n' "$RISK_INGESTION_KEY"
    printf 'RISK_SERVER_PORT=%s\n' "$port"
    printf 'RISK_TRAINING_ENABLED=true\n'
    printf 'RISK_MODEL_ARTIFACT_ROOT=%s\n' "${runtime_home}/model-artifacts"
    printf 'RISK_TRAINING_POLL_DELAY=10s\n'
    printf 'RISK_TRAINING_SCHEDULE_RECONCILE_DELAY=1m\n'
    printf 'RISK_TRAINING_LIFECYCLE_DELAY=30s\n'
    printf 'RISK_LLM_TRAINER_URL=http://127.0.0.1:%s/v1/train\n' "$trainer_port"
    printf 'RISK_LLM_SCORING_URL=http://127.0.0.1:%s/v1/score\n' "$trainer_port"
    printf 'RISK_LLM_BEARER_TOKEN=%s\n' "$RISK_LLM_BEARER_TOKEN"
    printf 'RISK_LLM_BASE_MODEL=%s\n' "$RISK_LLM_BASE_MODEL"
    printf 'RISK_LLM_PORT=%s\n' "$trainer_port"
    printf 'RISK_LLM_ARTIFACT_ROOT=%s\n' "${runtime_home}/model-artifacts/lora"
    printf 'RISK_TRAINING_NODE_TOKEN=%s\n' "$RISK_TRAINING_NODE_TOKEN"
    printf 'RISK_TRAINING_NODE_LEASE_DURATION=35m\n'
    printf 'RISK_TRAINING_NODE_ARTIFACT_ROOT=%s\n' "${runtime_home}/model-artifacts/remote"
    printf 'RISK_TRAINING_NODE_MAXIMUM_ARTIFACT_BYTES=67108864\n'
    printf 'JAVA_HOME=%s\n' "$java_home_value"
  } > "${config_file}.new"
  chmod 600 "${config_file}.new"
  mv "${config_file}.new" "$config_file"
}

install_release() {
  local temporary_release final_release timestamp
  "${backend_root}/scripts/mvn-jdk21.sh" -q \
    -f "${backend_root}/risk-intelligence-service/pom.xml" -DskipTests package
  timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  temporary_release="$(mktemp -d "${releases_dir}/.install.XXXXXX")"
  final_release="${releases_dir}/${timestamp}-$$"
  install -m 500 "${backend_root}/scripts/run-risk-intelligence-launch-agent.sh" \
    "${temporary_release}/run-risk-intelligence-launch-agent.sh"
  install -m 500 "${backend_root}/scripts/healthcheck-risk-intelligence-local.sh" \
    "${temporary_release}/healthcheck-risk-intelligence-local.sh"
  install -m 400 "${backend_root}/scripts/local-process-ownership.sh" \
    "${temporary_release}/local-process-ownership.sh"
  install -m 400 "${backend_root}/risk-intelligence-service/target/risk-intelligence-service-0.1.0-SNAPSHOT.jar" \
    "${temporary_release}/risk-intelligence-service.jar"
  install -m 400 "${backend_root}/tools/risk-mlx-trainer/server.py" \
    "${temporary_release}/risk-mlx-trainer.py"
  mv "$temporary_release" "$final_release"
  ln -sfn "$final_release" "${current_release}.new"
  mv -fh "${current_release}.new" "$current_release"
}

install_agent() {
  require_install_environment
  assert_runtime_home_safe
  [[ -f "$source_plist" ]] || {
    echo "Risk LaunchAgent template is missing: $source_plist" >&2
    return 1
  }
  plutil -lint "$source_plist" >/dev/null

  if agent_is_loaded; then
    launchctl bootout "$service_target"
    wait_for_port_release
  elif [[ -n "$(listener_pid)" ]]; then
    echo "Refusing to replace an unowned listener on port $port (pid=$(listener_pid))" >&2
    return 1
  fi
  if [[ -n "$(trainer_listener_pid)" ]]; then
    echo "Refusing to replace an unowned MLX listener on port $trainer_port (pid=$(trainer_listener_pid))" >&2
    return 1
  fi

  mkdir -p "$runtime_home" "$releases_dir" "$runtime_state" "$log_dir" "${HOME}/Library/LaunchAgents"
  chmod 700 "$runtime_home" "$releases_dir" "$runtime_state" "$log_dir"
  : > "$marker"
  chmod 600 "$marker"
  write_runtime_config
  mkdir -p "${runtime_home}/model-artifacts"
  chmod 700 "${runtime_home}/model-artifacts"
  if [[ ! -x "${runtime_home}/mlx-venv/bin/python3" ]]; then
    [[ -x "${backend_root}/tools/risk-mlx-trainer/.venv/bin/python3" ]] || {
      echo "Source MLX virtual environment is unavailable" >&2
      return 1
    }
    /bin/cp -cR "${backend_root}/tools/risk-mlx-trainer/.venv" \
      "${runtime_home}/mlx-venv"
  fi
  install_release
  sed "s|__HOME__|${HOME}|g" "$source_plist" > "${installed_plist}.new"
  chmod 600 "${installed_plist}.new"
  mv "${installed_plist}.new" "$installed_plist"

  launchctl enable "$service_target"
  launchctl bootstrap "$domain" "$installed_plist"
  wait_for_health
  status_agent
}

upgrade_agent() {
  [[ -f "$installed_plist" && -f "$config_file" && -L "$current_release" ]] || {
    echo "Risk LaunchAgent is not installed" >&2
    return 1
  }
  local previous
  previous="$(readlink "$current_release")"
  if agent_is_loaded; then launchctl bootout "$service_target"; wait_for_port_release; fi
  install_release
  if ! launchctl bootstrap "$domain" "$installed_plist" || ! wait_for_health; then
    if agent_is_loaded; then launchctl bootout "$service_target" || true; fi
    ln -sfn "$previous" "${current_release}.new"; mv -fh "${current_release}.new" "$current_release"
    launchctl bootstrap "$domain" "$installed_plist" || true
    wait_for_health || true
    echo "Risk intelligence upgrade failed; previous release restored" >&2
    return 1
  fi
  echo "RISK_INTELLIGENCE_UPGRADE_OK release=$(readlink "$current_release")"
}

start_agent() {
  [[ -f "$installed_plist" && -L "$current_release" ]] || {
    echo "Risk LaunchAgent is not installed. Run: $0 install" >&2
    return 1
  }
  if agent_is_loaded; then
    launchctl kickstart "$service_target"
  else
    launchctl bootstrap "$domain" "$installed_plist"
  fi
  wait_for_health
}

stop_agent() {
  if agent_is_loaded; then
    launchctl bootout "$service_target"
  fi
  wait_for_port_release
  echo "Risk intelligence LaunchAgent stopped"
}

restart_agent() {
  local previous_pid current_pid
  previous_pid="$(listener_pid || true)"
  if agent_is_loaded; then
    launchctl kickstart -k "$service_target"
  else
    start_agent
    return
  fi
  wait_for_health
  current_pid="$(listener_pid || true)"
  [[ -n "$current_pid" && "$current_pid" != "$previous_pid" ]] || {
    echo "Risk intelligence restart did not produce a new listener PID" >&2
    return 1
  }
  echo "RISK_INTELLIGENCE_RESTART_OK oldPid=${previous_pid:--} newPid=$current_pid"
}

status_agent() {
  local loaded=no pid="-" code=000
  agent_is_loaded && loaded=yes
  pid="$(listener_pid || true)"
  code="$(curl -sS --max-time 3 -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:${port}/actuator/health" 2>/dev/null || true)"
  echo "LaunchAgent label=$label installed=$([[ -f "$installed_plist" ]] && echo yes || echo no) loaded=$loaded"
  trainer_pid="$(trainer_listener_pid || true)"
  trainer_code="$(curl -sS --max-time 3 -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:${trainer_port}/health" 2>/dev/null || true)"
  echo "risk-intelligence pid=${pid:--} port=$port http=${code:-000}"
  echo "risk-mlx-trainer pid=${trainer_pid:--} port=$trainer_port http=${trainer_code:-000}"
  [[ -f "$installed_plist" && "$loaded" == yes && -n "$pid" && "$code" == 200 &&
    -n "$trainer_pid" && "$trainer_code" == 200 ]]
}

uninstall_agent() {
  stop_agent
  [[ ! -f "$installed_plist" ]] || rm -f "$installed_plist"
  echo "Risk intelligence LaunchAgent uninstalled; runtime and config retained"
}

case "${1:-}" in
  install) install_agent ;;
  upgrade) upgrade_agent ;;
  start) start_agent ;;
  stop) stop_agent ;;
  restart) restart_agent ;;
  status) status_agent ;;
  uninstall) uninstall_agent ;;
  *) usage; exit 2 ;;
esac
