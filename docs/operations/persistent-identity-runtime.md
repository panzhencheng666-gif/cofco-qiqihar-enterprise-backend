# 持久身份运行基础与账号开通约束

## 状态与架构决策（2026-09-09）

本文件为后续开发约束。业务用户名/密码校验由独立持久化 Keycloak 身份库承担；
密码使用 IdP 的安全哈希策略，不保存明文，不另建第二套业务密码校验。
业务系统通过真实 issuer + subject 绑定员工、角色、地区和维护人权限。
账号不得绑定一次性业务测试库、依赖临时明文密码清单或以本地 actor 代理验收。

当前提交提供部署候选和严格后端启动入口，尚未安装、切换受管环境或完成真实账号验收。
既有 local-stack 仍是 local profile；不能称为正式身份已接入。

## 隔离候选边界

`deploy/identity/compose.yaml` 使用独立 Compose 项目 `cofco-persistent-identity`，
身份 PostgreSQL 不发布宿主端口，仅 Keycloak 发布 loopback `28443` 的 TLS 端口。
专用外部 Docker 卷 `cofco_identity_persistent_v1` 在部署前明确创建；业务测试重置和
普通 compose down 均不应删除它。禁止对该卷运行 down -v / volume prune 或套用业务恢复脚本。
不使用宿主 5432、55434、55435，不挂载既有业务数据库目录。
数据库密码是服务凭据，通过 owner-only 文件挂载；不包含用户密码。
使用审核固定 digest 的 PostgreSQL 17 与 Keycloak 镜像；不得使用 latest。

Keycloak 使用 start、严格 hostname、直接 TLS 和 unless-stopped。
Docker 引擎必须配置用户登录后自动启动；restart 策略不等于 macOS 启动管理已验收。
TLS 信任链、正式 origin 与证书由运行负责人提供；不绕过浏览器证书警告。
候选只发布本机端口，不属于云端或公网部署。

运行前确定专用卷、新项目与 28443 的边界并检查无占用；填入受控配置后仅运行
`docker compose -f deploy/identity/compose.yaml config --quiet` 校验，避免打印插值后的敏感配置。
安装时显式创建专用卷，再启动该项目；不得操作其他项目或已有受管服务。

## 严格后端入口

受管配置文件仍使用 0600、字面 KEY=VALUE，无 shell 求值。
设置 `COFCO_ENTERPRISE_AUTH_MODE=oidc` 后，默认 launch wrapper 转到
`scripts/start-oidc-backend.sh`，以前台进程运行严格 oidc profile。
此入口必须由独立监督者使用；不能直接替换现有 local-stack plist：它不会启动两个 Vite 服务。
候选后端建议单独使用 28090，数据库候选仅使用已授权 55435；正式业务 DB 切换需另行确认目标。

配置必需项：issuer、client id/secret、HTTPS callback/logout、可信 MFA AMR/ACR、
32 字节 base64url 邀请加密键、真实 HTTPS 投递 endpoint/token、激活 URL、账号管理 URL、
开启 delivery worker，以及明确且彼此不同的运行/Flyway/consumer DB 账号。
具体键名见 `scripts/validate-managed-identity-config.py`。
缺项先拒绝启动；不允许 local/test profile 混入、关闭 secure cookie 或打开匿名自注册。
既有 Java SecurityStartupInvariant 和生产认证链仍是最终安全检查。
配置预检通过只证明格式完整，不能证明地址可信、投递成功或账号登录可用。

业务正式入口必须是 HTTPS 静态构建及无身份注入的反向代理，代理 OAuth callback、
登录、退出和业务 API；不能以当前 Vite 本地 actor 代理作为正式验收入口。
切换前备份当前清单与配置、验证候选；失败停止候选并保留其身份卷，恢复原受管清单。
不覆盖原数据，不将新身份库回滚成临时验收 schema。

## 真实账号创建流程

1. 身份管理员使用 Keycloak 官方 bootstrap 流程一次性建立管理入口，立即创建长期管理账号、
   注册 MFA 并移除 bootstrap 账号。bootstrap 秘密仅在受控交互中处理，不写聊天或仓库。
2. 配置 realm、关闭公开注册、启用忘记密码与暴力尝试防护、设置安全密码策略、强制 MFA；
   OIDC confidential client 只允许精确回调/退出地址。MFA 声明必须来自实际认证流程。
3. 配置并实测 SMTP/企业邮件投递。管理员创建真实用户（不设共享初始密码），通过
   Keycloak 的操作邮件让用户验证邮箱、自己设置密码和注册 OTP。不得输出临时密码名单。
4. 业务管理员通过账号与授权入口创建/邀请员工并分配最小角色和范围；业务邀请投递服务
   必须真实可用，不能用固定 503 替代。用户通过真实 OIDC 登录后激活邀请完成 issuer/subject 绑定。
   第一个业务管理员的受控引导流程仍需核实/补齐；本次未发现可直接执行的正式引导入口，禁止给匿名注册者管理员权限。
5. 用户从稳定业务 HTTPS 地址登录；密码设置/重置在 IdP 页面完成。聊天只展示入口与步骤。

注意：Keycloak 操作邮件和业务邀请投递是两条链路，SMTP 可用不代表业务 outbox 已送达。
正式域名、证书、邮件投递和受控首管理员引导尚待真实配置，当前无可宣称可用的创建入口。

## 必要验收与恢复

同一个持久账号完成创建→设置密码→真实登录→角色权限→退出→错误密码拒绝→停用拒绝；
重新启用后重启该身份项目并以同一账号登录，核对 subject 未变、密码仍可校验、业务绑定仍有效。
验证管理员/维护人/无权普通账号，保留浏览器、API、业务 DB 回查与 IdP 事件证据（不记录秘密）。
单独备份身份库并在另一个隔离目标验证恢复，不用业务测试重置验证持久性。
在这些证据齐备前，环境稳定性、完整密码校验、真实登录和固定第2项均不得标记通过。

参考：[Keycloak 容器运行](https://www.keycloak.org/server/containers)、
[管理员引导与恢复](https://www.keycloak.org/server/bootstrap-admin-recovery)。
