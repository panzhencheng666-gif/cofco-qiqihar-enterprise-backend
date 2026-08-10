#!/usr/bin/env bash
set -euo pipefail

launcher="${COFCO_ENTERPRISE_DESKTOP_LAUNCHER:-/Users/federal/Desktop/启动齐齐哈尔粮食商情系统.command}"

if [[ ! -f "$launcher" ]]; then
  echo "[FAIL] desktop launcher not found: $launcher" >&2
  exit 1
fi

if grep -Fq "cofco-qiqihar-dashboard" "$launcher"; then
  echo "[FAIL] desktop launcher still references the legacy dashboard project" >&2
  exit 1
fi

required_fragments=(
  "/Users/federal/Desktop/cofco-qiqihar-enterprise-backend"
  "scripts/local-runtime.sh"
  "install"
  "scripts/verify-local-region-hierarchy.sh"
  "http://127.0.0.1:63182/"
  "http://127.0.0.1:63200/"
  "http://127.0.0.1:8090/actuator/health"
  "com.cofco.qiqihar.enterprise.local-stack"
)

for fragment in "${required_fragments[@]}"; do
  if ! grep -Fq "$fragment" "$launcher"; then
    echo "[FAIL] desktop launcher is missing required fragment: $fragment" >&2
    exit 1
  fi
done

if grep -Fq "/usr/bin/screen" "$launcher"; then
  echo "[FAIL] desktop launcher still delegates lifecycle ownership to screen" >&2
  exit 1
fi

if [[ ! -x "$launcher" ]]; then
  echo "[FAIL] desktop launcher is not executable: $launcher" >&2
  exit 1
fi

echo "[OK] desktop launcher targets only the enterprise local stack"
