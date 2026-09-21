#!/usr/bin/env bash
set -euo pipefail

config_file="${HOME}/.config/cofco-qiqihar-risk-intelligence/local-runtime.env"
port="${RISK_SERVER_PORT:-63184}"
expected_database="${RISK_EXPECTED_DATABASE:-}"

if [[ -f "$config_file" ]]; then
  mode="$(stat -f '%Lp' "$config_file")"
  if (( (8#$mode & 077) != 0 )); then
    echo "Unsafe risk runtime config mode: $mode" >&2
    exit 1
  fi
  while IFS='=' read -r key value; do
    case "$key" in
      RISK_SERVER_PORT) port=$value ;;
      RISK_EXPECTED_DATABASE) expected_database=$value ;;
    esac
  done < "$config_file"
fi

health_url="http://127.0.0.1:${port}/actuator/health"
boundary_url="http://127.0.0.1:${port}/api/v1/risk-intelligence/operations/boundary"
health_json="$(curl -fsS --max-time 4 "$health_url")"
python3 -c 'import json,sys; assert json.load(sys.stdin).get("status") == "UP"' <<< "$health_json"
echo "RISK_INTELLIGENCE_HEALTH_OK url=${health_url}"

boundary_json="$(curl -fsS --max-time 4 "$boundary_url")"
result="$(python3 -c '
import json
import sys
expected = sys.argv[1]
payload = json.load(sys.stdin)
if expected and payload.get("databaseName") != expected:
    raise SystemExit("unexpected risk database")
if payload.get("writableSchemas") != ["risk"]:
    raise SystemExit("risk writable schema boundary failed")
print("database={} user={} writableSchemas=risk".format(
    payload["databaseName"], payload["databaseUser"]))
' "$expected_database" <<< "$boundary_json")"

listener="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
grep -Fq "127.0.0.1:${port}" <<< "$listener" || {
  echo "Risk intelligence is not bound to numeric loopback" >&2
  exit 1
}
echo "RISK_DATABASE_BOUNDARY_OK ${result}"
