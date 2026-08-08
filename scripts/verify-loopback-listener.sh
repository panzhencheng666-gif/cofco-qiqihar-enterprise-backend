#!/usr/bin/env bash
set -euo pipefail

require_loopback_listener() {
  local port=$1
  local name=$2
  local listener
  local found=0

  while IFS= read -r listener; do
    [[ -z "$listener" ]] && continue
    found=1
    case "$listener" in
      127.*:"$port"|\[::1\]:"$port") ;;
      *)
        echo "Refusing $name listener outside numeric loopback: $listener" >&2
        return 1
        ;;
    esac
  done < <(lsof -nP -a -iTCP:"$port" -sTCP:LISTEN -Fn 2>/dev/null | sed -n 's/^n//p')

  if [[ "$found" -ne 1 ]]; then
    echo "No $name listener found on port $port" >&2
    return 1
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <port> <service-name>" >&2
    exit 2
  fi
  require_loopback_listener "$1" "$2"
fi
