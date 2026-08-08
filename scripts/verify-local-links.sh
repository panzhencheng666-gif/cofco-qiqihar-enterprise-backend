#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${backend_root}/scripts/verify-loopback-listener.sh"

workspace_root="$(cd "${backend_root}/.." && pwd)"
overview_frontend_root="${COFCO_ENTERPRISE_FRONTEND_ROOT:-${workspace_root}/cofco-qiqihar-enterprise-frontend}"
business_frontend_root="${COFCO_ENTERPRISE_WEB_ROOT:-${workspace_root}/cofco-qiqihar-enterprise-web}"

launcher="${COFCO_ENTERPRISE_DESKTOP_LAUNCHER:-/Users/federal/Desktop/启动齐齐哈尔粮食商情系统.command}"
host="127.0.0.1"

backend_port="${COFCO_ENTERPRISE_BACKEND_PORT:-8090}"
business_port="${COFCO_ENTERPRISE_BUSINESS_PORT:-63182}"
overview_port="${COFCO_ENTERPRISE_OVERVIEW_PORT:-63200}"

declare -a checks=(
  "backend|http://127.0.0.1:${backend_port}/actuator/health|后端健康检查"
  "business|http://${host}:${business_port}/prototype.html|业务前端"
  "overview|http://${host}:${overview_port}/|总览前端"
  "business API proxy|http://${host}:${business_port}/api/v1/master-data/products|业务前端本地身份代理"
  "overview API proxy|http://${host}:${overview_port}/api/v1/master-data/products|总览前端本地身份代理"
)

all_ok=1

for item in "${checks[@]}"; do
  IFS="|" read -r name url label <<< "$item"
  if curl -sSf --max-time 3 "$url" >/dev/null 2>&1; then
    echo "[OK] $label ($name) 可访问"
  else
    echo "[FAIL] $label ($name) 无法访问: $url"
    all_ok=0
  fi
done

require_loopback_listener "$backend_port" "backend"
require_loopback_listener "$business_port" "business frontend"
require_loopback_listener "$overview_port" "overview frontend"
"${backend_root}/scripts/verify-local-region-hierarchy.sh"

legacy_project="cofco-qiqihar-""dashboard"
runtime_files=(
  "${backend_root}/scripts/start-local.sh" \
  "${backend_root}/scripts/stop-local.sh" \
  "${backend_root}/scripts/healthcheck-local.sh" \
  "${backend_root}/scripts/local-process-ownership.sh" \
  "${backend_root}/scripts/verify-loopback-listener.sh" \
  "${backend_root}/scripts/verify-local-region-hierarchy.sh" \
  "${overview_frontend_root}/package.json" \
  "${overview_frontend_root}/vite.config.ts" \
  "${business_frontend_root}/package.json" \
  "${business_frontend_root}/vite.prototype.config.ts" \
  "$launcher"
)
if grep -Fq -- "$legacy_project" "${runtime_files[@]}"; then
  echo "[FAIL] launcher/runtime scripts still reference the legacy dashboard project" >&2
  exit 1
fi

echo
echo "仅限本机访问:"
echo "业务入口: http://$host:${business_port}/prototype.html?page=overview&section=map"
echo "总览入口: http://$host:${overview_port}/"

if [[ "$all_ok" -ne 1 ]]; then
  echo "建议执行: ./scripts/healthcheck-local.sh"
  exit 1
fi

echo "本机联动链路检查通过"
