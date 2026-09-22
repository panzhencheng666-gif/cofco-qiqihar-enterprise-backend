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
  require_release_sources || return 1
  timestamp="$(date -u '+%Y%m%dT%H%M%SZ')" || return 1
  temporary="$(mktemp -d "${releases_dir}/.install.XXXXXX")" || return 1
  final="${releases_dir}/${timestamp}-$$"
  install -m 500 "${backend_root}/scripts/run-risk-training-node-launch-agent.sh" "$temporary/" || return 1
  install -m 400 "${backend_root}/scripts/local-process-ownership.sh" "$temporary/" || return 1
  install -m 400 "${backend_root}/tools/risk-mlx-trainer/server.py" "$temporary/risk-mlx-trainer.py" || return 1
  install -m 400 "${backend_root}/tools/risk-mlx-trainer/remote_worker.py" \
    "${backend_root}/tools/risk-mlx-trainer/expert.py" \
    "${backend_root}/tools/risk-mlx-trainer/expert_dataset.py" \
    "${backend_root}/tools/risk-mlx-trainer/expert_training.py" \
    "${backend_root}/tools/risk-mlx-trainer/expert_training_worker.py" \
    "${backend_root}/tools/risk-mlx-trainer/expert_training_artifacts.py" \
    "${backend_root}/tools/risk-mlx-trainer/expert_training_process.py" \
    "${backend_root}/tools/risk-mlx-trainer/expert_knowledge.json" "$temporary/" || return 1
  mv "$temporary" "$final" || return 1
  activate_release "$final"
}

activate_release() {
  ln -sfn "$1" "${current_release}.new" && mv -fh "${current_release}.new" "$current_release"
}

require_release_sources() {
  local file
  for file in scripts/run-risk-training-node-launch-agent.sh scripts/local-process-ownership.sh \
    tools/risk-mlx-trainer/server.py tools/risk-mlx-trainer/remote_worker.py \
    tools/risk-mlx-trainer/expert.py tools/risk-mlx-trainer/expert_knowledge.json \
    tools/risk-mlx-trainer/expert_dataset.py tools/risk-mlx-trainer/expert_training.py \
    tools/risk-mlx-trainer/expert_training_worker.py tools/risk-mlx-trainer/expert_training_artifacts.py \
    tools/risk-mlx-trainer/expert_training_process.py \
    scripts/healthcheck-risk-training-node-local.sh; do
    [[ -f "${backend_root}/${file}" && -r "${backend_root}/${file}" ]] || {
      echo "Required release source is missing or unreadable: $file" >&2; return 1; }
  done
}

preflight_upgrade() {
  require_release_sources || return 1
  python3 - "$runtime_home" "$config_file" "$current_release" "$releases_dir" <<'PY'
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

def reject(reason):
    sys.exit(reason)

try:
    runtime, config, current, releases = map(Path, sys.argv[1:])
    marker = runtime / ".risk-training-node-runtime"
    if (runtime.is_symlink() or not marker.is_file() or marker.is_symlink()
            or releases.is_symlink() or not current.is_symlink()
            or not current.resolve().is_dir()
            or current.resolve().parent != releases.resolve()):
        reject("Refusing to replace an unrecognized runtime directory/release")
    if config.is_symlink() or stat.S_IMODE(config.stat().st_mode) & 0o077:
        reject("Training node config permissions are unsafe")
    values = {}
    for line in config.read_text().splitlines():
        if line and not line.startswith("#"):
            key, sep, value = line.partition("=")
            if not sep:
                reject("Invalid training node config line")
            values[key] = value
    state = Path(values.get("RISK_TRAINING_NODE_STATE_ROOT", str(runtime / "state")))
    if not state.is_absolute():
        reject("Training node state root must be absolute")
    for claim in (runtime / "state/active-claim.json", state / "active-claim.json"):
        if os.path.lexists(claim):
            reject("Refusing upgrade: active claim exists; wait for legitimate idle status")
    if "RISK_EXPERT_MODEL_PATH" in os.environ:
        value = os.environ["RISK_EXPERT_MODEL_PATH"]
        model = Path(value)
        if (not value or value != value.strip() or "\n" in value or "\r" in value
                or not model.is_absolute() or not model.is_dir()):
            reject("Expert model must be an existing absolute local snapshot directory")
        for name in ("config.json", "tokenizer_config.json"):
            metadata = json.loads((model / name).read_text())
            if not isinstance(metadata, dict):
                reject("Expert model metadata must be JSON objects")
        if not any(p.is_file() and p.stat().st_size > 0 for p in model.glob("*.safetensors")):
            reject("Expert model weights are missing")
    # ps does not quote paths containing spaces. Match the complete known script
    # argument with whitespace boundaries rather than splitting its command line.
    known = {str(current / "risk-mlx-trainer.py"), str(current.resolve() / "risk-mlx-trainer.py")}
    rows = []
    result = subprocess.run(["ps", "-axo", "pid=,ppid=,command="],
                            capture_output=True, text=True, check=True, timeout=5)
    for row in result.stdout.splitlines():
        parts = row.strip().split(None, 2)
        if len(parts) == 3:
            rows.append((parts[0], parts[1], parts[2]))
    trainers = {pid for pid, _, command in rows
                if any(f" {path} " in f" {command} " for path in known)}
    if any(parent in trainers for _, parent, _ in rows):
        reject("Refusing upgrade: known trainer has a live worker child")
except (OSError, ValueError, subprocess.SubprocessError):
    reject("Upgrade preflight failed: model metadata, config or process state could not be verified")
PY
}

stage_expert_config() {
  python3 - "$config_file" "$1" <<'PY'
import os
from pathlib import Path
import sys

source, target = map(Path, sys.argv[1:])
data = source.read_bytes()
if "RISK_EXPERT_MODEL_PATH" in os.environ:
    replacement = b"RISK_EXPERT_MODEL_PATH=" + os.fsencode(os.environ["RISK_EXPERT_MODEL_PATH"])
    lines = data.splitlines(keepends=True)
    found = False
    for index, line in enumerate(lines):
        if line.startswith(b"RISK_EXPERT_MODEL_PATH="):
            ending = b"\r\n" if line.endswith(b"\r\n") else b"\n" if line.endswith(b"\n") else b""
            lines[index] = replacement + ending
            found = True
    data = b"".join(lines)
    if not found:
        data += (b"\n" if data and not data.endswith(b"\n") else b"") + replacement + b"\n"
target.write_bytes(data)
target.chmod(source.stat().st_mode & 0o777)
PY
}

wait_until_stopped() {
  # bootout acknowledges removal asynchronously. Do not activate another release
  # until the old registration, trainer and listener have all disappeared.
  python3 - "$service_target" "$current_release" "$config_file" "${1:-30}" <<'PY'
from pathlib import Path
import subprocess
import sys
import time

target, release, config, budget = sys.argv[1:]
budget = min(30.0, float(budget))
deadline = time.monotonic() + budget

def observe(args):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError()
    return subprocess.run(args, capture_output=True, text=True, timeout=remaining)

try:
    port = "63201"
    for line in Path(config).read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and key == "RISK_LLM_PORT":
            port = value
    if not port.isdecimal() or not 1 <= int(port) <= 65535:
        sys.exit(1)
    current = Path(release)
    known = {str(current / "risk-mlx-trainer.py"),
             str(current.resolve() / "risk-mlx-trainer.py")}
    while time.monotonic() < deadline:
        registered = observe(["launchctl", "print", target]).returncode == 0
        processes = observe(["ps", "-axo", "pid=,ppid=,command="])
        if processes.returncode != 0:
            sys.exit(1)
        trainer_alive = False
        for row in processes.stdout.splitlines():
            parts = row.strip().split(None, 2)
            if len(parts) == 3 and any(f" {path} " in f" {parts[2]} " for path in known):
                trainer_alive = True
        listener = observe(["lsof", f"-tiTCP:{port}", "-sTCP:LISTEN", "-P", "-n"])
        if listener.returncode not in (0, 1) or listener.stderr.strip():
            sys.exit(1)
        if not registered and not trainer_alive and not listener.stdout.strip():
            sys.exit(0)
        time.sleep(min(0.5, budget / 20, max(0, deadline - time.monotonic())))
except (OSError, ValueError, TimeoutError, subprocess.SubprocessError):
    pass
sys.exit(1)
PY
}

wait_until_healthy() {
  # Include the healthcheck execution time in the deadline (curl itself can
  # take four seconds). Suppress all boundary output, including error payloads.
  python3 - "${backend_root}/scripts/healthcheck-risk-training-node-local.sh" "${1:-30}" <<'PY'
import subprocess
import sys
import time

budget = min(30.0, float(sys.argv[2]))
deadline = time.monotonic() + budget
while time.monotonic() < deadline:
    try:
        result = subprocess.run(["bash", sys.argv[1]], stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL,
                                timeout=max(0.001, deadline - time.monotonic()))
        if result.returncode == 0:
            sys.exit(0)
    except (OSError, subprocess.TimeoutExpired):
        pass
    time.sleep(min(0.5, max(0, deadline - time.monotonic()), budget / 4))
sys.exit(1)
PY
}

install_node() {
  require_install_environment
  require_release_sources
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
  local previous backup rollback_healthy=no
  preflight_upgrade || return 1
  previous="$(cd "$current_release" && pwd -P)" || return 1
  # A single retained checkpoint binds the original config bytes/mode to its
  # release. Credentials never go to stdout or through shell evaluation.
  umask 077
  backup="$(mktemp -d "${runtime_home}/upgrade.XXXXXX")" || return 1
  cp -p "$config_file" "$backup/training-node.env" || return 1
  ln -s "$previous" "$backup/release" || return 1
  stage_expert_config "$backup/next.env" || return 1
  if ! stop_node || ! wait_until_stopped; then
    echo "STOP_INCOMPLETE: old target/trainer/port did not stop cleanly; release/config unchanged; upgrade not applied" >&2
    return 1
  fi
  if install_release && mv "$backup/next.env" "$config_file" &&
      launchctl bootstrap "$domain" "$installed_plist" && wait_until_healthy; then
    echo "RISK_TRAINING_NODE_UPGRADE_OK release=$(readlink "$current_release")"
    return 0
  fi
  if stop_node && wait_until_stopped && activate_release "$previous" &&
      cp -p "$backup/training-node.env" "${config_file}.new" &&
      mv "${config_file}.new" "$config_file"; then
    if launchctl bootstrap "$domain" "$installed_plist" && wait_until_healthy; then rollback_healthy=yes; fi
    echo "Training node upgrade failed; previous release/config restored; rollback healthy=$rollback_healthy" >&2
  else
    echo "Training node upgrade failed; rollback restore failed; rollback healthy=no; checkpoint=$backup" >&2
  fi
  return 1
}

start_node() {
  if loaded; then launchctl kickstart "$service_target"; else launchctl bootstrap "$domain" "$installed_plist"; fi
}
stop_node() {
  if loaded; then launchctl bootout "$service_target"; fi
}
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
