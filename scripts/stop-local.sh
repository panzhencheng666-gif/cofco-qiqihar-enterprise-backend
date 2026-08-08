#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_root="${COFCO_ENTERPRISE_LOCAL_RUNTIME_ROOT:-${backend_root}/.local-runtime}"
pid_dir="${runtime_root}/pids"
source "${backend_root}/scripts/local-process-ownership.sh"

all_stopped=1
for service in \
  "${pid_dir}/backend.pid|backend" \
  "${pid_dir}/overview.pid|overview frontend" \
  "${pid_dir}/business.pid|business frontend"; do
  IFS='|' read -r pid_file name <<< "$service"
  if ! stop_owned_process "$pid_file" "$name"; then
    all_stopped=0
  fi
done

if [[ "$all_stopped" -ne 1 ]]; then
  echo "One or more owned services did not stop cleanly; their ownership records were retained." >&2
  exit 1
fi
