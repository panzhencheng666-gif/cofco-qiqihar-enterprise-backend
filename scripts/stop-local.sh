#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_root="${COFCO_ENTERPRISE_LOCAL_RUNTIME_ROOT:-${backend_root}/.local-runtime}"
pid_dir="${runtime_root}/pids"
source "${backend_root}/scripts/local-process-ownership.sh"

stop_owned_process "${pid_dir}/backend.pid" "backend"
stop_owned_process "${pid_dir}/overview.pid" "overview frontend"
stop_owned_process "${pid_dir}/business.pid" "business frontend"
