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

## 本地交付基准与账号创建

现有本地项目和业务数据库是唯一功能验收基准；本地与线上业务表结构按一致处理。
稳定本地 HTTPS 入口即可交付，不以公网域名、云部署或邮件服务作为前置。
持久 IdP 使用独立身份库，业务数据继续使用既有数据库，不另起空业务库替代。
切换必须先候选验证、备份和恢复检查点，保护既有端口和受管服务。

首管理员是用户指定的独立 `admin` 账号，由运维手动维护，不对应员工，
不登记地区负责人或做责任区域重叠检查。现有模型要求单位外键，因此开通时创建
`PLATFORM_ADMIN` 技术管理单位，授予 SYSTEM_ADMIN 和已有根区域范围；不修改其他账号。
如果 admin 已存在，只允许绑定原本有效的 SYSTEM_ADMIN，绝不覆盖或提升已有普通账号。

身份管理员在持久 IdP 受控创建 admin，关闭公开注册并要求用户首次设置自己的密码和 MFA。
用户已选择短信或人工转交：允许将短期一次性激活凭证或强制首次修改的临时密码受控转交，
禁止将用户最终密码写入明文名单。IdP 保存不可逆密码哈希，不能从数据库读出原密码。
短信网关尚未配置；人工转交是用户已允许的渠道。不得把固定 503 当投递成功。

## 首管理员一次性开通入口

受保护 POST `/api/v1/identity/invitations/first-administrator`，请求仅含 `token`。
运维在0600运行配置中临时设置：
- `QIQIHAR_FIRST_ADMIN_SUBJECT=admin`
- `QIQIHAR_FIRST_ADMIN_PROVIDER_SUBJECT`：从持久 IdP 创建结果读取的真实账号ID，不能自行编造。
- `QIQIHAR_FIRST_ADMIN_TOKEN_SHA256`：高熵随机一次性凭证的SHA256十六进制哈希。
- `QIQIHAR_FIRST_ADMIN_EXPIRES_AT`：短期UTC到期时间；未配置默认关闭。

用户必须先真实 OIDC 登录并完成 MFA，使用 activation-bootstrap 获取 CSRF cookie，
再提交一次性凭证。issuer/subject 只来自服务端认证会话，忽略请求伪造的身份字段。
配置的真实 IdP subject、issuer、凭证和期限全部匹配后，在同一事务内创建/核验独立 admin、
保存绑定并写业务审计，随后销毁会话要求重新登录。已有任何系统管理员历史绑定时拒绝；
撤销绑定不会重新打开首管理员入口。开通完成后删除四个临时配置。
没有新增密码表、迁移或匿名管理员授权。后续员工沿用既有账号与授权/邀请入口。

当前实现已编译并完成定向安全链测试和55435事务回滚探针；尚未部署到稳定本地入口，
尚无真实用户设置密码、登录和重启持久性证据。

## 必要验收与恢复

同一个持久账号完成创建→设置密码→真实登录→角色权限→退出→错误密码拒绝→停用拒绝；
重新启用后重启该身份项目并以同一账号登录，核对 subject 未变、密码仍可校验、业务绑定仍有效。
验证管理员/维护人/无权普通账号，保留浏览器、API、业务 DB 回查与 IdP 事件证据（不记录秘密）。
单独备份身份库并在另一个隔离目标验证恢复，不用业务测试重置验证持久性。
在这些证据齐备前，环境稳定性、完整密码校验、真实登录和固定第2项均不得标记通过。

参考：[Keycloak 容器运行](https://www.keycloak.org/server/containers)、
[管理员引导与恢复](https://www.keycloak.org/server/bootstrap-admin-recovery)。
