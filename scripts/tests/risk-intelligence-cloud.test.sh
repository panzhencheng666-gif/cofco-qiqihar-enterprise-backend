#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() { echo "FAIL: $1" >&2; exit 1; }
assert_contains() {
  grep -Fq -- "$2" "${backend_root}/$1" || fail "$1 does not contain $2"
}
assert_not_contains() {
  if grep -Fq -- "$2" "${backend_root}/$1"; then
    fail "$1 contains forbidden text $2"
  fi
}

deploy_script="ops/risk-intelligence/deploy-cloud-runtime.sh"
unit="ops/systemd/cofco-risk-intelligence.service"
runner="risk-intelligence-service/src/main/java/com/cofco/qiqihar/riskintelligence/operations/RiskMigrationRunner.java"
bundle_script="scripts/build-risk-intelligence-cloud-bundle.sh"

[[ -x "${backend_root}/${deploy_script}" ]] || fail "cloud deployment script is missing or not executable"
[[ -f "${backend_root}/${unit}" ]] || fail "cloud systemd unit is missing"

assert_contains risk-intelligence-service/src/main/resources/application.yml \
  'address: ${RISK_SERVER_ADDRESS:127.0.0.1}'
assert_contains "$deploy_script" 'RISK_TRAINING_REMOTE_NODE_ENABLED=true'
assert_contains "$deploy_script" 'RISK_DB_USERNAME=qiqihar_risk_runtime_login'
assert_contains "$deploy_script" 'verify-risk-database-boundary.sh'
assert_contains "$deploy_script" 'LOCAL_ACCEPTANCE'
assert_contains "$deploy_script" 'assert_existing_service_healthy'
assert_contains "$deploy_script" 'rollback_service'
assert_contains "$deploy_script" 'RDS has fewer than 10 free connections'
assert_contains "$deploy_script" 'sslrootcert|sslcert|sslkey'
assert_contains "$deploy_script" 'resolve_tls_host_path'
assert_contains "$deploy_script" 'RISK_MIGRATION_TLS_SOURCE_DIR'
assert_contains "$deploy_script" 'container_tls_root=/var/lib/cofco/risk-intelligence/tls'
assert_contains "$deploy_script" 'container_path="${container_tls_root}/$(basename "$decoded_path")"'
assert_contains "$deploy_script" 'runtime_parameter="${parameter_key}=${container_path}"'
assert_contains "$deploy_script" 'runtime_url+="${separator}${runtime_parameter}"'
assert_contains "$deploy_script" 'migration_tls_mount_args+=(--volume "${runtime_tls_root}:${container_tls_root}:ro")'
assert_contains "$deploy_script" 'for migration in V214__create_inventory_risk_foundation.sql'
assert_contains "$deploy_script" 'V217__isolate_risk_schema_runtime.sql; do'
assert_contains "$deploy_script" 'install -m 444 "${bundle_root}/migrations/${migration}" "$release_dir/migrations/"'
assert_not_contains "$deploy_script" 'cp -a "${bundle_root}/migrations"'
assert_not_contains "$deploy_script" 'migrations/V*.sql'
assert_contains "$bundle_script" 'for migration in V214__create_inventory_risk_foundation.sql'
assert_contains "$bundle_script" 'V217__isolate_risk_schema_runtime.sql; do'
assert_not_contains "$bundle_script" "-name 'V*.sql'"
assert_contains "$runner" 'private static final String RISK_HISTORY_TABLE="risk_flyway_schema_history";'
assert_contains "$runner" '.table(RISK_HISTORY_TABLE)'
assert_contains "$runner" '.baselineOnMigrate(true)'
assert_contains "$runner" 'private static final String SHARED_BASELINE_VERSION="213";'
assert_contains "$runner" '.baselineVersion(SHARED_BASELINE_VERSION)'
assert_contains "$runner" "select count(*) from public.flyway_schema_history where version='213' and success"
assert_contains "$runner" "version in ('214','215','216','217')"
assert_contains "$deploy_script" 'configure_runtime_tls'
assert_contains "$deploy_script" 'unit_source="${RISK_UNIT_SOURCE:-${bundle_root}/cofco-risk-intelligence.service}"'
assert_contains "$deploy_script" 'install -m 644 "$unit_source" "$unit_target"'
assert_contains "$deploy_script" 'install -o 10001 -g 10001 -m 400 "$host_path" "$staged_path"'
assert_contains "$unit" '--memory=384m'
assert_contains "$unit" '--cpus=0.75'
assert_contains "$unit" '127.0.0.1:19384:63184'
assert_contains "$unit" 'RISK_SERVER_ADDRESS=0.0.0.0'
assert_contains "$unit" '/var/lib/cofco/risk-intelligence/runtime-tls:/var/lib/cofco/risk-intelligence/tls:ro'
assert_not_contains "$unit" ':/run:ro'
assert_not_contains "$unit" '--privileged'
assert_not_contains "$unit" '0.0.0.0:19384'

bash -n "${backend_root}/${deploy_script}"

echo "RISK_INTELLIGENCE_CLOUD_CONTRACT_OK"
