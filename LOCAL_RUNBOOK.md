# 本地一体化运行手册（上线前本地验收）

目标：保证本地每次启动后，页面、总览、后端三者始终可访问，避免“点开后过一会打不开”。

## 1. 一键启动（推荐）

```bash
cd "/Users/federal/Library/Application Support/COFCO Qiqihar Enterprise/runtime/cofco-qiqihar-enterprise-backend"
nohup ./scripts/start-local.sh > /tmp/cofco-local-stack.log 2>&1 &
```

按需固定端口（与 `verify-local-links.sh/healthcheck-local.sh` 一致）：

```bash
export COFCO_ENTERPRISE_BACKEND_PORT=8090
export COFCO_ENTERPRISE_BUSINESS_PORT=63182
export COFCO_ENTERPRISE_OVERVIEW_PORT=63200
```

说明：

- `start-local.sh` 会启动/接管三项服务（如上覆盖变量，则按自定义端口启动）：
  - 后端：`COFCO_ENTERPRISE_BACKEND_PORT`
  - 业务前端（入口）：`COFCO_ENTERPRISE_BUSINESS_PORT`
  - 总览前端：`COFCO_ENTERPRISE_OVERVIEW_PORT`
- 默认是 watch 模式，脚本会持续检测并尝试恢复掉线服务（按 `Ctrl+C` 可停掉 3 个服务）。

## 2. 快速自检

```bash
cd "/Users/federal/Library/Application Support/COFCO Qiqihar Enterprise/runtime/cofco-qiqihar-enterprise-backend"
./scripts/healthcheck-local.sh
```

或

```bash
cd "/Users/federal/Library/Application Support/COFCO Qiqihar Enterprise/runtime/cofco-qiqihar-enterprise-backend"
./scripts/verify-local-links.sh
```

通过后可直接打开：

正式本地验收只使用 numeric loopback，不对局域网暴露：

```bash
echo "http://127.0.0.1:${COFCO_ENTERPRISE_BUSINESS_PORT:-63182}/"
```

`63200` 是经业务入口代理的内部总揽渲染器，`8090` 是内部 API；两者都不是用户验收入口。

## 3. 停止服务

```bash
cd "/Users/federal/Library/Application Support/COFCO Qiqihar Enterprise/runtime/cofco-qiqihar-enterprise-backend"
./scripts/stop-local.sh
```

## 4. 日志

- 运行日志：`cofco-qiqihar-enterprise-backend/.local-runtime/logs`
  - `backend.log`
  - `overview.log`
  - `business.log`

## 5. 只启动一遍并退出（不守护）

```bash
./scripts/start-local.sh --no-watch
```

用于 CI/脚本或一次性检测场景，不建议日常手工运行。

## 6. 稳定性验收检查项（每次变更后都建议执行）

- [ ] `./scripts/start-local.sh` 正常启动，无报错退出
- [ ] `./scripts/healthcheck-local.sh` 三项全部 `[OK]`
- [ ] 链接可在当前机器打开
- [ ] 5 分钟内反复切换页面仍可访问
- [ ] 关闭启动窗口后，后台服务仍持续存在（使用 `nohup` 时）
- [ ] `./scripts/verify-local-links.sh` 跨机入口返回均为 `[OK]`

## 7. 配置文件（可选）

- 总览前端配置模板：`../cofco-qiqihar-enterprise-frontend/.env.example`
- 业务前端配置模板：`../cofco-qiqihar-enterprise-web/.env.example`

如需固定内网地址（例如多网段测试），可复制为 `.env.local` 并按需填充：

- `VITE_BUSINESS_PLATFORM_HOST / VITE_BUSINESS_PLATFORM_PORT`
- `VITE_OVERVIEW_MAP_HOST / VITE_OVERVIEW_MAP_PORT`

## 8. 商业月更卫星影像

公开态势地图只访问本系统的同源瓦片接口，商业供应商密钥保留在后端。采购
Planet Global Monthly Mosaics 权限后，在受保护的运行环境中配置：

```bash
export QIQIHAR_MAP_IMAGERY_TILE_URL_TEMPLATE='https://tiles.planet.com/basemaps/v1/planet-tiles/global_monthly_{year}_{month}_mosaic/gmap/{z}/{x}/{y}.png?api_key={apiKey}'
export QIQIHAR_MAP_IMAGERY_API_KEY='由密钥管理服务注入，不写入仓库或前端'
export QIQIHAR_MAP_IMAGERY_PROVIDER='Planet Global Monthly'
export QIQIHAR_MAP_IMAGERY_ATTRIBUTION='Planet Labs PBC'
export QIQIHAR_MAP_IMAGERY_PERIOD_LAG_MONTHS=1
```

重启后端后，`/api/v1/overview/map-imagery/metadata` 应显示
`commercialConfigured=true`、`updateCadence=MONTHLY`。网关在每月自动切换年月；
考虑供应商发布窗口，每月前 7 天继续使用上一个已发布周期。未配置商业密钥时，
系统使用 Esri World Imagery 兼容回退，不能将其表述为“已启用商业月更影像”。
