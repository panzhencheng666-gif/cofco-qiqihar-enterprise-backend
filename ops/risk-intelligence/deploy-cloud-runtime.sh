#!/usr/bin/env bash
set -euo pipefail

bundle_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
runtime_root=/var/lib/cofco/risk-intelligence
releases_root="${runtime_root}/releases"
secrets_root="${runtime_root}/secrets"
artifact_root="${runtime_root}/model-artifacts"
tls_root="${runtime_root}/rds-tls"
runtime_env="${secrets_root}/runtime.env"
migration_env=/var/lib/cofco/checkpoints/regional-controlled-20260915-1419/migration.env
unit_target=/etc/systemd/system/cofco-risk-intelligence.service
unit_source="${RISK_UNIT_SOURCE:-${bundle_root}/cofco-risk-intelligence.service}"
release_id="${RISK_RELEASE_ID:-$(date -u '+%Y%m%dT%H%M%SZ')}"
release_dir="${releases_root}/${release_id}"
image="${RISK_CONTAINER_IMAGE:-localhost/cofco-local/backend:stage9-20260814-amd64}"

fail() { echo "RISK_CLOUD_DEPLOY_FAILED: $*" >&2; exit 1; }
require_root() { [[ "$(id -u)" == 0 ]] || fail "root is required"; }
require_file() { [[ -f "$1" ]] || fail "required file is missing: $1"; }

read_named_env() {
  local file=$1 wanted=$2 key value
  while IFS='=' read -r key value; do
    [[ "$key" == "$wanted" ]] && { printf '%s' "$value"; return 0; }
  done < "$file"
  return 1
}

assert_host_capacity() {
  local available_kib root_free_kib
  available_kib="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
  root_free_kib="$(df -Pk /var/lib/cofco | awk 'NR==2 {print $4}')"
  [[ "$available_kib" -ge 524288 ]] || fail "less than 512 MiB memory is available"
  [[ "$root_free_kib" -ge 2097152 ]] || fail "less than 2 GiB disk is available"
  if ss -ltnH '( sport = :19384 )' | grep -q . \
      && ! systemctl is-active --quiet cofco-risk-intelligence.service; then
    fail "loopback port 19384 is occupied outside the managed risk service"
  fi
}

assert_existing_service_healthy() {
  curl -fsS --max-time 5 http://127.0.0.1:19091/actuator/health/readiness >/dev/null \
    || curl -fsS --max-time 5 http://127.0.0.1:19091/actuator/health >/dev/null \
    || fail "existing business service on port 19091 is not healthy"
}

write_runtime_env() {
  if [[ -f "$runtime_env" ]]; then
    [[ "$(stat -c '%a' "$runtime_env")" == 600 ]] || fail "unsafe runtime secret mode"
    return
  fi
  local password ingestion_key training_token database_name migration_url temporary
  password="$(openssl rand -hex 32)"
  ingestion_key="$(openssl rand -hex 32)"
  training_token="$(openssl rand -hex 32)"
  migration_url="$(read_named_env "$migration_env" MIGRATION_URL)"
  database_name="$(printf '%s' "${migration_url%%\?*}" | sed 's|.*/||')"
  temporary="$(mktemp "${secrets_root}/.runtime.env.XXXXXX")"
  umask 077
  {
    printf 'RISK_DB_URL=%s\n' "$migration_url"
    printf 'RISK_DB_USERNAME=qiqihar_risk_runtime_login\n'
    printf 'RISK_DB_PASSWORD=%s\n' "$password"
    printf 'RISK_EXPECTED_DATABASE=%s\n' "$database_name"
    printf 'RISK_INGESTION_KEY=%s\n' "$ingestion_key"
    printf 'RISK_SERVER_PORT=63184\n'
    printf 'RISK_TRAINING_ENABLED=true\n'
    printf 'RISK_TRAINING_REMOTE_NODE_ENABLED=true\n'
    printf 'RISK_MODEL_ARTIFACT_ROOT=%s\n' "${artifact_root}/models"
    printf 'RISK_TRAINING_NODE_ARTIFACT_ROOT=%s\n' "${artifact_root}/remote"
    printf 'RISK_TRAINING_NODE_TOKEN=%s\n' "$training_token"
    printf 'RISK_TRAINING_NODE_LEASE_DURATION=35m\n'
    printf 'RISK_TRAINING_NODE_MAXIMUM_ARTIFACT_BYTES=67108864\n'
    printf 'RISK_TRAINING_NODE_MAXIMUM_TOTAL_ARTIFACT_BYTES=1073741824\n'
    printf 'RISK_LLM_BASE_MODEL=mlx-community/Qwen3-0.6B-4bit\n'
    printf 'JAVA_TOOL_OPTIONS=-Xms64m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError\n'
  } > "$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$runtime_env"
}

configure_runtime_tls() {
  local migration_url query parameter parameter_key encoded_path decoded_path runtime_url separator
  migration_url="$(read_named_env "$migration_env" MIGRATION_URL)"
  runtime_url=${migration_url%%\?*}
  mkdir -p "$tls_root"
  chown 10001:10001 "$tls_root"
  chmod 700 "$tls_root"
  find "$tls_root" -mindepth 1 -maxdepth 1 -type f -delete
  if [[ "$migration_url" == *\?* ]]; then
    query=${migration_url#*\?}
    separator='?'
    IFS='&' read -r -a parameters <<< "$query"
    for parameter in "${parameters[@]}"; do
      parameter_key=${parameter%%=*}
      case "$parameter_key" in
        sslrootcert|sslcert|sslkey)
          encoded_path=${parameter#*=}
          decoded_path=${encoded_path//+/ }
          printf -v decoded_path '%b' "${decoded_path//%/\\x}"
          [[ "$decoded_path" == /* ]] || fail "$parameter_key must use an absolute path"
          [[ -f "$decoded_path" ]] || fail "$parameter_key file is missing on the host: $decoded_path"
          install -o 10001 -g 10001 -m 400 "$decoded_path" "${tls_root}/${parameter_key}"
          parameter="${parameter_key}=/run/risk-rds/${parameter_key}"
          ;;
      esac
      runtime_url+="${separator}${parameter}"
      separator='&'
    done
  fi
  local temporary
  temporary="$(mktemp "${secrets_root}/.runtime.env.XXXXXX")"
  chmod 600 "$temporary"
  awk -v runtime_url="$runtime_url" '
    BEGIN { replaced=0 }
    /^RISK_DB_URL=/ { print "RISK_DB_URL=" runtime_url; replaced=1; next }
    { print }
    END { if (!replaced) print "RISK_DB_URL=" runtime_url }
  ' "$runtime_env" > "$temporary"
  mv "$temporary" "$runtime_env"
}

install_release() {
  [[ ! -e "$release_dir" ]] || fail "release already exists: $release_dir"
  mkdir -p "$release_dir"
  chmod 755 "$release_dir"
  install -m 444 "${bundle_root}/risk-intelligence-service.jar" "$release_dir/"
  install -m 500 "${bundle_root}/verify-risk-database-boundary.sh" "$release_dir/"
  install -m 500 "${bundle_root}/run-risk-migration.sh" "$release_dir/"
  install -m 400 "${bundle_root}/create-risk-runtime-roles.sql" "$release_dir/"
  cp -a "${bundle_root}/migrations" "$release_dir/migrations"
  find "$release_dir/migrations" -type f -exec chmod 444 {} +
  chmod 755 "$release_dir/migrations"
}

migrate_database() {
  local migration_url migration_user migration_password expected_database runtime_password migration_secret
  local query parameter parameter_key encoded_path decoded_path
  local -a migration_tls_mount_args=()
  migration_url="$(read_named_env "$migration_env" MIGRATION_URL)"
  migration_user="$(read_named_env "$migration_env" MIGRATION_USER)"
  migration_password="$(read_named_env "$migration_env" MIGRATION_PASSWORD)"
  expected_database="$(read_named_env "$runtime_env" RISK_EXPECTED_DATABASE)"
  runtime_password="$(read_named_env "$runtime_env" RISK_DB_PASSWORD)"
  if [[ "$migration_url" == *\?* ]]; then
    query=${migration_url#*\?}
    IFS='&' read -r -a parameters <<< "$query"
    for parameter in "${parameters[@]}"; do
      parameter_key=${parameter%%=*}
      case "$parameter_key" in
        sslrootcert|sslcert|sslkey)
          encoded_path=${parameter#*=}
          decoded_path=${encoded_path//+/ }
          printf -v decoded_path '%b' "${decoded_path//%/\\x}"
          [[ "$decoded_path" == /* ]] || fail "$parameter_key must use an absolute path"
          [[ -f "$decoded_path" ]] || fail "$parameter_key file is missing on the host: $decoded_path"
          migration_tls_mount_args+=(--volume "${decoded_path}:${decoded_path}:ro")
          ;;
      esac
    done
  fi
  migration_secret="$(mktemp /run/risk-migration.XXXXXX)"
  chmod 600 "$migration_secret"
  {
    printf 'RISK_MIGRATION_URL=%s\n' "$migration_url"
    printf 'RISK_MIGRATION_USERNAME=%s\n' "$migration_user"
    printf 'RISK_MIGRATION_PASSWORD=%s\n' "$migration_password"
    printf 'RISK_EXPECTED_DATABASE=%s\n' "$expected_database"
    printf 'RISK_MIGRATION_PATH=/release/migrations\n'
  } > "$migration_secret"
  local psql_url=${migration_url#jdbc:}
  psql_url=${psql_url%%\?*}
  local connection_headroom
  connection_headroom="$(PGPASSWORD="$migration_password" psql --no-psqlrc --tuples-only --no-align \
    --set=ON_ERROR_STOP=1 --username="$migration_user" "$psql_url" \
    --command="SELECT current_setting('max_connections')::int-count(*) FROM pg_stat_activity")"
  [[ "$connection_headroom" -ge 10 ]] || { rm -f "$migration_secret"; fail "RDS has fewer than 10 free connections"; }
  if ! podman run --rm --network host --memory=384m --cpus=0.75 --pids-limit=256 \
      --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m --env-file "$migration_secret" \
      "${migration_tls_mount_args[@]}" \
      -v "${release_dir}:/release:ro" --entrypoint java "$image" \
      -Dloader.main=com.cofco.qiqihar.riskintelligence.operations.RiskMigrationRunner \
      -cp /release/risk-intelligence-service.jar \
      org.springframework.boot.loader.launch.PropertiesLauncher; then
    rm -f "$migration_secret"
    fail "controlled risk migrations failed"
  fi
  rm -f "$migration_secret"

  PGPASSWORD="$migration_password" psql --no-psqlrc --set=ON_ERROR_STOP=1 \
    --username="$migration_user" --set=database_name="$expected_database" \
    --set=migration_owner="$migration_user" --set=risk_runtime_password="$runtime_password" \
    --file="${release_dir}/create-risk-runtime-roles.sql" "$psql_url"

  RISK_DB_URL="$migration_url" RISK_DB_USERNAME=qiqihar_risk_runtime_login \
    RISK_DB_PASSWORD="$runtime_password" RISK_EXPECTED_DATABASE="$expected_database" \
    "${release_dir}/verify-risk-database-boundary.sh"
}

activate_service() {
  local previous="" had_unit=false unit_backup
  [[ ! -L "${runtime_root}/current" ]] || previous="$(readlink "${runtime_root}/current")"
  unit_backup="$(mktemp /run/cofco-risk-unit.XXXXXX)"
  if [[ -f "$unit_target" ]]; then cp -a "$unit_target" "$unit_backup"; had_unit=true; fi
  rollback_service() {
    systemctl disable --now cofco-risk-intelligence.service >/dev/null 2>&1 || true
    if [[ "$had_unit" == true ]]; then
      cp -a "$unit_backup" "$unit_target"
    else
      rm -f "$unit_target"
    fi
    if [[ -n "$previous" ]]; then
      ln -sfn "$previous" "${runtime_root}/current"
    else
      rm -f "${runtime_root}/current"
    fi
    systemctl daemon-reload
    if [[ "$had_unit" == true && -n "$previous" ]]; then
      if ! systemctl enable --now cofco-risk-intelligence.service >/dev/null 2>&1; then
        echo "RISK_SERVICE_ROLLBACK_FAILED old service could not start" >&2
        return 1
      fi
      for _ in $(seq 1 30); do
        curl -fsS --max-time 2 http://127.0.0.1:19384/actuator/health >/dev/null 2>&1 \
          && { rm -f "$unit_backup"; echo "RISK_SERVICE_ROLLBACK_OK databaseMigrationsRemainForwardOnly=true" >&2; return 0; }
        sleep 1
      done
      echo "RISK_SERVICE_ROLLBACK_FAILED old service is unhealthy" >&2
      return 1
    fi
    rm -f "$unit_backup"
    echo "RISK_SERVICE_ROLLBACK_OK databaseMigrationsRemainForwardOnly=true" >&2
  }
  ln -sfn "$release_dir" "${runtime_root}/current.new"
  mv -Tf "${runtime_root}/current.new" "${runtime_root}/current"
  install -m 644 "$unit_source" "$unit_target"
  systemctl daemon-reload
  if ! systemctl enable cofco-risk-intelligence.service || ! systemctl restart cofco-risk-intelligence.service; then
    rollback_service || fail "systemd activation failed and old service recovery failed"
    fail "systemd activation failed"
  fi
  for _ in $(seq 1 90); do
    if curl -fsS --max-time 2 http://127.0.0.1:19384/actuator/health >/dev/null 2>&1; then
      curl -fsS --max-time 3 http://127.0.0.1:19384/api/v1/risk-intelligence/operations/boundary
      if ! curl -fsS --max-time 5 http://127.0.0.1:19091/actuator/health/readiness >/dev/null \
          && ! curl -fsS --max-time 5 http://127.0.0.1:19091/actuator/health >/dev/null; then
        rollback_service || fail "existing service failed and old risk service recovery failed"
        fail "existing business service became unhealthy; risk service was rolled back"
      fi
      rm -f "$unit_backup"
      printf '\nRISK_CLOUD_RUNTIME_OK release=%s loopbackPort=19384\n' "$release_id"
      return
    fi
    sleep 1
  done
  systemctl status --no-pager cofco-risk-intelligence.service || true
  rollback_service || fail "risk service health timed out and old service recovery failed"
  fail "risk service health check timed out"
}

main() {
  require_root
  for tool in openssl podman psql systemctl curl sha256sum ss; do command -v "$tool" >/dev/null || fail "$tool is required"; done
  for file in risk-intelligence-service.jar verify-risk-database-boundary.sh run-risk-migration.sh \
    create-risk-runtime-roles.sql SHA256SUMS LOCAL_ACCEPTANCE; do
    require_file "${bundle_root}/${file}"
  done
  require_file "$unit_source"
  require_file "$migration_env"
  podman image exists "$image" || fail "required local Java image is unavailable: $image"
  (cd "$bundle_root" && sha256sum -c SHA256SUMS)
  grep -Fxq 'java_tests=passed' "${bundle_root}/LOCAL_ACCEPTANCE" || fail "Java acceptance is missing"
  grep -Fxq 'python_tests=passed' "${bundle_root}/LOCAL_ACCEPTANCE" || fail "Python acceptance is missing"
  grep -Fxq 'shell_contracts=passed' "${bundle_root}/LOCAL_ACCEPTANCE" || fail "shell acceptance is missing"
  grep -Fxq 'real_mlx_training=passed' "${bundle_root}/LOCAL_ACCEPTANCE" || fail "real MLX training acceptance is missing"
  assert_host_capacity
  assert_existing_service_healthy
  mkdir -p "$releases_root" "$secrets_root" "${artifact_root}/models" "${artifact_root}/remote"
  chmod 755 "$runtime_root" "$releases_root"
  chmod 700 "$secrets_root"
  chown -R 10001:10001 "$artifact_root"
  chmod 700 "$artifact_root" "${artifact_root}/models" "${artifact_root}/remote"
  write_runtime_env
  configure_runtime_tls
  install_release
  migrate_database
  activate_service
}

main "$@"
