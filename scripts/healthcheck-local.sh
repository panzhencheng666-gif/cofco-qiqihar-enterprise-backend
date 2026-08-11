#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_root="${COFCO_ENTERPRISE_LOCAL_RUNTIME_ROOT:-${HOME}/Library/Application Support/COFCO Qiqihar Enterprise/state}"
log_dir="${runtime_root}/logs"
source "${backend_root}/scripts/verify-loopback-listener.sh"

runtime_profile="${COFCO_ENTERPRISE_RUNTIME_PROFILE:-local}"
host="127.0.0.1"

backend_port="${COFCO_ENTERPRISE_BACKEND_PORT:-8090}"
business_port="${COFCO_ENTERPRISE_BUSINESS_PORT:-63182}"
overview_port="${COFCO_ENTERPRISE_OVERVIEW_PORT:-63200}"

declare -a checks=("backend|http://127.0.0.1:${backend_port}/actuator/health|后端服务")

if [[ "$runtime_profile" == "local" ]]; then
  checks+=(
    "business|http://127.0.0.1:${business_port}/|业务前端唯一验收入口"
    "overview|http://127.0.0.1:${overview_port}/|总览前端"
    "business API proxy|http://127.0.0.1:${business_port}/api/v1/master-data/products|业务前端本地身份代理"
    "overview API proxy|http://127.0.0.1:${overview_port}/api/v1/master-data/products|总览前端本地身份代理"
  )
elif [[ "$runtime_profile" == "production" ]]; then
  status_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 4 "http://127.0.0.1:${backend_port}/api/v1/master-data/products")"
  if [[ "$status_code" != "401" ]]; then
    echo "[FAIL] production API without JWT must return 401, got: $status_code"
    exit 1
  fi
  echo "[OK] production API without JWT returns 401"
else
  echo "Unsupported COFCO_ENTERPRISE_RUNTIME_PROFILE: $runtime_profile" >&2
  exit 2
fi

all_ok=1

for item in "${checks[@]}"; do
  IFS="|" read -r name url label <<< "$item"
  if curl -sSf --max-time 4 "$url" >/dev/null 2>&1; then
    echo "[OK] $label ($name) 可访问"
  else
    echo "[FAIL] $label ($name) 无法访问: $url"
    all_ok=0
  fi
done

if [[ "$runtime_profile" == "local" ]]; then
  require_loopback_listener "$backend_port" "backend"
  require_loopback_listener "$business_port" "business frontend"
  require_loopback_listener "$overview_port" "overview frontend"
  echo "[OK] 三个本地监听均绑定 numeric loopback"
  "${backend_root}/scripts/verify-local-region-hierarchy.sh"
fi

echo
echo "运行模式: $runtime_profile"
if [[ "$runtime_profile" == "local" ]]; then
  echo "建议访问入口:"
  echo "http://$host:${business_port}/"
fi
echo "日志路径: $log_dir"

if [[ "$all_ok" -ne 1 ]]; then
  echo "本地链路不完整，请先执行: ./scripts/start-local.sh"
  exit 1
fi

exit 0
