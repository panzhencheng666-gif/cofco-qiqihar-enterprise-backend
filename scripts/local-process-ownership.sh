#!/usr/bin/env bash
set -euo pipefail

cofco_process_identity() {
  local pid=$1
  ps -p "$pid" -o lstart= 2>/dev/null | sed -e 's/^[[:space:]]*//'
}

process_matches_identity() {
  local pid=$1
  local expected_identity=$2
  local actual_identity

  [[ "$pid" =~ ^[0-9]+$ && -n "$expected_identity" ]] || return 1
  actual_identity="$(cofco_process_identity "$pid")"
  [[ -n "$actual_identity" && "$actual_identity" == "$expected_identity" ]] && kill -0 "$pid" 2>/dev/null
}

process_is_same_or_descendant() {
  local owned_pid=$1
  local candidate_pid=$2
  local current_pid=$candidate_pid
  local parent_pid

  for _ in {1..64}; do
    if [[ "$current_pid" == "$owned_pid" ]]; then
      return 0
    fi
    parent_pid="$(ps -p "$current_pid" -o ppid= 2>/dev/null | tr -d '[:space:]' || true)"
    if [[ ! "$parent_pid" =~ ^[0-9]+$ || "$parent_pid" -le 1 || "$parent_pid" == "$current_pid" ]]; then
      return 1
    fi
    current_pid=$parent_pid
  done
  return 1
}

pid_listening_on_port() {
  local port=$1
  lsof -tiTCP:"$port" -sTCP:LISTEN -P -n 2>/dev/null | head -n 1
}

port_is_released() {
  local port=$1
  [[ -z "$(pid_listening_on_port "$port")" ]]
}

record_owned_process() {
  local pid_file=$1
  local root_pid=$2
  local listener_pid=$3
  local port=$4
  local service=$5
  local root_identity
  local listener_identity
  local temporary_pid_file="${pid_file}.new.$$"

  [[ "$root_pid" =~ ^[0-9]+$ && "$listener_pid" =~ ^[0-9]+$ && "$port" =~ ^[1-9][0-9]*$ && -n "$service" ]] || return 1
  root_identity="$(cofco_process_identity "$root_pid")"
  listener_identity="$(cofco_process_identity "$listener_pid")"
  if [[ -z "$root_identity" || -z "$listener_identity" ]] ||
      ! process_matches_identity "$root_pid" "$root_identity" ||
      ! process_matches_identity "$listener_pid" "$listener_identity" ||
      ! process_is_same_or_descendant "$root_pid" "$listener_pid"; then
    rm -f "$temporary_pid_file"
    return 1
  fi

  umask 077
  {
    printf 'owned-v2\n'
    printf '%s\n' "$root_pid"
    printf '%s\n' "$root_identity"
    printf '%s\n' "$listener_pid"
    printf '%s\n' "$listener_identity"
    printf '%s\n' "$port"
    printf '%s\n' "$service"
  } > "$temporary_pid_file"
  mv "$temporary_pid_file" "$pid_file"
}

load_owned_process() {
  local pid_file=$1
  local line_count
  local marker
  local root_pid
  local root_identity
  local listener_pid
  local listener_identity
  local port
  local service

  COFCO_OWNED_ROOT_PID=""
  COFCO_OWNED_ROOT_IDENTITY=""
  COFCO_OWNED_LISTENER_PID=""
  COFCO_OWNED_LISTENER_IDENTITY=""
  COFCO_OWNED_PORT=""
  COFCO_OWNED_SERVICE=""
  [[ -f "$pid_file" ]] || return 1
  line_count="$(wc -l < "$pid_file" | tr -d '[:space:]')"
  marker="$(sed -n '1p' "$pid_file")"
  root_pid="$(sed -n '2p' "$pid_file")"
  root_identity="$(sed -n '3p' "$pid_file")"
  listener_pid="$(sed -n '4p' "$pid_file")"
  listener_identity="$(sed -n '5p' "$pid_file")"
  port="$(sed -n '6p' "$pid_file")"
  service="$(sed -n '7p' "$pid_file")"
  if [[ "$line_count" != 7 || "$marker" != "owned-v2" ||
      ! "$root_pid" =~ ^[0-9]+$ || -z "$root_identity" ||
      ! "$listener_pid" =~ ^[0-9]+$ || -z "$listener_identity" ||
      ! "$port" =~ ^[1-9][0-9]*$ || -z "$service" ]]; then
    echo "[$(date '+%F %T')] ownership record is malformed or legacy; leaving it untouched." >&2
    return 1
  fi
  COFCO_OWNED_ROOT_PID="$root_pid"
  COFCO_OWNED_ROOT_IDENTITY="$root_identity"
  COFCO_OWNED_LISTENER_PID="$listener_pid"
  COFCO_OWNED_LISTENER_IDENTITY="$listener_identity"
  COFCO_OWNED_PORT="$port"
  COFCO_OWNED_SERVICE="$service"
}

owned_listener_is_running() {
  local pid_file=$1
  load_owned_process "$pid_file" &&
      process_matches_identity "$COFCO_OWNED_LISTENER_PID" "$COFCO_OWNED_LISTENER_IDENTITY"
}

owned_listener_matches_port_and_service() {
  local pid_file=$1
  local listener_pid=$2
  local port=$3
  local service=$4
  load_owned_process "$pid_file" &&
      [[ "$COFCO_OWNED_PORT" == "$port" && "$COFCO_OWNED_SERVICE" == "$service" &&
          "$COFCO_OWNED_LISTENER_PID" == "$listener_pid" ]] &&
      process_matches_identity "$COFCO_OWNED_LISTENER_PID" "$COFCO_OWNED_LISTENER_IDENTITY"
}

reconcile_stale_owned_process() {
  local pid_file=$1
  local name=$2
  local listener_pid_on_port

  [[ -f "$pid_file" ]] || return 0
  if ! load_owned_process "$pid_file"; then
    echo "[$(date '+%F %T')] $name: refusing to reconcile a malformed or legacy ownership record." >&2
    return 1
  fi
  if [[ "$COFCO_OWNED_SERVICE" != "$name" ]]; then
    echo "[$(date '+%F %T')] $name: ownership record service mismatch; leaving it untouched." >&2
    return 1
  fi

  listener_pid_on_port="$(pid_listening_on_port "$COFCO_OWNED_PORT" || true)"
  if [[ -n "$listener_pid_on_port" ]]; then
    echo "[$(date '+%F %T')] $name: port $COFCO_OWNED_PORT became occupied by pid=$listener_pid_on_port; leaving it untouched." >&2
    return 1
  fi

  # stop_owned_process validates both recorded start times before signalling.
  # If both identities are already gone it only removes the now-proven-stale
  # record; if the root wrapper survived its listener, it stops that exact root.
  stop_owned_process "$pid_file" "$name"
}

stop_owned_process() {
  local pid_file=$1
  local name=$2
  local root_alive=0
  local listener_alive=0
  local listener_pid_on_port

  [[ -f "$pid_file" ]] || {
    echo "[$(date '+%F %T')] $name: no owned process"
    return 0
  }
  if ! load_owned_process "$pid_file"; then
    echo "[$(date '+%F %T')] $name: refusing to act on malformed or legacy ownership record." >&2
    return 1
  fi
  if [[ "$COFCO_OWNED_SERVICE" != "$name" ]]; then
    echo "[$(date '+%F %T')] $name: ownership record service mismatch; leaving it untouched." >&2
    return 1
  fi

  if process_matches_identity "$COFCO_OWNED_ROOT_PID" "$COFCO_OWNED_ROOT_IDENTITY"; then
    root_alive=1
  fi
  if process_matches_identity "$COFCO_OWNED_LISTENER_PID" "$COFCO_OWNED_LISTENER_IDENTITY"; then
    listener_alive=1
  fi
  listener_pid_on_port="$(pid_listening_on_port "$COFCO_OWNED_PORT" || true)"
  if [[ -n "$listener_pid_on_port" && "$listener_pid_on_port" != "$COFCO_OWNED_LISTENER_PID" ]]; then
    echo "[$(date '+%F %T')] $name: port $COFCO_OWNED_PORT is occupied by unrecorded pid=$listener_pid_on_port; leaving record and process untouched." >&2
    return 1
  fi
  if [[ "$listener_alive" -ne 1 && -n "$listener_pid_on_port" ]]; then
    echo "[$(date '+%F %T')] $name: recorded listener identity no longer matches; leaving record and process untouched." >&2
    return 1
  fi

  if [[ "$listener_alive" -eq 1 ]]; then
    echo "[$(date '+%F %T')] $name: stopping owned listener pid=$COFCO_OWNED_LISTENER_PID"
    kill -TERM "$COFCO_OWNED_LISTENER_PID" 2>/dev/null || true
  fi
  if [[ "$root_alive" -eq 1 && "$COFCO_OWNED_ROOT_PID" != "$COFCO_OWNED_LISTENER_PID" ]]; then
    echo "[$(date '+%F %T')] $name: stopping owned root pid=$COFCO_OWNED_ROOT_PID"
    kill -TERM "$COFCO_OWNED_ROOT_PID" 2>/dev/null || true
  fi

  for _ in {1..20}; do
    root_alive=0
    listener_alive=0
    process_matches_identity "$COFCO_OWNED_ROOT_PID" "$COFCO_OWNED_ROOT_IDENTITY" && root_alive=1
    process_matches_identity "$COFCO_OWNED_LISTENER_PID" "$COFCO_OWNED_LISTENER_IDENTITY" && listener_alive=1
    if [[ "$root_alive" -eq 0 && "$listener_alive" -eq 0 ]] && port_is_released "$COFCO_OWNED_PORT"; then
      rm -f "$pid_file"
      return 0
    fi
    sleep 0.25
  done
  echo "[$(date '+%F %T')] $name: SIGTERM did not release every recorded process and port $COFCO_OWNED_PORT; retaining record." >&2
  return 1
}
