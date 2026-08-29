#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${backend_root}/scripts/jdk21-env.sh"

exec mvn "$@"
