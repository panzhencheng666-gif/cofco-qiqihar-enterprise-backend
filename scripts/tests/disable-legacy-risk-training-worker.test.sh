#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="${backend_root}/scripts/disable-legacy-risk-training-worker.sh"
launcher="${backend_root}/scripts/run-local-launch-agent.sh"
grep -Fq "QIQIHAR_RISK_TRAINING_ENABLED=false" "$script"
grep -Fq "stat -f '%Lp'" "$script"
grep -Fq "awk '!/^QIQIHAR_RISK_TRAINING_ENABLED=/'" "$script"
grep -Fq "QIQIHAR_RISK_TRAINING_ENABLED" "$launcher"
if grep -Eq 'PASSWORD=|SECRET=|TOKEN=' "$script"; then
  echo "Legacy worker script must not contain secrets" >&2
  exit 1
fi
echo "LEGACY_RISK_WORKER_CUTOVER_CONTRACT_OK"
