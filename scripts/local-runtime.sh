#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_workspace_root="$(cd "${backend_root}/.." && pwd)"
runtime_home="${HOME}/Library/Application Support/COFCO Qiqihar Enterprise"
snapshot_workspace="${runtime_home}/runtime"
runtime_root="${runtime_home}/state"
label="com.cofco.qiqihar.enterprise.local-stack"
domain="gui/$(id -u)"
service_target="${domain}/${label}"
source_plist="${backend_root}/ops/launchd/${label}.plist"
installed_plist="${HOME}/Library/LaunchAgents/${label}.plist"
launchd_log_dir="${HOME}/Library/Logs/COFCO Qiqihar Enterprise"
source "${backend_root}/scripts/local-process-ownership.sh"

backend_port="${COFCO_ENTERPRISE_BACKEND_PORT:-8090}"
business_port="${COFCO_ENTERPRISE_BUSINESS_PORT:-63182}"
overview_port="${COFCO_ENTERPRISE_OVERVIEW_PORT:-63200}"

usage() {
  echo "Usage: $0 {install|uninstall|start|stop|restart|status}"
}

agent_is_loaded() {
  launchctl print "$service_target" >/dev/null 2>&1
}

agent_is_disabled() {
  launchctl print-disabled "$domain" 2>/dev/null | grep -Fq \"${label}\"' => true'
}

remove_runtime_cache() {
  local target=$1
  case "$target" in
    "${runtime_home}"/.runtime-install.* | "${runtime_home}"/runtime.previous.*)
      rm -rf -- "$target"
      ;;
    *)
      echo "Refusing to remove unexpected runtime cache path: $target" >&2
      return 1
      ;;
  esac
}

refresh_runtime_snapshot() {
  local temporary_root
  local temporary_workspace
  local previous_workspace=""
  local repository
  local source_repository

  mkdir -p "$runtime_home"
  temporary_root="$(mktemp -d "${runtime_home}/.runtime-install.XXXXXX")"
  temporary_workspace="${temporary_root}/runtime"
  mkdir -p "$temporary_workspace"

  for repository in \
    cofco-qiqihar-enterprise-backend \
    cofco-qiqihar-enterprise-web \
    cofco-qiqihar-enterprise-frontend; do
    source_repository="${source_workspace_root}/${repository}"
    if [[ ! -d "$source_repository" ]]; then
      remove_runtime_cache "$temporary_root"
      echo "Required source repository is missing: $source_repository" >&2
      return 1
    fi
    /bin/cp -cR "$source_repository" "${temporary_workspace}/${repository}"
  done

  if [[ -d "$snapshot_workspace" ]]; then
    previous_workspace="${runtime_home}/runtime.previous.$$"
    /bin/mv "$snapshot_workspace" "$previous_workspace"
  fi
  /bin/mv "$temporary_workspace" "$snapshot_workspace"
  /bin/rmdir "$temporary_root"
  if [[ -n "$previous_workspace" ]]; then
    remove_runtime_cache "$previous_workspace"
  fi
  echo "Refreshed launchd-readable runtime snapshot: $snapshot_workspace"
}

wait_for_stack() {
  local retries=${1:-90}
  local attempt

  for ((attempt=1; attempt<=retries; attempt++)); do
    if owned_service_is_ready \
      "backend" "${runtime_root}/pids/backend.pid" "$backend_port" \
      "http://127.0.0.1:${backend_port}/actuator/health" &&
      owned_service_is_ready \
        "business frontend" "${runtime_root}/pids/business.pid" "$business_port" \
        "http://127.0.0.1:${business_port}/" &&
      owned_service_is_ready \
        "overview frontend" "${runtime_root}/pids/overview.pid" "$overview_port" \
        "http://127.0.0.1:${overview_port}/"; then
      return 0
    fi
    sleep 1
  done
  echo "Enterprise local stack did not become healthy within ${retries}s." >&2
  echo "Inspect logs under: ${runtime_root}/logs and ${launchd_log_dir}" >&2
  return 1
}

owned_service_is_ready() {
  local name=$1
  local pid_file=$2
  local port=$3
  local url=$4
  local listener_pid

  listener_pid="$(pid_listening_on_port "$port" || true)"
  [[ -n "$listener_pid" ]] &&
    owned_listener_matches_port_and_service "$pid_file" "$listener_pid" "$port" "$name" &&
    curl -fsS --max-time 2 "$url" >/dev/null 2>&1
}

wait_for_ports_released() {
  local attempt
  for ((attempt=1; attempt<=40; attempt++)); do
    if [[ -z "$(pid_listening_on_port "$backend_port")" &&
      -z "$(pid_listening_on_port "$business_port")" &&
      -z "$(pid_listening_on_port "$overview_port")" ]]; then
      return 0
    fi
    sleep 0.25
  done
  echo "One or more formal local ports remained occupied after the LaunchAgent stopped." >&2
  return 1
}

install_agent() {
  [[ -f "$source_plist" ]] || {
    echo "LaunchAgent source plist not found: $source_plist" >&2
    return 1
  }
  plutil -lint "$source_plist" >/dev/null
  mkdir -p "${HOME}/Library/LaunchAgents" "$launchd_log_dir" "${runtime_root}/logs" "${runtime_root}/pids"
  chmod 700 "$launchd_log_dir" "${runtime_root}/logs" "${runtime_root}/pids"

  if agent_is_loaded; then
    launchctl bootout "$service_target"
    wait_for_ports_released
  fi
  refresh_runtime_snapshot
  install -m 600 "$source_plist" "$installed_plist"

  launchctl enable "$service_target"
  launchctl bootstrap "$domain" "$installed_plist"
  wait_for_stack 90
  status_agent
}

start_agent() {
  [[ -f "$installed_plist" ]] || {
    echo "LaunchAgent is not installed. Run: $0 install" >&2
    return 1
  }
  mkdir -p "$launchd_log_dir" "${runtime_root}/logs" "${runtime_root}/pids"
  launchctl enable "$service_target"
  if agent_is_loaded; then
    launchctl kickstart "$service_target"
  else
    launchctl bootstrap "$domain" "$installed_plist"
  fi
  wait_for_stack 90
  status_agent
}

stop_agent() {
  if agent_is_loaded; then
    launchctl bootout "$service_target"
  fi
  wait_for_ports_released
  echo "LaunchAgent stopped; plist remains installed for the next start/login."
}

restart_agent() {
  if agent_is_loaded; then
    launchctl kickstart -k "$service_target"
  else
    start_agent
    return
  fi
  wait_for_stack 90
  status_agent
}

uninstall_agent() {
  stop_agent
  if [[ -f "$installed_plist" ]]; then
    rm -f "$installed_plist"
  fi
  echo "LaunchAgent uninstalled: $installed_plist"
}

http_code() {
  curl -sS --location --max-redirs 3 --max-time 3 \
    -o /dev/null -w '%{http_code}' "$1" 2>/dev/null || true
}

print_service_status() {
  local name=$1
  local pid_file=$2
  local port=$3
  local url=$4
  local listener_pid
  local ownership="unowned"
  local code

  listener_pid="$(pid_listening_on_port "$port")"
  code="$(http_code "$url")"
  if [[ -n "$listener_pid" ]] && load_owned_process "$pid_file" &&
    [[ "$COFCO_OWNED_LISTENER_PID" == "$listener_pid" ]] &&
    process_matches_identity "$listener_pid" "$COFCO_OWNED_LISTENER_IDENTITY"; then
    ownership="owned root=${COFCO_OWNED_ROOT_PID}"
  fi
  printf '%-10s pid=%-7s port=%-5s http=%-3s ownership=%s\n' \
    "$name" "${listener_pid:--}" "$port" "${code:-000}" "$ownership"

  [[ -n "$listener_pid" && "$ownership" == owned* && "$code" == "200" ]]
}

status_agent() {
  local installed="no"
  local loaded="no"
  local enabled="yes"
  local supervisor_pid="-"
  local ok=1
  local launchd_dump=""

  [[ -f "$installed_plist" ]] && installed="yes"
  if agent_is_disabled; then
    enabled="no"
  fi
  if agent_is_loaded; then
    loaded="yes"
    launchd_dump="$(launchctl print "$service_target")"
    supervisor_pid="$(awk '$1 == "pid" && $2 == "=" {print $3; exit}' <<< "$launchd_dump")"
  fi

  echo "LaunchAgent label=$label installed=$installed loaded=$loaded enabled=$enabled supervisor_pid=${supervisor_pid:--}"
  print_service_status "backend" "${runtime_root}/pids/backend.pid" "$backend_port" \
    "http://127.0.0.1:${backend_port}/actuator/health" || ok=0
  print_service_status "business" "${runtime_root}/pids/business.pid" "$business_port" \
    "http://127.0.0.1:${business_port}/" || ok=0
  print_service_status "overview" "${runtime_root}/pids/overview.pid" "$overview_port" \
    "http://127.0.0.1:${overview_port}/" || ok=0
  echo "Logs: ${runtime_root}/logs and ${launchd_log_dir}"

  [[ "$installed" == "yes" && "$loaded" == "yes" && "$enabled" == "yes" && "$ok" -eq 1 ]]
}

command_name="${1:-}"
case "$command_name" in
  install) install_agent ;;
  uninstall) uninstall_agent ;;
  start) start_agent ;;
  stop) stop_agent ;;
  restart) restart_agent ;;
  status) status_agent ;;
  *) usage; exit 2 ;;
esac
