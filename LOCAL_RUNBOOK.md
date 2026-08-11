# 本地一体化运行手册（上线前本地验收）

目标：保证本地每次启动后，页面、总览、后端三者始终可访问，避免“点开后过一会打不开”。

## 1. 一键启动（推荐）

```bash
cd /Users/federal/Desktop/cofco-qiqihar-enterprise-backend
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
cd /Users/federal/Desktop/cofco-qiqihar-enterprise-backend
./scripts/healthcheck-local.sh
```

或

```bash
cd /Users/federal/Desktop/cofco-qiqihar-enterprise-backend
./scripts/verify-local-links.sh
```

通过后可直接打开：

使用你当前机器的内网 IP（如 `192.168.x.x`）拼接到端口即可打开。
注意：`127.0.0.1` 只能本机访问，局域网设备请务必用内网 IP：

```bash
export COFCO_ENTERPRISE_ACCESS_HOST="$(ipconfig getifaddr en0 || ipconfig getifaddr en1)"
echo "http://${COFCO_ENTERPRISE_ACCESS_HOST}:${COFCO_ENTERPRISE_BUSINESS_PORT:-63182}/prototype.html?page=overview&section=map"
```

如果你修改了端口变量，请同时把示例 URL 使用对应端口。

示例（请替换为你的局域网 IP）：

`http://<你的局域网IP>:${COFCO_ENTERPRISE_BUSINESS_PORT:-63182}/prototype.html?page=overview&section=map`

## 3. 停止服务

```bash
cd /Users/federal/Desktop/cofco-qiqihar-enterprise-backend
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
- [ ] 同局域网另一台设备可以打开同一链接
- [ ] 5 分钟内反复切换页面仍可访问
- [ ] 关闭启动窗口后，后台服务仍持续存在（使用 `nohup` 时）
- [ ] `./scripts/verify-local-links.sh` 跨机入口返回均为 `[OK]`

## 7. 配置文件（可选）

- 总览前端配置模板：`/Users/federal/Desktop/cofco-qiqihar-enterprise-frontend/.env.example`
- 业务原型配置模板：`/Users/federal/Desktop/cofco-qiqihar-enterprise-web/.env.example`

如需固定内网地址（例如多网段测试），可复制为 `.env.local` 并按需填充：

- `VITE_BUSINESS_PLATFORM_HOST / VITE_BUSINESS_PLATFORM_PORT`
- `VITE_OVERVIEW_MAP_HOST / VITE_OVERVIEW_MAP_PORT`
