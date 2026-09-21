#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
label="com.cofco.qiqihar.risk-training-node.local"
domain="gui/$(id -u)"
service_target="${domain}/${label}"
runtime_home="${HOME}/Library/Application Support/COFCO Qiqihar Risk Training Node"
releases_dir="${runtime_home}/releases"
current_release="${runtime_home}/current"
marker="${runtime_home}/.risk-training-node-runtime"
config_dir="${HOME}/.config/cofco-qiqihar-risk-training-node"
config_file="${config_dir}/training-node.env"
log_dir="${HOME}/Library/Logs/COFCO Qiqihar Risk Training Node"
source_plist="${backend_root}/ops/launchd/${label}.plist"
installed_plist="${HOME}/Library/LaunchAgents/${label}.plist"
trainer_port="${RISK_LLM_PORT:-63201}"

usage() { echo "Usage: $0 {install|upgrade|start|stop|restart|status|uninstall}"; }
loaded() { launchctl print "$service_target" >/dev/null 2>&1; }
listener() { lsof -tiTCP:"$trainer_port" -sTCP:LISTEN -P -n 2>/dev/null | head -n1; }

require_install_environment() {
  local name
  for name in RISK_TRAINING_CLOUD_URL RISK_TRAINING_NODE_TOKEN RISK_TRAINING_NODE_ID \
    RISK_LLM_BEARER_TOKEN; do
    [[ -n "${!name:-}" ]] || { echo "Required install environment variable is missing: $name" >&2; return 1; }
  done
  [[ "$RISK_TRAINING_CLOUD_URL" == https://* ]] || { echo "Training cloud URL must use HTTPS" >&2; return 1; }
}

write_config() {
  mkdir -p "$config_dir"; chmod 700 "$config_dir"; umask 077
  {
    printf 'RISK_TRAINING_CLOUD_URL=%s\n' "$RISK_TRAINING_CLOUD_URL"
    printf 'RISK_TRAINING_NODE_TOKEN=%s\n' "$RISK_TRAINING_NODE_TOKEN"
    printf 'RISK_TRAINING_NODE_ID=%s\n' "$RISK_TRAINING_NODE_ID"
    printf 'RISK_TRAINING_NODE_STATE_ROOT=%s\n' "${runtime_home}/state"
    printf 'RISK_TRAINING_NODE_POLL_SECONDS=%s\n' "${RISK_TRAINING_NODE_POLL_SECONDS:-60}"
    printf 'RISK_TRAINING_NODE_HEARTBEAT_SECONDS=%s\n' "${RISK_TRAINING_NODE_HEARTBEAT_SECONDS:-300}"
    printf 'RISK_LLM_TRAINER_URL=http://127.0.0.1:%s/v1/train\n' "$trainer_port"
    printf 'RISK_LLM_BEARER_TOKEN=%s\n' "$RISK_LLM_BEARER_TOKEN"
    printf 'RISK_LLM_PORT=%s\n' "$trainer_port"
    printf 'RISK_LLM_ARTIFACT_ROOT=%s\n' "${runtime_home}/model-artifacts/lora"
    printf 'RISK_LLM_TRAIN_ITERS=%s\n' "${RISK_LLM_TRAIN_ITERS:-80}"
  } >"${config_file}.new"
  chmod 600 "${config_file}.new"; mv "${config_file}.new" "$config_file"
}

install_release() {
  local temporary final timestamp
  timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  temporary="$(mktemp -d "${releases_dir}/.install.XXXXXX")"
  final="${releases_dir}/${timestamp}-$$"
  install -m 500 "${backend_root}/scripts/run-risk-training-node-launch-agent.sh" "$temporary/"
  install -m 400 "${backend_root}/scripts/local-process-ownership.sh" "$temporary/"
  install -m 400 "${backend_root}/tools/risk-mlx-trainer/server.py" "$temporary/risk-mlx-trainer.py"
  install -m 400 "${backend_root}/tools/risk-mlx-trainer/remote_worker.py" "$temporary/remote_worker.py"
  mv "$temporary" "$final"
  ln -sfn "$final" "${current_release}.new"; mv -fh "${current_release}.new" "$current_release"
}

install_node() {
  require_install_environment
  [[ -z "$(listener)" ]] || { echo "Refusing to replace an unowned listener on port $trainer_port" >&2; return 1; }
  [[ ! -d "$runtime_home" || -f "$marker" || -z "$(find "$runtime_home" -mindepth 1 -maxdepth 1 -print -quit)" ]] || {
    echo "Refusing to replace an unrecognized runtime directory" >&2; return 1; }
  mkdir -p "$runtime_home" "$releases_dir" "$log_dir" "${HOME}/Library/LaunchAgents"
  chmod 700 "$runtime_home" "$releases_dir" "$log_dir"; : >"$marker"; chmod 600 "$marker"
  write_config
  if [[ ! -x "${runtime_home}/mlx-venv/bin/python3" ]]; then
    [[ -x "${backend_root}/tools/risk-mlx-trainer/.venv/bin/python3" ]] || { echo "Source MLX venv unavailable" >&2; return 1; }
    /bin/cp -cR "${backend_root}/tools/risk-mlx-trainer/.venv" "${runtime_home}/mlx-venv"
  fi
  install_release
  sed "s|__HOME__|${HOME}|g" "$source_plist" >"${installed_plist}.new"
  chmod 600 "${installed_plist}.new"; mv "${installed_plist}.new" "$installed_plist"
  launchctl enable "$service_target"; launchctl bootstrap "$domain" "$installed_plist"
  for _ in {1..120}; do "${backend_root}/scripts/healthcheck-risk-training-node-local.sh" && break; sleep 0.5; done
  status_node
}

upgrade_node() {
  [[ -f "$installed_plist" && -f "$config_file" && -L "$current_release" ]] || {
    echo "Training node is not installed" >&2; return 1; }
  local previous
  previous="$(readlink "$current_release")"
  stop_node
  install_release
  if ! start_node || ! status_node; then
    stop_node
    ln -sfn "$previous" "${current_release}.new"; mv -fh "${current_release}.new" "$current_release"
    start_node || true
    echo "Training node upgrade failed; previous release restored" >&2
    return 1
  fi
  echo "RISK_TRAINING_NODE_UPGRADE_OK release=$(readlink "$current_release")"
}

start_node() { loaded && launchctl kickstart "$service_target" || launchctl bootstrap "$domain" "$installed_plist"; }
stop_node() { loaded && launchctl bootout "$service_target" || true; }
status_node() {
  code="$(curl -sS --max-time 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${trainer_port}/health" 2>/dev/null || true)"
  echo "training-node installed=$([[ -f "$installed_plist" ]] && echo yes || echo no) loaded=$(loaded && echo yes || echo no) trainer-http=${code:-000}"
  [[ -f "$installed_plist" ]] && loaded && [[ "$code" == 200 ]]
}

case "${1:-}" in
  install) install_node ;;
  upgrade) upgrade_node ;;
  start) start_node ;;
  stop) stop_node ;;
  restart) stop_node; start_node ;;
  status) status_node ;;
  uninstall) stop_node; [[ ! -f "$installed_plist" ]] || rm -f "$installed_plist"; echo "Training node uninstalled; runtime retained" ;;
  *) usage; exit 2 ;;
esac
