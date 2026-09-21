# 风险研判数据库访问边界

风险研判服务与现有系统共用 PostgreSQL 实例和数据库，但只允许写入 `risk` schema。现有 Flyway 历史仍是共享数据库的唯一迁移权威；独立风险服务关闭 Flyway，不能自行执行全库迁移。

## 角色

- `qiqihar_risk_runtime`：无登录能力的权限组，只持有 `risk` schema 的运行权限。
- `qiqihar_risk_runtime_login`：风险服务的独立登录账号，继承运行权限，连接数上限为 6。
- 迁移账号：继续使用现有受控迁移账号。V217 之后的风险迁移必须通过契约测试证明不改变现有业务 schema。

风险账号没有现有业务表的写权限。后续确需读取业务事实时，只对明确的只读投影视图授予 `SELECT`，不授予业务 schema 的通用 `USAGE` 或表级写权限。

## 本地配置

密码只能通过环境变量提供，不得提交到 Git：

```bash
export RISK_DB_ADMIN_URL='jdbc:postgresql://127.0.0.1:65432/qiqihar_enterprise_test'
export RISK_DB_ADMIN_USERNAME='qiqihar_test'
export RISK_DB_ADMIN_PASSWORD='<isolated-test-admin-password>'
export RISK_DB_RUNTIME_PASSWORD='<generated-risk-runtime-password>'
export RISK_EXPECTED_DATABASE='qiqihar_enterprise_test'
./scripts/configure-risk-database-access.sh
```

配置完成后以风险账号执行权限核验：

```bash
export RISK_DB_URL="$RISK_DB_ADMIN_URL"
export RISK_DB_USERNAME='qiqihar_risk_runtime_login'
export RISK_DB_PASSWORD="$RISK_DB_RUNTIME_PASSWORD"
./scripts/verify-risk-database-boundary.sh
```

成功输出必须为 `RISK_DATABASE_BOUNDARY_OK`。脚本一旦发现风险账号能写入非 `risk` 表、能在非 `risk` schema 创建对象，或连接了错误数据库，就会非零退出并打印实际对象。

## 云端约束

云端执行相同 SQL 和核验脚本，但管理员连接只在配置时短暂提供。风险运行密码进入服务器密钥存储，不写入部署包。RDS 连接池上限、语句超时、锁等待超时和空闲事务超时不得放宽，除非重新完成现有业务负载门禁。
