#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
workspace_root="$(cd "${backend_root}/.." && pwd)"

overview_frontend_root="${COFCO_ENTERPRISE_FRONTEND_ROOT:-${workspace_root}/cofco-qiqihar-enterprise-frontend}"
business_frontend_root="${COFCO_ENTERPRISE_WEB_ROOT:-${workspace_root}/cofco-qiqihar-enterprise-web}"
runtime_root="${COFCO_ENTERPRISE_LOCAL_RUNTIME_ROOT:-${backend_root}/.local-runtime}"
pid_dir="${runtime_root}/pids"
log_dir="${runtime_root}/logs"
mkdir -p "$pid_dir" "$log_dir"

backend_pid_file="${pid_dir}/backend.pid"
overview_pid_file="${pid_dir}/overview.pid"
business_pid_file="${pid_dir}/business.pid"

backend_log="${log_dir}/backend.log"
overview_log="${log_dir}/overview.log"
business_log="${log_dir}/business.log"

watch_mode=1
if [[ "${1:-}" == "--no-watch" ]]; then
  watch_mode=0
fi

cofco_is_lan_ipv4() {
  local cofco_ip=$1
  if [[ "$cofco_ip" == 10.* ]]; then
    return 0
  fi
  if [[ "$cofco_ip" == 192.168.* ]]; then
    return 0
  fi
  if [[ "$cofco_ip" =~ ^172\.(1[6-9]|2[0-9]|3[0-1])\.[0-9]+\.[0-9]+$ ]]; then
    return 0
  fi
  return 1
}

cofco_is_unusable_ipv4() {
  local cofco_ip=$1
  if [[ -z "$cofco_ip" ]]; then
    return 0
  fi
  if [[ "$cofco_ip" == 127.* || "$cofco_ip" == 169.254.* || "$cofco_ip" == 198.18.* ]]; then
    return 0
  fi
  return 1
}

cofco_collect_local_ipv4_candidates() {
  local cofco_candidate
  for cofco_iface in en0 en1 en2 en3 en4 en5 en6; do
    cofco_candidate="$(ipconfig getifaddr "$cofco_iface" 2>/dev/null || true)"
    if [[ -n "$cofco_candidate" ]]; then
      printf '%s\n' "$cofco_candidate"
    fi
  done
  ifconfig -a | awk 'BEGIN {OFS="";} ($1=="inet" && $2 !~ /^127\\./ && $2 !~ /^169\\.254\\./) {print $2}' \
    | sed -E "s/^([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+).*/\\1/" \
    | while read -r cofco_candidate; do
      printf '%s\n' "$cofco_candidate"
    done
}

cofco_resolve_local_access_host() {
  if [[ -n "${COFCO_ENTERPRISE_ACCESS_HOST:-}" ]]; then
    printf '%s\n' "${COFCO_ENTERPRISE_ACCESS_HOST}"
    return 0
  fi

  local cofco_candidate
  local cofco_preferred=""
  local cofco_fallback=""

  while IFS= read -r cofco_candidate; do
    if cofco_is_unusable_ipv4 "$cofco_candidate"; then
      continue
    fi
    if [[ -z "$cofco_preferred" ]] && cofco_is_lan_ipv4 "$cofco_candidate"; then
      cofco_preferred="$cofco_candidate"
    fi
    if [[ -z "$cofco_fallback" ]]; then
      cofco_fallback="$cofco_candidate"
    fi
    if [[ -n "$cofco_preferred" ]]; then
      break
    fi
  done < <(cofco_collect_local_ipv4_candidates | awk '!seen[$0]++')

  if [[ -n "$cofco_preferred" ]]; then
    printf '%s\n' "$cofco_preferred"
    return 0
  fi
  if [[ -n "$cofco_fallback" ]]; then
    printf '%s\n' "$cofco_fallback"
    return 0
  fi

  printf '127.0.0.1\n'
}

local_access_host="$(cofco_resolve_local_access_host)"

backend_port="${COFCO_ENTERPRISE_BACKEND_PORT:-8090}"
business_port="${COFCO_ENTERPRISE_BUSINESS_PORT:-63182}"
overview_port="${COFCO_ENTERPRISE_OVERVIEW_PORT:-63200}"

for path in "$overview_frontend_root" "$business_frontend_root"; do
  if [[ ! -d "$path" ]]; then
    echo "Frontend directory not found: $path" >&2
    exit 1
  fi
done

if [[ ! -d "$backend_root" ]]; then
  echo "Backend directory not found: $backend_root" >&2
  exit 1
fi

# The backend requires JDK 21. Prefer the installed Homebrew runtime when the
# caller did not provide a compatible JAVA_HOME, rather than silently running
# Maven with the machine's legacy JDK.
if [[ -x "${JAVA_HOME:-}/bin/java" ]] &&
  "${JAVA_HOME}/bin/java" -version 2>&1 | grep -Eq 'version "2[1-9]|version "[3-9][0-9]'; then
  export JAVA_HOME
elif [[ -x /opt/homebrew/opt/openjdk@21/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
else
  echo "JDK 21 is required; set JAVA_HOME to a JDK 21 installation." >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

log() {
  echo "[$(date '+%F %T')] $*"
}

read_pid() {
  local pid_file=$1
  if [[ ! -f "$pid_file" ]]; then
    return 1
  fi

  local pid
  pid="$(cat "$pid_file" | tr -d '[:space:]')"
  if [[ -z "$pid" ]]; then
    rm -f "$pid_file"
    return 1
  fi
  echo "$pid"
}

pid_from_port() {
  local port=$1
  lsof -tiTCP:"$port" -sTCP:LISTEN -P -n 2>/dev/null | head -n 1
}

backend_listener_is_loopback_only() {
  local listener
  local found=0
  while IFS= read -r listener; do
    [[ -z "$listener" ]] && continue
    found=1
    case "$listener" in
      127.*:"$backend_port"|\[::1\]:"$backend_port") ;;
      *)
        log "Refusing backend listener outside loopback: $listener"
        return 1
        ;;
    esac
  done < <(lsof -nP -a -iTCP:"$backend_port" -sTCP:LISTEN -Fn 2>/dev/null | sed -n 's/^n//p')
  [[ "$found" -eq 1 ]]
}

service_state() {
  local pid_file=$1
  local port=$2
  local name=$3
  local probe_url=${4:-}

  local listener_pid
  listener_pid="$(pid_from_port "$port")"
  if [[ -n "${listener_pid:-}" ]]; then
    echo "$listener_pid" > "$pid_file"
    if [[ -n "$probe_url" ]]; then
      if curl -sSf --max-time 2 "$probe_url" >/dev/null 2>&1; then
        log "$name already listening on :$port, attached pid=$listener_pid"
        return 0
      fi

      log "$name port :$port exists but health probe failed ($probe_url), will restart."
      rm -f "$pid_file"
      return 1
    fi

    log "$name already listening on :$port, attached pid=$listener_pid"
    return 0
  fi

  local pid
  pid="$(read_pid "$pid_file")" || return 1
  if kill -0 "$pid" 2>/dev/null; then
    return 2
  fi

  rm -f "$pid_file"
  return 1
}

wait_for_ready() {
  local name=$1
  local url=$2
  local retries=${3:-20}

  for ((i=1; i<=retries; i++)); do
    if curl -sSf --max-time 2 "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  log "$name not ready after ${retries}s. See logs: $4"
  return 1
}

start_service_background() {
  local log_file=$1
  shift
  nohup "$@" </dev/null >> "$log_file" 2>&1 &
}

cleanup() {
  if [[ "$watch_mode" -eq 1 ]]; then
    log "Watch mode exit requested: stopping managed local services."
    "${backend_root}/scripts/stop-local.sh" || true
  fi
}

trap cleanup INT TERM

start_backend() {
  local service_state

  if [[ -n "$(pid_from_port "$backend_port")" ]] && ! backend_listener_is_loopback_only; then
    log "Backend must listen only on 127.0.0.1; existing process was left untouched."
    return 1
  fi

  if service_state "$backend_pid_file" "$backend_port" "backend" "http://127.0.0.1:${backend_port}/actuator/health"; then
    return
  else
    service_state=$?
    if [[ "$service_state" -eq 2 ]]; then
      return
    fi
  fi

  log "start: backend"
  cd "$backend_root"
  start_service_background \
    "$backend_log" \
    env \
    "QIQIHAR_SERVER_PORT=$backend_port" \
    mvn \
    spring-boot:run \
    -Dspring-boot.run.profiles=local
  backend_pid=$!
  echo "$backend_pid" > "$backend_pid_file"
  wait_for_ready "backend" "http://127.0.0.1:${backend_port}/actuator/health" 30 "$backend_log" || true
  if ! backend_listener_is_loopback_only; then
    log "Backend listener verification failed; process was left untouched."
    return 1
  fi
  cd - >/dev/null
}

start_overview() {
  local service_state

  if service_state "$overview_pid_file" "$overview_port" "overview frontend" "http://127.0.0.1:${overview_port}/"; then
    return
  else
    service_state=$?
    if [[ "$service_state" -eq 2 ]]; then
      return
    fi
  fi

  log "start: overview frontend"
  cd "$overview_frontend_root"
  start_service_background \
    "$overview_log" \
    env \
    VITE_BUSINESS_PLATFORM_HOST="$local_access_host" \
    VITE_BUSINESS_PLATFORM_PORT="$business_port" \
    npm \
    run \
    dev \
    -- --host \
    0.0.0.0 \
    --port \
    "$overview_port" \
    --strictPort
  overview_pid=$!
  echo "$overview_pid" > "$overview_pid_file"
  wait_for_ready "overview frontend" "http://127.0.0.1:${overview_port}/" 30 "$overview_log" || true
  cd - >/dev/null
}

start_business() {
  local service_state

  if service_state "$business_pid_file" "$business_port" "business frontend" "http://127.0.0.1:${business_port}/prototype.html"; then
    return
  else
    service_state=$?
    if [[ "$service_state" -eq 2 ]]; then
      return
    fi
  fi

  log "start: business frontend"
  cd "$business_frontend_root"
  start_service_background \
    "$business_log" \
    env \
    VITE_OVERVIEW_MAP_HOST="$local_access_host" \
    VITE_OVERVIEW_MAP_PORT="$overview_port" \
    npm \
    run \
    prototype \
    -- --host \
    0.0.0.0 \
    --port \
    "$business_port" \
    --strictPort
  business_pid=$!
  echo "$business_pid" > "$business_pid_file"
  wait_for_ready "business frontend" "http://127.0.0.1:${business_port}/prototype.html" 30 "$business_log" || true
  cd - >/dev/null
}

start_backend
start_overview
start_business

log "Started services."
log "Business: http://$local_access_host:${business_port}/prototype.html"
if [[ "$local_access_host" != "127.0.0.1" ]]; then
  log "Business（本机）: http://127.0.0.1:${business_port}/prototype.html"
fi
log "Overview: http://$local_access_host:${overview_port}/"
if [[ "$local_access_host" != "127.0.0.1" ]]; then
  log "Overview（本机）: http://127.0.0.1:${overview_port}/"
fi
log "Backend（仅本机）:  http://127.0.0.1:${backend_port}/actuator/health"
log "Logs: $log_dir"

if [[ $watch_mode -ne 1 ]]; then
  exit 0
fi

log "Watch mode enabled. Keep this process running to auto-restart if any service exits."
while true; do
  start_backend
  start_overview
  start_business
  sleep 5
done
