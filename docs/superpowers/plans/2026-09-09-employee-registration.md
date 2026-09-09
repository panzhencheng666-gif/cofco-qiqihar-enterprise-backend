# Employee Registration Implementation Plan

**Goal:** 已确认的普通员工注册后立即登录业务系统。
**Architecture:** Keycloak 管理密码与认证，注册控制器从会话提取身份；业务事务复用邀请选项和校验并创建普通账号及绑定。独立中文静态注册页面由现有 HTTPS 入口提供。
**Tech Stack:** Spring Boot / JDBC / Keycloak / HTML JavaScript.

## Global Constraints
- 固定 BUSINESS_OPERATOR，服务器拒绝其他角色；保留原管理员与邀请流程。
- 测试写入仅 55435，独立 realm/端口；不重置真实密码。

## Steps
- [x] 增加 EmployeeRegistrationService/Controller；共享 IdentityGovernanceService 的选项过滤和校验；测试非法单位、角色、用户名和重复提交。
- [x] 精确放行已认证未绑定账号的 registration/options GET 和 registration POST，CSRF 保持有效。
- [x] 创建 register.html，加载与邀请相同的单位、岗位、地区；角色固定普通；完成后重新认证。未开通页面添加注册链接，Keycloak 启用注册。
- [x] 在独立认证 realm 实际创建用户，提交业务注册，重新登录与 DB 回查，验证普通授权与管理员拒绝；失败时定位根因。
- [x] 清理隔离数据，提交检查点，部署已测包与页面，确认健康后通知复测。

## 验证结果
7 个定向 JUnit 检查通过；10 项真实 OIDC 业务注册回归通过；并发提交为 200/409 且只有一条账号；4 类空值请求均为 400；Keycloak 实际账号注册回调、浏览器资料提交、登录到业务页面与数据库 ACTIVE/BUSINESS_OPERATOR 回查通过。隔离 fixture 已清理，审计保护已恢复。
