#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/cofco-runtime-rollback.XXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT

source_root="${test_root}/source"
runtime_home="${test_root}/runtime-home"
export COFCO_ENTERPRISE_RUNTIME_HOME="$runtime_home"
export COFCO_ENTERPRISE_LAUNCH_AGENTS_DIR="${test_root}/agents"
export COFCO_ENTERPRISE_LOG_DIR="${test_root}/logs"
export COFCO_TEST_LOADED="${test_root}/agent-loaded"
export COFCO_TEST_RUNTIME="$runtime_home/runtime"

for repository in \
  cofco-qiqihar-enterprise-backend \
  cofco-qiqihar-enterprise-web \
  cofco-qiqihar-enterprise-frontend; do
  mkdir -p "${source_root}/${repository}" "${COFCO_TEST_RUNTIME}/${repository}"
  git -C "${source_root}/${repository}" init -q
done

source_backend="${source_root}/cofco-qiqihar-enterprise-backend"
mkdir -p "${source_backend}/scripts" "${source_backend}/ops/launchd" "$COFCO_ENTERPRISE_LAUNCH_AGENTS_DIR"
cp "${backend_root}/scripts/local-runtime.sh" "${source_backend}/scripts/local-runtime.sh"
cat > "${source_backend}/scripts/local-process-ownership.sh" <<'MOCK'
pid_listening_on_port() { [[ -f "$COFCO_TEST_LOADED" ]] && printf '123'; }
owned_listener_matches_port_and_service() { return 0; }
MOCK
printf 'new\n' > "${source_backend}/version"
printf 'old\n' > "${COFCO_TEST_RUNTIME}/cofco-qiqihar-enterprise-backend/version"
printf 'new plist\n' > "${source_backend}/ops/launchd/com.cofco.qiqihar.enterprise.local-stack.plist"
printf 'old plist\n' > "${COFCO_ENTERPRISE_LAUNCH_AGENTS_DIR}/com.cofco.qiqihar.enterprise.local-stack.plist"
touch "$COFCO_TEST_LOADED"

launchctl() {
  case "$1" in
    print) [[ -f "$COFCO_TEST_LOADED" ]] ;;
    bootout) rm -f "$COFCO_TEST_LOADED" ;;
    bootstrap) touch "$COFCO_TEST_LOADED" ;;
    enable) return 0 ;;
    *) return 1 ;;
  esac
}
plutil() { return 0; }
curl() { [[ "$(cat "${COFCO_TEST_RUNTIME}/cofco-qiqihar-enterprise-backend/version")" == old ]]; }
sleep() { return 0; }
export -f launchctl plutil curl sleep

if bash "${source_backend}/scripts/local-runtime.sh" install >"${test_root}/install.log" 2>&1; then
  cat "${test_root}/install.log" >&2
  echo "Expected the candidate health check to fail" >&2
  exit 1
fi

[[ "$(cat "${COFCO_TEST_RUNTIME}/cofco-qiqihar-enterprise-backend/version")" == old ]]
[[ "$(cat "${COFCO_ENTERPRISE_LAUNCH_AGENTS_DIR}/com.cofco.qiqihar.enterprise.local-stack.plist")" == 'old plist' ]]
[[ -f "$COFCO_TEST_LOADED" ]]
grep -Fq 'restoring the previous snapshot' "${test_root}/install.log"
echo 'ROLLBACK_OK candidate failed; old snapshot, plist, and agent restored'
