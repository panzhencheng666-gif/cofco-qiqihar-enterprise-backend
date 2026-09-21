# 业务会话闲置 8 小时

用户于 2026-09-13 明确选择“闲置 8 小时（推荐）”。

将应用默认 `server.servlet.session.timeout` 从 `${QIQIHAR_SESSION_TIMEOUT:30m}` 改为 `${QIQIHAR_SESSION_TIMEOUT:8h}`。生产以 `QIQIHAR_SESSION_TIMEOUT=8h` 显式覆盖，使当前已发布应用无需替换业务 JAR 即可生效。

此值是 Servlet 会话两次关联 HTTP 请求之间允许的最大闲置时间，不是从登录开始固定 8 小时后强制退出。正常业务请求刷新访问时间；后台请求也可能刷新此计时，不将它宣称为严格按鼠标键盘活动计算的空闲锁屏。

已核对认证链：业务 API 使用服务端已认证 HttpSession 和当前账号权限，未按 OIDC ID token 的 exp 在每次业务请求强制注销。统一登录服务当前为 SSO idle 1800 秒、SSO max 28800 秒、access token 300 秒。它们控制统一登录会话/令牌，不能与业务 HttpSession 时长混为一谈；本次仅修改用户要求的业务闲置时长，不延长访问令牌或取消账号撤销检查。

运行变更：备份原始环境配置、systemd 单元和容器检查信息（含服务凭据的备份仅保存在服务器 0700 目录、0600 文件）；保持原镜像 ID、JAR 挂载、端口、账号配置，重建同名业务容器加载环境变量；保留已停止的旧容器作为回退。检查服务 active、内部 health UP、容器中最终变量为 8h。失败自动恢复原单元、环境文件和旧容器。

配置生效需要重启业务后端，旧内存会话可能需要重新登录。不会声称已经实等 8 小时验证到期；验收依据为生效配置、框架闲置语义、启动健康和公网登录入口。

参考：
- https://docs.spring.io/spring-boot/appendix/application-properties/
- https://tomcat.apache.org/tomcat-10.1-doc/servletapi/jakarta/servlet/http/HttpSession.html
- https://www.keycloak.org/docs/latest/server_admin/#_timeouts

## 应用结果

2026-09-13 13:05:46，云助手执行 `t-bj06wwufa0o13wg` 成功，耗时 8 秒。容器最终配置确认 `QIQIHAR_SESSION_TIMEOUT=8h`，内部 `/actuator/health` 返回 UP，systemd 服务 active。

- 原配置备份：`/var/lib/cofco/backups/idle-eight-hours-20260913-130538`。
- 已停止的原容器：`cofco-cloud-oidc-backend-20260910-idle-rollback-130538`。
- 公网真实登录入口正常打开密码登录页；旧业务会话因本次重启需要重新登录。此次尚未由用户完成重启后的真实登录，不宣称已验证新会话持续 8 小时。
