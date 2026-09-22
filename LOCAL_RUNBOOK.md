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

## 8. 周更近期卫星影像

公开态势地图只访问本系统的同源瓦片接口，供应商实例标识或密钥保留在后端。
供应源必须支持按时间区间生成瓦片；不能通过缩短 Esri 缓存时间冒充影像更新。

以下是 Copernicus Data Space Sentinel Hub 自定义 WMTS 实例的周窗口示例。实例中需预先
建立 `TRUE-COLOR` Sentinel-2 L2A 图层，并核对 `PopularWebMercator256` 矩阵集：

```bash
export QIQIHAR_MAP_IMAGERY_TILE_URL_TEMPLATE='https://sh.dataspace.copernicus.eu/ogc/wmts/{apiKey}?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=TRUE-COLOR&STYLE=default&FORMAT=image/jpeg&TILEMATRIXSET=PopularWebMercator256&TILEMATRIX={z}&TILECOL={x}&TILEROW={y}&TIME={periodStart}%2F{periodEnd}&PRIORITY=leastCC&MAXCC=30'
export QIQIHAR_MAP_IMAGERY_API_KEY='由受保护配置注入的 Sentinel Hub 实例标识'
export QIQIHAR_MAP_IMAGERY_PROVIDER='Copernicus Sentinel-2 L2A weekly'
export QIQIHAR_MAP_IMAGERY_ATTRIBUTION='European Union, Copernicus Sentinel-2 imagery'
export QIQIHAR_MAP_IMAGERY_PERIOD_LAG_WEEKS=0
```

重启后端后，`/api/v1/overview/map-imagery/metadata` 应显示
`commercialConfigured=true`、`updateCadence=WEEKLY`、`automaticWeeklyPeriod=true`，
并给出最近完整 UTC 自然周的 `acquisitionFrom` 与 `acquisitionTo`。每周一切换到
上一完整周；若新周瓦片暂不可用，网关继续返回缓存中的上一成功周版并标记 stale。

Sentinel-2 可提供约 5 天重访、10 米级真彩色数据，不等同于建筑级实时高清影像。
若采购更高分辨率商业源，仍使用 `{period}`、`{periodStart}`、`{periodEnd}`、
`{year}`、`{week}` 和 `{apiKey}` 占位符接入。页面必须展示实际采集周，不得写成
“实时卫星”。未配置版本化供应源时系统使用 Esri World Imagery 兼容回退，不能将其
表述为“已启用周更影像”。

### 8.1 自托管周更影像

生产推荐使用 `scripts/weekly_imagery_sync.py` 每周生成本地、版本化瓦片。用户浏览时
只访问本系统，不再逐次调用上游。工作进程依赖 Python 3 和 GDAL CLI
（`gdalbuildvrt`、`gdalwarp`、`gdal_translate`、`gdal_calc.py`、`gdal2tiles.py`）。

先从示例创建仅存在于受保护服务器的环境文件：

```bash
install -m 600 ops/imagery/weekly-imagery.env.example /secure/weekly-imagery.env
sudo scripts/install-weekly-imagery-worker.sh \
  --env-file /secure/weekly-imagery.env
```

安装器创建无登录权限的 `cofco-imagery` 用户、只读程序目录、
`/var/lib/cofco/imagery` 版本库以及 systemd timer。定时器每周一北京时间 03:10
运行；失败由 systemd 每三小时重试，最多四次。同一周成功后再次运行会幂等退出。

正式启用后执行一次受控首发并核验：

```bash
sudo systemctl start cofco-weekly-imagery.service
sudo systemctl status cofco-weekly-imagery.service --no-pager
sudo -u cofco-imagery python3 \
  /usr/local/lib/cofco-imagery/weekly_imagery_sync.py \
  --root /var/lib/cofco/imagery \
  --aoi /usr/local/lib/cofco-imagery/qiqihar-aoi.geojson --dry-run
readlink -f /var/lib/cofco/imagery/current
sha256sum -c /var/lib/cofco/imagery/current/manifest.sha256
```

正常运行不需要人工下载。没有合格影像或处理失败时 `current` 不变，在线地图继续使用
上一成功版本；不得在没有首次同步成功、接口读回和浏览器验收时声称周更已经上线。
