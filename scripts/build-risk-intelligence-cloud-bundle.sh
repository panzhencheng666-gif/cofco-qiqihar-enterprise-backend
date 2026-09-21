#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:-${backend_root}/work/cloud-bundle}"
bundle_name="cofco-risk-intelligence-20260921"
stage="${output_dir}/${bundle_name}"
archive="${output_dir}/${bundle_name}.tar.gz"
acceptance="${stage}/LOCAL_ACCEPTANCE"

mkdir -p "$output_dir"
[[ ! -e "$stage" ]] || { echo "Refusing to overwrite bundle stage: $stage" >&2; exit 1; }
mkdir -p "$stage/migrations"

local_runtime_env="${HOME}/.config/cofco-qiqihar-risk-intelligence/local-runtime.env"
if [[ -f "$local_runtime_env" ]]; then
  while IFS='=' read -r key value; do
    case "$key" in
      RISK_DB_URL|RISK_DB_USERNAME|RISK_DB_PASSWORD|RISK_EXPECTED_DATABASE)
        export "$key=$value"
        ;;
    esac
  done < "$local_runtime_env"
fi

"${backend_root}/scripts/mvn-jdk21.sh" -q \
  -f "${backend_root}/risk-intelligence-service/pom.xml" test package
(cd "${backend_root}/tools/risk-mlx-trainer" && python3 -m unittest test_server.py test_remote_worker.py)
for contract in "${backend_root}"/scripts/tests/*.test.sh; do bash "$contract"; done
"${backend_root}/scripts/verify-risk-intelligence-real-training.sh"

{
  printf 'accepted_at_utc=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  printf 'java_tests=passed\npython_tests=passed\nshell_contracts=passed\nreal_mlx_training=passed\n'
  printf 'git_commit=%s\n' "$(git -C "$backend_root" rev-parse HEAD)"
} > "$acceptance"
chmod 400 "$acceptance"

install -m 400 "${backend_root}/risk-intelligence-service/target/risk-intelligence-service-0.1.0-SNAPSHOT.jar" \
  "$stage/risk-intelligence-service.jar"
install -m 500 "${backend_root}/ops/risk-intelligence/deploy-cloud-runtime.sh" "$stage/"
install -m 500 "${backend_root}/ops/risk-intelligence/run-risk-migration.sh" "$stage/"
install -m 500 "${backend_root}/scripts/verify-risk-database-boundary.sh" "$stage/"
install -m 400 "${backend_root}/ops/risk-intelligence/create-risk-runtime-roles.sql" "$stage/"
install -m 400 "${backend_root}/ops/systemd/cofco-risk-intelligence.service" "$stage/"
for migration in V214__create_inventory_risk_foundation.sql \
    V215__operate_daily_risk_ai_training.sql \
    V216__automate_risk_model_promotion.sql \
    V217__isolate_risk_schema_runtime.sql; do
  install -m 400 "${backend_root}/src/main/resources/db/migration/${migration}" \
    "$stage/migrations/"
done

(cd "$stage" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
tar -C "$output_dir" -czf "$archive" "$bundle_name"
sha256sum "$archive" > "${archive}.sha256"
echo "$archive"
