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

## Review Round 1 修复附录

Round 1 的 7 项 Important 与 2 项 Minor 已全部修复。对应代码提交：

- 后端：`ba9977cf62a4d834528921cdc3256d61c8bd6b5a`
- 前端：`40a3ba06cd8fcdadba5e744f8f32408715144714`

### 后端修复

- 状态迁移不再以 `reportedAt` 写入 `updated_at`。服务通过注入的 Asia/Shanghai `Clock` 取得可信 `Instant` 并显式传给仓储；提交、审核、退回测试均以固定时钟验证 `updated_at` 精确值，同时证明 `reported_at` 保持不变。
- 核心字段定义改为一次字段查询加一次选项批量查询；普通选项与产品适用对象选项在同一 `UNION ALL` 查询中按 `fieldCode` 分组。代理数据源查询计数测试固定验证总计 2 次查询，消除 1+N。
- 市场表单定义先验证正式 `MARKET/MONITORING/productCode` 页面上下文，再检查对象适用性；未知、大小写变体、空白及重复查询参数均稳定返回 400 `INVALID_MARKET_RECORD`。共享页面定义查询新增公开的 `hasDefinition` 应用层边界，Spring Modulith 校验无越界依赖。
- 新增前向迁移 V19，未修改 V17/V18。V19 增加只读 `MKT_REPORTED_AT` 定义并投影到三产品列表，在核心字段和页面列元数据中补充采购/销售基础价“不含组成费用”与实际成交价“已含组成费用”的中文说明。
- 列表、详情和表单定义分别验证填报时间：列表返回 `MKT_REPORTED_AT`，详情返回独立 `reportedAt`，表单返回 `READONLY_DATETIME`；交易日期仍保持独立字段。

### 前端修复

- 市场定义 Zod 契约对未知核心字段失败关闭，并映射为独立 `DEFINITION` 错误；编辑器的稳定字段映射不再对未知字段返回空节点，页面显示明确管理员提示。
- 行动作增加同步 `inFlight` 门闩，且 `ListWorkbench` 在请求与最新列表刷新完成前禁用页级/行级动作。成功与冲突双击测试均证明同一版本只发送一次请求，后到事件不能覆盖结果。
- 新增纯领域实际成交价预览：按购销方向选择对应基础价，再精确累加车板、包装、运费；以四位小数文本输出，不使用浮点数，并按服务端 18,4 范围对不完整、负数、超精度、超范围和求和溢出返回空预览。
- 编辑器和列表动态呈现填报时间与采购、销售、实际成交价说明；详情只读填报时间与交易日期分离。

### 浏览器契约与工作流

- Chromium 市场夹具改为严格有状态记录模型，真实处理 POST 新建、PUT 保存、提交、退回、再次保存/提交和审核；每次写入在修改前校验完整请求体、状态与 CAS 版本，400/409 请求不产生部分写入。
- DOM 场景真实填写价格组成并验证 `2424.0000`/`2422.0000`/`2090.0000` 预览与列表回读，输入用户退回原因，再执行最终审核；同时直接验证陈旧版本 409、未知请求体 400 与记录数不变。
- 三产品 1280×720 场景验证填报时间、三类价格语义说明、230px 侧栏、顶栏/主区不重叠、紧凑筛选区、分页可见和宽表横向滚动。

### Round 1 TDD 与最终证据

- 后端初始定向回归：30 项中 4 项按预期失败；修复后 30/30 通过。
- 前端初始定向回归：24 项中 11 项按预期失败；补充范围与冲突用例后最终定向 27/27 通过。
- `mvn verify`：176 tests，0 failures，0 errors，JAR 构建成功；架构与空库/分段 Flyway v19 回放通过。
- `npm run verify`：Prettier、ESLint、dependency-cruiser、架构探针、111 Vitest、TypeScript/Vite 构建及 11 Chromium E2E 全部通过。
- `playwright test --project=chromium --repeat-each=3`：33/33 通过。
- `npm audit --audit-level=high`：0 vulnerabilities。
- V17 SHA-256：`d33fd96f416c3362c562ed716a5296fa2d506c317cc1161cd85a238a869e5ab3`。
- V18 SHA-256：`06fc9bf97a30d8e9db8a1fb546d54e6daee239478e3437a45d1c086c39efd2ae`。
- 两仓 `git diff --check` 通过；保留 `codex/formal-rebuild` 分支，未 push、未合并、未清理工作区。

## Review Round 2 修复附录

Round 2 的 5 项 Important 与 3 项 Minor 已全部修复。对应代码提交：

- 后端：`f4af9f5c3a921543653dadf2696163502a2fd25b`
- 前端：`226e35f8fb23f64e917162be03656d6a2a7c2bc7`

### 写入、刷新与价格语义

- 新建、提交、退回已区分“写入失败”和“写入成功但列表刷新失败”。后者显示明确提示，重试只执行刷新；三类回归测试均验证写入调用次数始终为 1。
- 前端价格预览逐项采用与后端 `MarketPricing` 一致的 `HALF_UP` 四位小数舍入，再精确求和并检查 18,4 范围；不再读取旧实际成交价作为兜底。
- 价格用例覆盖 `1.00005 -> 1.0001`、基础价与三个组成项分别舍入后的 `1.0004`、购销方向切换缺失对应基础价、清空、负数和越界。

### 元数据驱动核心值与查询边界

- 核心值的 API、应用层和前端领域模型统一改为 `fieldCode -> value` 映射；前端不再持有固定核心字段枚举或 DTO 属性，后端接口层也不暴露固定字段属性。
- 前向迁移 V20 为字段定义增加后端绑定、前端能力、必填属性和 `TEXT` 控件，并新增规范化扩展值表；数据库新增的 `MKT_SOURCE_NOTE` 字段无需修改前端即可显示、提交并在详情/列表回读。
- 服务端按数据库元数据验证未知字段、只读字段、选项、对象适用性、日期、区域、文本和小数，严格拒绝伪字段/伪对象且不产生写入；类型化领域模型继续持有价格与状态不变量。
- `PageDefinitionRepository.exists(BusinessPageKey)` 使用单条 `SELECT EXISTS`；`PageDefinitionQuery.hasDefinition` 不再传递 primitive clump 或加载整个页面。查询计数测试固定验证存在性 1 次、核心字段定义 2 次、完整页面定义 6 次。
- 市场 application/domain 保持 React 无关；架构测试仅对声明命名接口所需的 `package-info` 作精确排除，实际领域类仍受框架依赖规则保护。

### 正式契约、CAS 与布局

- 市场 E2E 使用一份正式共享产品/对象/事实适用矩阵，全部字段和对象均为 V17/V18/V20 的真实代码；匹配采用产品与对象的严格交集，不再使用伪代码。
- 负向契约在运行时构造伪字段与伪对象，均返回 400 且记录数不变；静态扫描确认仓库中无被禁伪代码字面量。
- 陈旧 CAS 场景在 `PENDING_REVIEW` 且“审核”为合法动作时发送旧版本，断言 409，并逐项确认状态、版本和事实保持不变。
- 1280 × 720 的三产品布局验证加入真实边界几何：数据区不压住分页、表头不覆盖首行、行操作不重叠；同时验证横向滚动可到达末列以及前后分页可用。

### Round 2 TDD 与最终证据

- 后端初始定向回归按预期暴露存在性查询、完整定义查询和代码键核心值契约问题；修复后定向测试全绿。
- 前端初始定向回归分别暴露价格舍入/无兜底、动态字段契约以及三类写后刷新语义问题；修复后相关定向用例 35/35 通过。
- `mvn verify`：180 tests，0 failures，0 errors，JAR 构建成功；架构及空库/分段 Flyway v20 回放通过。
- `npm run verify`：Prettier、ESLint、dependency-cruiser、架构探针、119 Vitest、TypeScript/Vite 构建及 11 Chromium E2E 全部通过。
- `playwright test --project=chromium --repeat-each=3`：33/33 通过。
- `npm audit --audit-level=high`：0 vulnerabilities。
- V17 SHA-256：`d33fd96f416c3362c562ed716a5296fa2d506c317cc1161cd85a238a869e5ab3`。
- V18 SHA-256：`06fc9bf97a30d8e9db8a1fb546d54e6daee239478e3437a45d1c086c39efd2ae`。
- V20 SHA-256：`b300decdfe59730f5f0325034be3637fafc5e9c3b3c25a4e31d9054d7d1347e5`。
- 禁用伪代码、固定前端核心字段和 application/domain React 引用的静态扫描均为 0 命中；两仓 `git diff --check` 通过。
- 保留 `codex/formal-rebuild` 分支，未 push、未合并；旧 dashboard 与旧 enterprise-web 均未修改。
