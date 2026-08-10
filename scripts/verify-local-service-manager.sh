#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manager="${backend_root}/scripts/local-runtime.sh"
label="com.cofco.qiqihar.enterprise.local-stack"
domain="gui/$(id -u)"
installed_plist="${HOME}/Library/LaunchAgents/${label}.plist"
runtime_home="${HOME}/Library/Application Support/COFCO Qiqihar Enterprise"
runtime_backend="${runtime_home}/runtime/cofco-qiqihar-enterprise-backend"
state_root="${runtime_home}/state"

fail() {
  echo "[FAIL] $*" >&2
  exit 1
}

[[ -x "$manager" ]] || fail "local runtime manager is not executable: $manager"
[[ -f "$installed_plist" ]] || fail "LaunchAgent is not installed: $installed_plist"

plutil -lint "$installed_plist" >/dev/null || fail "installed LaunchAgent plist is invalid"
[[ "$(plutil -extract RunAtLoad raw -o - "$installed_plist")" == "true" ]] ||
  fail "LaunchAgent must enable RunAtLoad"
[[ "$(plutil -extract KeepAlive raw -o - "$installed_plist")" == "true" ]] ||
  fail "LaunchAgent must enable KeepAlive"

plist_dump="$(plutil -p "$installed_plist")"
for required in \
  "$runtime_backend" \
  "scripts/start-local.sh" \
  "ThrottleInterval" \
  "StandardOutPath" \
  "StandardErrorPath" \
  "JAVA_HOME" \
  "PATH"; do
  [[ "$plist_dump" == *"$required"* ]] || fail "LaunchAgent is missing: $required"
done
[[ "$plist_dump" != *"/Users/federal/Desktop/"* ]] ||
  fail "LaunchAgent must not depend on macOS-protected Desktop paths"

launchctl print "${domain}/${label}" >/dev/null 2>&1 ||
  fail "LaunchAgent is not registered in ${domain}"

disabled_dump="$(launchctl print-disabled "$domain" 2>/dev/null || true)"
[[ "$disabled_dump" != *\"${label}\"' => true'* ]] ||
  fail "LaunchAgent is registered but disabled"

"$manager" status
"${backend_root}/scripts/healthcheck-local.sh"

for service in backend business overview; do
  for stream in stdout stderr; do
    log_file="${state_root}/logs/${service}.${stream}.log"
    [[ -f "$log_file" ]] || fail "component log is missing: $log_file"
  done
done

echo "[OK] launchd owns a healthy, persistent enterprise local stack"
