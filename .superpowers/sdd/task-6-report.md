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

## Review Round 3 修复附录

Round 3 的 5 项 Important 与 3 项 Minor 已全部落在正式后端与正式前端仓库。代码提交：

- 后端：`876ca28324e0eb8766ff9e2062d3269ea54c347d`
- 前端：`b70a6318f1735f7ea282ca711ef951031e9766a5`

### 写入成功与刷新失败边界

- 生产监测的新建、保存、提交、审核与退回均把业务写入和后续列表刷新拆成两个阶段；刷新失败显示“写入已成功”的明确提示，重试只刷新当前列表，不重放 mutation。
- 新建、提交、退回使用独立回归用例；保存、审核使用参数化回归。每个用例均断言 mutation 调用次数严格为 1，失败后的重试只增加查询次数。
- 生产页面定向 TDD 首次运行 27 项中 5 项按预期失败，修复后 27/27 通过。

### 产品适用核心扩展与数据库约束

- `findCoreFields(productCode)` 现通过正式 `MARKET/MONITORING` 页面定义与页面字段挂载查询核心定义；核心扩展不再无条件对所有产品暴露。
- 前向迁移 V21 新增 `market_core_field_applicability`，以产品、页面字段、字段定义和 `EXTENSION` 绑定的复合外键约束扩展适用性；扩展值同时保存记录产品与绑定，并受记录产品及字段适用性的双重外键保护。
- 新增只挂载于玉米的 `MKT_CORN_SOURCE_NOTE`。玉米创建、列表和详情可完整回读；大豆定义中不出现该字段，越权写入稳定返回 400 且记录数不变。
- 类型化绑定在数据库中唯一；字段元数据的 binding/control/capability/required 组合由 V21 精确 `CHECK` 约束。普通扩展只允许 `TEXT`/`DECIMAL`，不支持的扩展 `REGION_HIERARCHY` 被数据库拒绝。
- V21 数据库约束测试覆盖合法玉米扩展、跨产品扩展、类型化字段写入扩展表、重复类型绑定、非法元数据组合和错误 capability。

### 失败关闭与数据完整性

- 服务层在定义读取和写入前验证全部 12 个类型化绑定恰好各一个、字段代码无重复、控制类型/capability/required/小数元数据组合完全匹配；损坏定义稳定返回 500 `MARKET_DEFINITION_INVALID`。
- 事务化故障注入分别覆盖非法组合、重复绑定和错误 capability；三种情况下定义 GET 与写 POST 都失败关闭，事务回滚不污染正式测试数据。
- 列表组装对类型化核心值、扩展值和事实字段执行不可覆盖合并；列表或详情发现冲突、或详情发现不属于记录产品的扩展时，稳定返回 500 `MARKET_DATA_INTEGRITY`。
- 核心字段请求保留显式 `null` 键到校验阶段，因此未知字段 `null` 和只读字段 `null` 均被拒绝，不再因规范化丢键而绕过校验。

### 十进制、正式契约与架构门禁

- 核心字段与事实字段只接受普通十进制文本：可带可选负号，不允许正号或指数形式。后端在任何 `BigDecimal` 转换前先匹配词法；随后领域规则继续拒绝负价格/数量与越界值。
- 前端精确价格预览使用同一普通十进制词法和符号解析；正式浏览器夹具的提交验证与后端一致。`+1`、`1e3`、`1E3` 在核心值和事实值路径均返回 400 且无写入。
- 后端新增 V21 市场契约摘要门禁，覆盖所有产品/对象/事实定义、精度/小数位、适用排序、核心定义及页面排序、对象中文名和排序。固定摘要为 `041cb147446cbd70cffb648b856b9ed71a3b6ec1ee34e8e4141107bcf338d4e0`。
- 正式前端夹具单独锁定契约版本、上述后端摘要、`PROTEIN=蛋白/scale=1`、`TEST_WEIGHT=容重/scale=0`、全部 15 个产品对象中文名与排序，以及关键对象的事实适用排序；响应由该契约生成，测试不再以同一错误响应作为唯一预期。
- package-info 架构豁免缩窄为精确 `org.springframework.modulith.NamedInterface`；外部伪框架 package annotation 的 RED 探针证明不会被忽略，普通领域类的严格规则保持不变。

### V21 回放、TDD 与最终证据

- V17–V20 保持冻结，SHA-256 分别为：
  - V17 `d33fd96f416c3362c562ed716a5296fa2d506c317cc1161cd85a238a869e5ab3`
  - V18 `06fc9bf97a30d8e9db8a1fb546d54e6daee239478e3437a45d1c086c39efd2ae`
  - V19 `c7dd9c6d4064ebfc947b359260f4be8a0023b72fdcdb550c41e83cdaa2438a7c`
  - V20 `b300decdfe59730f5f0325034be3637fafc5e9c3b3c25a4e31d9054d7d1347e5`
- V21 SHA-256：`b8446a51c15fac0c4de3f358b78c2595b0494ede809ff279270c9f9d27763cad`。空库一次应用 21 个迁移，V10 后分段一次应用 11 个迁移到 v21，第二次运行执行 0 个迁移；页面字段计数更新为 88。
- 定义故障注入 3/3 RED 后 3/3 GREEN；列表/详情碰撞 1/1 RED 后 1/1 GREEN；架构外部 package annotation 探针 1 项 RED 后架构测试 5/5 GREEN；事实十进制正号/指数用例 RED 后 GREEN。
- `mvn -q verify`（JDK 21）：191 tests，0 failures，0 errors，0 skipped；Spring Modulith、ArchUnit、真实 PostgreSQL、Flyway 空库/分段回放和 JAR 构建全部通过。
- `npm run verify`：Prettier、ESLint、dependency-cruiser、架构探针、129 Vitest、TypeScript/Vite 构建及 11 Chromium E2E 全部通过。
- 市场、生产监测和生产 API 契约三套 Chromium 测试 `--repeat-each=3`：33/33 通过；市场单套 Task 6 E2E：5/5 通过。
- `npm audit --audit-level=low`：0 vulnerabilities；两仓 `git diff --check` 通过，禁用伪代码与已纠正中文伪标签静态扫描 0 命中。
- 两个正式仓库均保留 `codex/formal-rebuild` 分支；未 push、未合并。旧 dashboard 与旧 enterprise-web 仅作只读审计，未由本轮修改。

## Review Round 4 修复附录

Round 4 的 5 项 Important 与 2 项 Minor 已全部完成。实现提交：

- 后端：`a558e01b56b3ca04b04aba0d15f96ec6897de8d`
- 前端：`88720fb796013c4a880927782dce3c6139e7e9c8`

### 生产写命令同步防重

- 生产监测的新建、保存、提交、审核和退回共用同步 `mutationInFlight` 门闩；同一 JavaScript tick 内的第二次调用在 React 状态提交前即被拒绝。
- 门闩覆盖业务 mutation 和随后列表刷新全过程。页面把 `commands.loading` 传给 `ListWorkbench.actionsDisabled`，页级按钮、行操作、编辑器提交和退回确认在 mutation/refresh 期间全部禁用。
- 异常路径在活动上下文内可靠释放门闩；上下文切换或卸载同时使旧请求失效并重置门闩，不会永久粘住。
- 参数化测试覆盖 CREATE、SAVE、SUBMIT、APPROVE、RETURN：同 tick 双触发、mutation deferred、refresh deferred 期间继续点击，均严格只有一次 mutation；刷新完成后恢复操作。RED 时 32 项中 5 项按预期失败，修复后 32/32 通过。

### V22 数据完整性与扩展定义对称约束

- V17–V21 保持冻结，所有数据库修复仅通过前向迁移 `V22__close_market_context_and_extension_invariants.sql` 完成。
- `market_record` 的产品/对象父上下文更新现在检查已有规范化事实适用性。CORN/FEED_MILL 的 `TEST_WEIGHT` 直接改为 SOYBEAN/DEEP_PROCESSOR 会在数据库边界失败并保持记录与事实原子不变；仅含共同适用 `MOISTURE` 的合法更新通过。
- 应用读取事实时同时校验记录当前产品/对象适用性；历史损坏数据的列表和详情均失败关闭为 500 `MARKET_DATA_INTEGRITY`，不再静默呈现不适用事实。
- 页面挂载与 `market_core_field_applicability` 之间新增双向、可延迟、初始延迟的精确约束，允许 definition + page mount + applicability 在同一事务完整安装，同时拒绝缺失、删除和跨产品不配对。
- 仓储以 mounted/mapped `FULL OUTER JOIN` 读取核心定义，EXTENSION 必须同时存在挂载和 mapping；missing/extra 故障注入下定义 GET 与写 POST 均返回 500 `MARKET_DEFINITION_INVALID`。
- V22 direct-SQL 约束测试 4/4 通过；定义故障注入从新增 2 项 RED（错误返回 200）修复为全量 5/5 GREEN；数据故障关闭 2/2 GREEN。

### 见证字段清理、升级保留与真实合同

- V22 移除 Round 3 临时见证字段 `MKT_CORN_SOURCE_NOTE` 的 core definition、field definition、页面挂载、列配置与 applicability。迁移先检查与永久 `MKT_SOURCE_NOTE` 的值冲突，再把真实旧值安全迁入永久字段，避免覆盖或静默丢失。
- `MKT_SOURCE_NOTE` 的开发态 core/column description 已清空；动态扩展测试改为 `@Transactional`/`@Rollback` 隔离夹具，在同一事务插入 definition、field definition、page mount、column 与 applicability，不向正式主数据泄漏测试字段。
- 分段回放在 V20 写入真实 `MKT_SOURCE_NOTE` 记录值，随后单独执行 V21 backfill 和 V22 清理；每一阶段均验证值、产品和 EXTENSION 绑定保持可读，第二次迁移执行 0 项。最终共 22 个迁移、页面字段 87 个。
- 前端把全部核心定义、option、description、binding、capability、定义排序和页面排序移入 `market-contract.ts`，`market-api` 直接由该定义生成响应，不再同时硬编码响应和断言。
- 前端按后端相同 FACT/OBJECT/CORE 行协议进行规范排序、换行序列化并实际计算 SHA-256。V22 双端真实摘要为 `0efc5505da3daff584e7af903e2dba0ca58e513aa56c9e5ba5ae4c61a77a7ac2`；后端快照测试与前端 canonical hash 测试均通过。

### 扩展值 CAS 与十进制矩阵

- 永久 `MKT_SOURCE_NOTE` 覆盖创建、详情、列表、PUT 修改、PUT 清空与陈旧 CAS。修改从 v0 到 v1，清空从 v1 到 v2 并确认规范化值行删除；随后使用 v1 的陈旧 PUT 返回 409，扩展值和事实均无部分写入。
- 事务动态产品扩展覆盖 CORN 创建/详情/列表/定义完整回读；SOYBEAN 定义不可见，越权写入 400 且记录数不变。
- 共享普通十进制解析器通过五类事实参数矩阵验证：QUALITY、PURCHASE、SALES、PROCESSING、INVENTORY 分别对 `+1`、`1e3`、`1E3` 返回 400 且零写入，每类合法普通十进制均成功创建并规范为四位小数。
- `MarketMonitoringRestIntegrationTest` 参数展开后 36/36 通过；V21/V22 约束组合 6/6 通过。

### Round 4 最终证据

- `mvn -q verify`（JDK 21）：203 tests，0 failures，0 errors，构建成功；真实 PostgreSQL、Flyway 空库/分段回放、Spring Modulith 与 ArchUnit 全部通过。
- `npm run verify`：Prettier、ESLint、dependency-cruiser、架构探针、134 Vitest、TypeScript/Vite build 和 11 Chromium E2E 全部通过。
- `playwright test --project=chromium --repeat-each=3`：33/33 通过；没有固定 sleep。
- `npm audit --audit-level=low`：0 vulnerabilities；两仓 `git diff --check` 通过；前端生产/fixture 静态扫描仅保留一项“不得出现旧见证字段”的负向断言。
- V17–V21 工作树 diff 为 0；冻结 SHA-256 仍为 V17 `d33fd96f416c3362c562ed716a5296fa2d506c317cc1161cd85a238a869e5ab3`、V18 `06fc9bf97a30d8e9db8a1fb546d54e6daee239478e3437a45d1c086c39efd2ae`、V19 `c7dd9c6d4064ebfc947b359260f4be8a0023b72fdcdb550c41e83cdaa2438a7c`、V20 `b300decdfe59730f5f0325034be3637fafc5e9c3b3c25a4e31d9054d7d1347e5`、V21 `b8446a51c15fac0c4de3f358b78c2595b0494ede809ff279270c9f9d27763cad`。V22 SHA-256 为 `c5bb425053bf90fd1b4e44c0b292197bb724a6c97f843a777b4b7aa7c5ba1f10`。
- 两个正式仓库均保留 `codex/formal-rebuild` 分支；未 push、未合并。旧 dashboard 与旧 enterprise-web 本轮保持只读。

## Review Round 5 修复附录

Round 5 的 5 项 Important 与 3 项 Minor 已全部完成。实现提交：

- 后端：`7339a8e`（`fix: close market integrity symmetry gaps`）
- 前端：`0845de1`（`fix: make production commands and market contract race-safe`）

### V23 对称约束与任意事务顺序

- V17–V22 保持冻结，数据库修复全部进入前向迁移 `V23__make_market_integrity_declarative_and_symmetric.sql`。迁移先诊断 extension mount/mapping 和 fact applicability 历史脏数据；诊断包含具体 product/field 或 record/context/fact，失败时 Flyway 整体回滚并保持原版本与数据。
- `market_core_field_applicability -> page_definition_field` 与 `-> market_core_field_definition(code, domain_binding)` 外键改为 `DEFERRABLE INITIALLY DEFERRED`。页面挂载、mapping 与核心定义三侧的 constraint trigger 在事务最终态统一校验，并以事务级 advisory lock 串行化同一 product/page/field，允许 mount→mapping、mapping→mount 以及跨产品 child-first/parent-first remount，同时拒绝最终 missing、extra、typed→extension、definition insert/update/delete 绕过。
- 事实表新增并回填 `product_code`、`object_type_code`，随后设为 `NOT NULL`。事实通过 deferred composite FK 指向 `(record_id, product_code, object_type_code)` header，并以 `ON UPDATE CASCADE` 跟随父上下文；另一条 deferred composite FK 指向 `(product_code, object_type_code, fact_code)` applicability。父 header 和 applicability 提供 PostgreSQL 被引用端所需的非延迟唯一键，写入端外键在事务最终态校验。
- V22 的 immediate parent guard 与 fact trigger 被 V23 declarative FK 取代。直接 SQL 证明父上下文先改、事实随后原子替换合法；最终不适用则整笔失败。双连接并发用例证明已验证但未提交的 fact 持有父键锁，applicability delete 等待，fact 提交后 delete 由 FK 失败关闭，不发生 write skew。
- 正式 REST PUT 验证 CORN/FEED_MILL + `PROCESSING_INPUT` 可在同一请求改为 CORN/BREEDING_FACTORY + `PURCHASE_VOLUME`，返回 v1；随后旧 v0 PUT 返回 409，详情仍为 v1 新上下文与新事实。

### 读取失败关闭与升级生命周期

- 扩展值批量读取现在只接受记录当前 product 下同时存在的 `MARKET/MONITORING` page mount、EXTENSION core definition 与 applicability。非碰撞但未挂载的历史扩展值不再进入列表；列表和详情均稳定返回 500 `MARKET_DATA_INTEGRITY`。历史 header/fact context 损坏的故障注入也按 V23 composite FK 形态更新。
- 分段回放在 V20 创建真实 `MKT_SOURCE_NOTE` 值。V21 再创建 witness/common 冲突，V22 首次迁移给出冲突诊断、保持 version=21 且两值不变；只移除冲突 common 后，V22 把 witness 安全迁为永久字段。V23 对 extension mismatch 与 inapplicable fact 分别进行失败/原子性证明，清理脏数据后成功升级。
- V20 记录直到 V23 后仍未删除：正式 Spring `MarketMonitoringService` 先 detail 读出旧 extension，再以 CAS save 修改到 v1、clear 到 v2；旧 v1 save 被 `ConflictException` 拒绝，最终 list/detail 均保持 v2 清空状态，完成后才清理回放夹具。

### 生产命令 ownership 与共享 V23 contract

- 生产监测 mutation 使用独立 owner token，不再依赖普通 request version。mutation pending 期间 VIEW/NEW 可推进展示版本，但不能释放 mutation ownership；无论 mutation resolve/reject，原 owner 都会释放，`loading` 由 pending operation set 精确计算。NEW 完成后迟到 VIEW 不覆盖编辑器，mutation 完成后下一次 SUBMIT 可正常进入。
- 市场 contract 升级为 V23：canonical 行覆盖 CATEGORY、OBJECT、FACT 的全部 response fields、CORE description/全部元数据与 CORE_OPTION。后端使用 `COLLATE "C"` 后按 UTF-8 求 SHA-256；前端使用显式 Unicode code-point comparator 与 UTF-8 hashing，双方摘要一致为 `16bbb60018df7c34f7a0cc2ccef4e577fcc75813139506d1108b6368ba5ae4c61a77a7ac2`。
- 后端数据库 mutation 证明 core description、option label/order、category label/order 任一漂移都会改变摘要；前端另证明 core/fact description、option label/order、category label/order 均进入 serializer。市场 API fixture 的 editable core codes、price-component/base-price codes 与 fact groups 改由共享定义和类别派生，不再维护平行硬编码清单。

### Round 5 TDD 与最终证据

- V23 约束定向测试先在 V22 上 4/4 按预期失败，实施后扩展为 5/5 GREEN；V22 既有约束门禁适配 deferred 最终态后 4/4 GREEN。脏扩展 list/detail 用例先暴露 list 200，修复后数据失败关闭 3/3 GREEN。
- 正式市场 REST 集成 37/37 GREEN；生产 mixed-command ownership 初次 2 项按预期失败，修复后整套 34/34 GREEN；V23 双端 canonical 测试后端 5/5、前端 3/3 GREEN；Flyway 分段与正式服务生命周期 8/8 GREEN。
- `mvn -q verify`（JDK 21）：211 tests，0 failures，0 errors，0 skipped；JAR、真实 PostgreSQL、Flyway 空库/分段回放、Spring Modulith 与 ArchUnit 全部通过。
- 前端最终各门禁均通过：Prettier、ESLint、dependency-cruiser、并发架构探针、137 Vitest、TypeScript/Vite production build、11/11 Chromium E2E。未执行无新增证据的 `repeat-each=3`。
- 最终验证前曾误用 pnpm 改写本地 `node_modules` 布局；未跟踪 `pnpm-lock.yaml` 已精确删除，并以 `npm ci` 恢复 `package-lock.json` 环境。源码和 lockfile 均未切换包管理器；恢复后架构探针立即通过。
- 冻结迁移 SHA-256 保持：V17 `d33fd96f416c3362c562ed716a5296fa2d506c317cc1161cd85a238a869e5ab3`、V18 `06fc9bf97a30d8e9db8a1fb546d54e6daee239478e3437a45d1c086c39efd2ae`、V19 `c7dd9c6d4064ebfc947b359260f4be8a0023b72fdcdb550c41e83cdaa2438a7c`、V20 `b300decdfe59730f5f0325034be3637fafc5e9c3b3c25a4e31d9054d7d1347e5`、V21 `b8446a51c15fac0c4de3f358b78c2595b0494ede809ff279270c9f9d27763cad`、V22 `c5bb425053bf90fd1b4e44c0b292197bb724a6c97f843a777b4b7aa7c5ba1f10`。V23 SHA-256 为 `c726ab4eeb3c386b55a117c4866d700c7ce2d12eaaa3687cb44f0f5446d2661b`。
- 两个正式仓库均保留 `codex/formal-rebuild` 分支；未 push、未合并。旧 dashboard 与旧 enterprise-web 全程只读。

## Review Round 6 修复附录

Round 6 仅处理 2 项 Important 与 1 项 Minor。实现提交：

- 后端：`b90ec30`（`fix: classify market monitoring field sources`）
- 前端：`ba03025`（`fix: separate production mutation ownership`）

### V24 市场页面字段来源图

- V17–V23 完全冻结；新增前向迁移 `V24__classify_market_monitoring_page_fields.sql`。来源判定不硬编码 `MKT_REGION`，而是由三类正式数据库元数据组成：CORE=`market_core_field_definition`、FACT=`market_fact_definition`、SYSTEM_PROJECTION=`market_monitoring_projection_field_definition`。`MKT_STATUS` 作为显式 `RECORD_STATUS` projection 定义登记，仍是合法页面字段。
- 新增 `market_core_typed_binding_requirement` registry，声明 12 个正式 typed binding。V24 preflight 同时要求每个 `MARKET/MONITORING` mount 恰好归属一个来源、每个 typed binding 全局恰好一份 definition、每个产品页面恰好挂载一份各 typed binding，并要求显式 required projection 挂载。
- 回放在 V22 删除 `MKT_REGION` core definition 后先成功安装 V23，再安装 V24；V24 给出包含 `MKT_REGION` 的 source-invariant 诊断，Flyway 保持 version=23、三产品挂载和 V20 业务值不变。恢复正式 definition 后 V24 成功；独立 Boot 测试证明正常空库 V1→V24 连续两次启动稳定。
- V24 在 page definition、page field、core definition、fact definition、projection definition 与 typed requirement registry 全部安装 `DEFERRABLE INITIALLY DEFERRED` constraint trigger。最终态守卫拒绝删除产品必需 typed mount、删除被挂载 core、删除被挂载 fact source、删除 required `MKT_STATUS` projection，同时允许同事务按任意顺序完成合法图变更。

### 独立 mutation owner 与事实响应元数据

- 生产写命令 owner 改为独立 `{token, requestVersionAtStart}`；普通 VIEW/NEW 只推进 request version，不再使 mutation 失去 ownership。mutation resolve 无条件执行一次列表 refresh；reject 即使普通请求已推进仍保留写错误。写命令只在 UI request version 未推进时关闭自己的 editor/return UI，因此后来打开的 VIEW/NEW editor 不被覆盖。
- mutation owner 在 finally 精确释放，`loading` 由普通 pending requests 与独立 mutation owner 联合计算；mixed resolve/reject 后均恢复，下一次 mutation 可执行。现有参数化 mixed 测试只增加必要断言：resolve refresh=1、reject 错误可见、later editor 保持，不新增同义矩阵。
- 市场 fixture 的 `factField` 不再写死 `DECIMAL`、`null`、`18`；`label`、`valueType`、`unit`、`description`、`precision`、`scale` 全部取共享 fact definition，`sortOrder` 取正式产品/对象 applicability contract，category 仍由共享 category/filter 驱动。现有 contract mutation 测试增加一条响应映射断言。

### Round 6 最小验证证据

- Backend RED：`FlywayMigrationReplayTest` 8 项中仅主回放 1 项按预期失败，明确因为不存在 V24、无法产生 `V24 preflight ... MKT_REGION` 诊断。GREEN：回放 8/8、Boot 1/1，共 9/9；未运行 backend full suite。
- Frontend RED：两份定向测试共 37 项，仅新增的 mixed resolve、mixed reject 与 fact response mutation 3 项失败；GREEN 37/37。随后仅对 4 个改动文件执行 Prettier 与 ESLint，并执行 `tsc -b`，全部通过。
- 未运行 full frontend suite、Chromium E2E 或 repeat3。V17–V23 工作树 diff 为 0；V24 SHA-256 为 `f5928b46bc7a58a8d60d6d160931509497fa3adebb3016f4db64440ec65bf48b`。市场 V23 canonical SHA 保持 `16bbb60018df7c34f7a0cc2ccef4e577fcc75813139506d1108b6368ba5ac278`。
- 两个正式仓库均保留 `codex/formal-rebuild` 分支；未 push、未合并。旧 dashboard 与旧 enterprise-web 保持只读。
