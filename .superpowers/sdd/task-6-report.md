# Task 6 市场监测全产品与对象类型迁移执行报告

## 交付结论

Task 6 已在正式后端与正式前端仓库完成。市场采集现以 `MARKET/MONITORING` 为规范页面上下文，覆盖玉米、大豆、稻谷及其全部 15 个适用对象上下文；价格构成、表单字段、字段顺序、中文分组、对象选项和行级动作均由后端/数据库提供，前端不再内置大豆专用业务表单。

代码提交：

- 后端实现：`fdee5af75fa08b380e6fb2683d7ff0c0bfbdd6df`
- 前端实现：`bb1287ffa34963f69aa743d42b2bf608614cef12`

## 接管与迁移审计

本任务接管时，V17 已存在并已安装到受保护测试库。执行前先审计文件与 Flyway 历史，随后冻结 V17，所有补充均通过 V18 前向迁移完成。

- V17 SHA-256：`d33fd96f416c3362c562ed716a5296fa2d506c317cc1161cd85a238a869e5ab3`
- Flyway 已验证 18 个迁移；空库回放和分段回放均到达 v18。
- V18 新增独立 `packaging_amount`，重建数据库生成列 `actual_trade_price`：方向对应基础价 + 车板组成 + 包装组成 + 运费组成。
- V18 新增数据库持有的 11 个核心表单字段、方向/包装选项，并把缺失字段补到三产品市场页面定义。
- 市场事实仍采用规范化定义、对象适用性与记录值模型；未新增 JSON 大字段或业务字段宽表。

## 后端实现

- 新增市场领域状态机、动作策略、价格计算与严格校验；服务层负责当前用户、Asia/Shanghai 服务端时间、适用性、CAS 版本和事务边界。
- JDBC 仓储实现批量列表头、批量事实和动作组装，避免逐行事实查询；详情、创建、修改、提交、审核、退回均使用规范记录和事实表。
- REST 提供列表、详情、表单定义、新建、PUT 保存、提交、审核、退回；写请求在请求体解析前完成身份校验，400/401/409 返回稳定错误码。
- 表单定义返回数据库排序的中文分组：质量指标、采购与成交、销售、加工生产、库存；未知分类立即失败，不静默吞掉数据。
- 15 个产品/对象适用上下文均验证定义；深加工、养殖厂、饲料厂、米厂的关键上下文完成创建、详情、提交、审核、列表回读，采购量与质量字段不丢失。

## 前端实现

- 将市场页面重构为通用 `MarketCollectionPage`，正式默认导航改为 `MARKET/MONITORING`，三产品侧栏统一显示“市场采集”。
- HTTP 仓储对列表、详情、定义与全部写动作进行 Zod 契约校验，并将认证、冲突、校验和意外错误映射为独立失败类型。
- 编辑器按后端 `coreFields`、`groups`、`options` 和排序动态渲染；对象切换原子加载新定义，并清理不再适用的隐藏事实。
- 新建、查看、保存、提交、审核和带用户输入原因的退回均由后端 `allowedActions` 与记录版本驱动。
- pending 写操作完成后的刷新使用最新路由筛选/分页版本，旧请求响应不能覆盖新页面状态。
- 应用层和领域层保持 React 无关；架构依赖检查无违规。

## 测试证据

采用 TDD 完成价格构成、对象适用性、动态表单和路由竞态。Task 6 新增：

- 后端 11 项测试方法/参数化测试入口；参数化展开覆盖 15 个定义上下文和 6 个完整写审流程。后端市场测试套件合计 32 项。
- 前端 9 项 Vitest（参数化展开后），覆盖三产品动态表单、退回原因、认证/冲突/校验错误、对象定义竞态和产品上下文竞态。
- Chromium 4 项场景：三产品动态 DOM、关键对象/分组/动作/实际成交价，以及严格失败关闭与 pending submit 的真实 back/forward。

最终验证结果：

- `mvn verify`：172 tests，0 failures，0 errors，构建成功。
- `npm run verify`：Prettier、ESLint、dependency-cruiser、架构探针、97 Vitest、TypeScript/Vite build、10 Chromium E2E 全部通过。
- `playwright test --project=chromium --repeat-each=3`：30/30 通过，无固定 sleep。
- `npm audit --audit-level=high`：0 vulnerabilities。
- 两仓 `git diff --check`：通过。

## 1280 × 720 布局验证

市场 E2E 在真实 Chromium 1280 × 720 视口逐产品验证：

- 顶栏与主内容无覆盖；主区位于顶栏下方。
- 侧栏实际宽度严格为 230px，主区从侧栏右侧开始。
- 筛选区高度小于 120px，保持紧凑密度。
- 分组表头和关键字段可见，宽表产生真实横向滚动。
- 分页底栏可见；三产品切换后标题、列和表单字段随服务端定义变化。

## 资料来源与边界

实现参考只读旧系统资料：

- `/Users/federal/Desktop/cofco-qiqihar-enterprise-web/src/prototype/marketMonitoringData.ts`
- `/Users/federal/Desktop/cofco-qiqihar-enterprise-infrastructure/docs/plans/2026-08-02-foundation-vertical-slice.md`
- `/Users/federal/Desktop/cofco-qiqihar-enterprise-infrastructure/docs/architecture/adr/0001-greenfield-system-boundaries.md`

未修改旧 dashboard 或旧 enterprise-web。浏览器测试使用严格、未知请求即失败的正式 API 契约夹具；后端真实 PostgreSQL/Flyway/JDBC/MockMvc 路径由 `mvn verify` 覆盖。未执行 push、合并或工作区清理。
