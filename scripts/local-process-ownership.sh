#!/usr/bin/env bash
set -euo pipefail

cofco_process_identity() {
  local pid=$1
  ps -p "$pid" -o lstart= 2>/dev/null | sed -e 's/^[[:space:]]*//'
}

record_owned_process() {
  local pid_file=$1
  local pid=$2
  local identity
  local temporary_pid_file="${pid_file}.new.$$"

  identity="$(cofco_process_identity "$pid")"
  if [[ -z "$identity" ]] || ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$temporary_pid_file" "$pid_file"
    return 1
  fi
  {
    printf 'owned-v1\n'
    printf '%s\n' "$pid"
    printf '%s\n' "$identity"
  } > "$temporary_pid_file"
  mv "$temporary_pid_file" "$pid_file"
}

load_owned_process() {
  local pid_file=$1
  local marker
  local pid
  local expected_identity
  local actual_identity

  COFCO_OWNED_PID=""
  COFCO_OWNED_IDENTITY=""
  [[ -f "$pid_file" ]] || return 1
  marker="$(sed -n '1p' "$pid_file")"
  pid="$(sed -n '2p' "$pid_file")"
  expected_identity="$(sed -n '3p' "$pid_file")"
  if [[ "$marker" != "owned-v1" || ! "$pid" =~ ^[0-9]+$ || -z "$expected_identity" ]]; then
    rm -f "$pid_file"
    return 1
  fi
  actual_identity="$(cofco_process_identity "$pid")"
  if [[ -z "$actual_identity" || "$actual_identity" != "$expected_identity" ]] ||
      ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$pid_file"
    return 1
  fi
  COFCO_OWNED_PID="$pid"
  COFCO_OWNED_IDENTITY="$expected_identity"
}

owned_process_is_running() {
  load_owned_process "$1"
}

stop_owned_process() {
  local pid_file=$1
  local name=$2
  local pid
  local identity

  if ! load_owned_process "$pid_file"; then
    echo "[$(date '+%F %T')] $name: no owned process"
    return 0
  fi
  pid="$COFCO_OWNED_PID"
  identity="$COFCO_OWNED_IDENTITY"
  echo "[$(date '+%F %T')] $name: stopping owned pid=$pid"
  kill "$pid" 2>/dev/null || true
  for _ in {1..20}; do
    if [[ "$(cofco_process_identity "$pid")" == "$identity" ]] && kill -0 "$pid" 2>/dev/null; then
      sleep 0.25
    else
      break
    fi
  done
  if [[ "$(cofco_process_identity "$pid")" == "$identity" ]] && kill -0 "$pid" 2>/dev/null; then
    echo "[$(date '+%F %T')] $name: forcing owned pid=$pid"
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$pid_file"
}
