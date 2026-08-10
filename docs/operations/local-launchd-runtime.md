# 本地 launchd 运行机制

正式本地服务由当前用户的 LaunchAgent
`com.cofco.qiqihar.enterprise.local-stack` 托管：后端 `8090`、业务 Web `63182`、
总览 Web `63200`。任务启用 `RunAtLoad` 与 `KeepAlive`，登录后自动启动；监督者
异常退出由 launchd 以 30 秒最小启动间隔重启，组件退出由监督者在 5 秒轮询内恢复。

## 安装与管理

```bash
cd /Users/federal/Desktop/cofco-qiqihar-enterprise-backend
./scripts/local-runtime.sh install
./scripts/local-runtime.sh start
./scripts/local-runtime.sh stop
./scripts/local-runtime.sh restart
./scripts/local-runtime.sh status
./scripts/local-runtime.sh uninstall
```

`install` 会刷新运行副本、安装 plist、注册并等待三项服务健康。`stop` 精确卸载该
LaunchAgent 并停止所有权记录匹配的组件，但保留 plist；`uninstall` 还会删除 plist。
`status` 报告监督者 PID，以及每项服务的 listener PID、端口、HTTP 状态和所有权。

macOS 不允许普通 LaunchAgent 直接读取 `Desktop`。因此安装命令使用 APFS clonefile
把三个工作树的当前内容复制到：

`~/Library/Application Support/COFCO Qiqihar Enterprise/runtime`

后台进程只读取该运行副本，不回写或覆盖工作树。工作树有新代码后再次执行
`install` 即可刷新；异常恢复无需访问 `Desktop`。

## 健康检查与日志

```bash
./scripts/verify-local-service-manager.sh
./scripts/healthcheck-local.sh
./scripts/verify-local-links.sh
```

组件 stdout/stderr 分开记录在：

`~/Library/Application Support/COFCO Qiqihar Enterprise/state/logs`

监督者 stdout/stderr 记录在：

`~/Library/Logs/COFCO Qiqihar Enterprise`

先运行 `local-runtime.sh status`；若 LaunchAgent 未运行，再查看
`supervisor.stderr.log`。若只有一个组件异常，查看对应的
`backend|business|overview.stderr.log`。端口冲突时不会宽泛终止占用者；脚本只会
操作 PID、启动时间、端口与服务名全部匹配的所有权记录。

## 可选敏感配置

默认本地配置不需要密钥。数据库必须使用凭据时，可创建：

`~/.config/cofco-qiqihar-enterprise/local-runtime.env`

执行 `chmod 600`，每行使用不带 shell 引号或变量展开的 `KEY=VALUE`。只允许
`QIQIHAR_DB_URL`、`QIQIHAR_DB_USERNAME`、`QIQIHAR_DB_PASSWORD`；其他键或
组/其他用户可读权限会使启动失败。正式本地端口固定为 `8090/63182/63200`，不从
敏感配置覆盖。不得把该文件或密钥提交到仓库。
