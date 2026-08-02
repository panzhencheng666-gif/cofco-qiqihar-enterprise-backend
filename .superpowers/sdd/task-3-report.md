# Task 3 实施报告：通用业务页面定义与列表查询内核

## 结果

- 状态：`DONE`
- 后端实现提交：`8e0cc83`（`feat: add business page definition kernel`）
- 前端实现提交：`1286f59`（`feat: add definition-driven list workbench`）
- 旧仓库保持只读；未修改计划或 ledger。

## 边界与设计结论

- canonical 接口为 `GET /api/v1/page-definitions/{domain}/{pageKind}?productCode=`，与 Task 2 的 `/api/v1/master-data/page-definitions` 并存，集成测试同时验证两条路由。
- 真正跨业务域的页面 key、页面定义、分页结果和查询端口位于后端 `shared/domain`、`shared/application`；事务边界在 application service，JDBC 在 infrastructure，HTTP DTO 映射在 interfaceadapter。
- 页面定义的展示元数据由 `platform` 表持久化。V5 只为既有已确认的稻谷/大豆质量定义建立真实展示映射；没有创建未确认的玉米质量字段、默认日期、状态或记录。
- 前端 `shared/application/page-definition` 只包含页面定义、查询状态、分页结果和端口；HTTP 解码位于 `shared/infrastructure/page-definition`；`shared/ui/list-workbench` 不调用 HTTP、不导入业务模块。
- 旧产品专用生产页已由定义驱动的 `MarketCollectionPage` 替换，移除了 `SOYBEAN`、`2026-07-31`、固定状态选项及固定业务列表布局。App 只接受 URL 提供的 `domain/pageKind/productCode`，缺少上下文时不猜测默认产品。
- 地区使用 `GET /api/v1/regions?parentCode=` 逐级读取根或一个父节点的直接子级；前端 selector 按 `undefined -> selected parent id` 请求，不需要平铺行政村。

## RED → GREEN 记录

### 后端 RED

1. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest=BusinessPageKeyTest,PagedResultTest,PageDefinitionControllerTest test`
   - RED：测试编译失败，缺少 `BusinessPageKey`、`PageDefinitionQuery`、`BusinessPageDefinition`，符合预期。
2. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest=DefaultPageDefinitionQueryTest,JdbcPageDefinitionRepositoryTest,PageDefinitionRestIntegrationTest test`
   - RED：缺少 `JdbcPageDefinitionRepository`，符合真实定义 adapter 尚未实现的预期。
3. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest=RegionHierarchyControllerTest,JdbcMasterDataRepositoryTest test`
   - RED：缺少 `findRegionChildren` 和 `RegionHierarchyController`，符合逐级地区能力尚未实现的预期。

### 后端 GREEN

1. shared key、分页结果和 canonical HTTP 契约：5 tests，0 failures。
2. query service、JDBC 真实定义、canonical REST 集成：5 tests，0 failures。
3. 地区 roots/直接子级 repository 与 controller：6 tests，0 failures。
4. `ArchitectureTest`：4 tests，0 failures；Spring Modulith 与 ArchUnit 均通过。
5. 首次全量 `mvn package`：47 tests 中 46 通过、1 失败。根因为 `BootFlywayStartupTest` 仍断言旧迁移数 4，V5 后实际为 5；更新测试期望后，目标测试 1/1 通过。
6. 最终 `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -q package`：退出码 0，47/47 tests 通过，生成 `target/grain-trade-enterprise-backend-0.0.1-SNAPSHOT.jar`。

### 前端 RED

1. `npm test -- src/shared/ui/list-workbench/ListWorkbench.spec.tsx src/shared/application/page-definition/HttpPageDefinitionGateway.spec.ts`
   - RED：2 suites 因深模块尚不存在而失败；随后按层归属将 HTTP spec 放到 `shared/infrastructure`。
2. 地区 HTTP adapter 目标测试：RED 为 `getRegionChildren is not a function`。
3. 通用市场页面目标测试：RED 为 `MarketCollectionPage` 尚不存在。
4. 分页配置行为测试：RED 为找不到“每页条数”选择器。

### 前端 GREEN

1. 三产品 fixture、定义驱动默认日期/状态/字段、分组表头、逐级地区与 canonical HTTP adapter：最初 6 tests，0 failures。
2. 地区 HTTP adapter 加入后目标组合：7 tests，0 failures。
3. 定义驱动 `MarketCollectionPage`：1 test，0 failures。
4. 分页配置行为：`ListWorkbench` 目标 6 tests，0 failures。
5. 最终 `npm run verify`：
   - Prettier：通过。
   - ESLint：0 warnings/errors。
   - dependency-cruiser：29 modules、49 dependencies，0 violations。
   - Vitest：5 files、10 tests，全部通过。
   - `tsc -b && vite build`：通过，104 modules transformed。

## 主要文件

### 后端新增

- `src/main/java/com/cofco/qiqihar/graintrade/shared/domain/BusinessPageKey.java`
- `src/main/java/com/cofco/qiqihar/graintrade/shared/domain/BusinessPageDefinition.java`
- `src/main/java/com/cofco/qiqihar/graintrade/shared/application/PageDefinitionQuery.java`
- `src/main/java/com/cofco/qiqihar/graintrade/shared/application/PageDefinitionRepository.java`
- `src/main/java/com/cofco/qiqihar/graintrade/shared/application/DefaultPageDefinitionQuery.java`
- `src/main/java/com/cofco/qiqihar/graintrade/shared/application/PagedResult.java`
- `src/main/java/com/cofco/qiqihar/graintrade/shared/infrastructure/JdbcPageDefinitionRepository.java`
- `src/main/java/com/cofco/qiqihar/graintrade/shared/interfaceadapter/PageDefinitionController.java`
- `src/main/java/com/cofco/qiqihar/graintrade/masterdata/interfaceadapter/RegionHierarchyController.java`
- `src/main/resources/db/migration/V5__create_business_page_definition_kernel.sql`
- 对应 shared/controller/repository/REST/地区测试共 7 个新增测试文件。

### 后端修改

- `MasterDataQuery`、`MasterDataQueryService`、`MasterDataRepository`、`JdbcMasterDataRepository`：增加直接子地区查询。
- Flyway 启动/重放测试：迁移总数更新为 5。
- 既有 master-data 测试 stub 与 repository 测试：覆盖新端口且保持旧契约。

### 前端新增

- `src/shared/application/page-definition/index.ts`
- `src/shared/infrastructure/page-definition/HttpPageDefinitionGateway.ts`
- `src/shared/ui/list-workbench/ListWorkbench.tsx`
- `src/shared/ui/list-workbench/RegionHierarchyFilter.tsx`
- `src/shared/ui/list-workbench/index.ts`
- `src/modules/market-monitoring/ui/pages/MarketCollectionPage.tsx`
- 对应 workbench、HTTP adapter、地区 adapter、业务页面测试。

### 前端修改/替换

- `App.tsx`：从外部上下文读取页面 key，组合真实 definition/region adapters。
- `HttpMasterDataRepository` 与其端口/类型：新增逐级子地区请求。
- `global.css`：共享工作台的地区层级和分页样式。
- 删除产品专用的 `SoybeanMarketCollectionPage` 及其测试，替换为已测试的通用 `MarketCollectionPage`。
- 市场记录状态解码改为服务端字符串，不再在前端枚举业务状态。

## 自审

- [x] canonical 路由与 Task 2 查询不冲突。
- [x] domain 不依赖 Spring/JPA/HTTP；application 不含 HTTP DTO 映射。
- [x] shared 是跨业务页面内核，不含地区、产品、对象类型或业务字段常量。
- [x] UI 不调用 `fetch`，HTTP 只在 infrastructure。
- [x] 同一个 `ListWorkbench` 通过玉米、大豆、稻谷三份测试 fixture；生产代码没有三产品分支。
- [x] 默认日期、状态选项、字段、动作和分页配置均来自定义；未发明玉米质量字段或默认上下文。
- [x] 真实页面定义当前 filters/defaultContext 为空是有意行为：基础数据尚无经确认值，接口返回空结构而不是猜测。
- [x] 地区只加载 roots 或直接 children，未平铺全部层级。
- [x] 紧凑筛选、面包屑、分组表头、横向滚动、每页条数和前后翻页均封装在 shared UI 深模块。
- [x] `git diff --check`、架构测试、全量 package、前端 verify 均通过后才提交。

## Round 1 复核修复（2026-08-02）

### 提交

- 后端修复提交：`97123de`（`fix: harden business page definition contracts`）。
- 前端修复提交：`e7bd8b6`（`fix: connect definition-driven workbench navigation`）。

### Critical 修复结果

- `App` 与 `EnterpriseShell` 统一使用 `#/pages/{domain}/{pageKind}/{productCode}` 路由；产品切换写入 history，`popstate/hashchange` 恢复页面上下文。无 deep link 时只从后端返回的首个适用产品建立初始路由，不猜产品 code。
- 产品导航来自 `GET /api/v1/master-data/products?domain=MARKET&pageKind=QUALITY`。生产 `src` hardcode scan 未发现 `CORN/SOYBEAN/RICE/玉米/大豆/稻谷/2026-07-31`。
- 生产组合注入既有 `HttpMarketCollectionRepository`：定义加载后立即查询；筛选提交和翻页均更新 URL 并查询；失败保持工作台、显示中文错误并可重试；请求序号阻止 stale response 覆盖新结果。
- 未实现的 `VIEW` seed 已删除，前端不会展示无 handler 的动作。

### Important / Minor 修复结果

- canonical 未知定义返回统一 `404 PAGE_DEFINITION_NOT_FOUND`；空白 key 返回受控 `400 INVALID_PAGE_KEY`。
- 增加 `GET /api/v1/regions/{regionCode}/path`，地区级联成为受控组件，可由 default/query 恢复路径，外部 reset 可同步；清空下级回退到最近祖先。
- 地区和列表均有 loading、中文错误、重试及 stale-response 防护。
- 空 column group 的两层表头与表体均保留一个占位单元格；field React key 使用 group + field；DB 同页 field 跨组唯一。
- 地区筛选使用 `fieldset/legend`；分页按钮有中文 `aria-label`，当前页使用 `aria-current` 且不是无行为按钮。
- V5 通过 deferred FK/constraint trigger 保证 presentation 必有 pagination、default page size 属于 options，进而至少保留一个 size option。`20/50/100` 明确记录为 Task 3 平台交互配置，不是业务主数据，也不是从黄金截图提取的数据。
- 针对反馈截图的系统级布局缺陷，shared `ListWorkbench` 改为单一 `.ledger-scroll` 横向滚动容器；table 使用 `width: max-content; min-width: 100%`，表头保持 normal flow，无 fixed/absolute/sticky；筛选标签禁止换行，约 1020px 内容区中的约 2095px 分组表不再互相覆盖或裁切标签。

### Round 1 RED → GREEN 与最终门禁

- 后端 RED：新增接口后目标集合先在 test compile 阶段因 `findRegionPath` 等缺失失败；随后覆盖 404/400、地区 path、适用产品过滤、动作移除和 V5 一致性约束。
- 前端 RED：4 个新增/扩展测试文件最初为 8 failed / 6 passed，失败覆盖动态产品导航、真实查询/翻页/history、列表 retry、空组对齐、受控地区 path/retry/stale 和宽表 CSS。
- 后端目标测试：30 tests，0 failures，0 errors。
- 前端目标测试：6 files、17 tests，全部通过。
- 后端最终 `mvn package`：54 tests，0 failures，0 errors；可执行 jar 构建成功。
- 前端最终 `npm run verify`：Prettier 通过；ESLint 0 warnings/errors；dependency-cruiser 35 modules/66 dependencies、0 violations；Vitest 8 files/19 tests 全部通过；TypeScript/Vite build 105 modules transformed。
- 两仓 `git diff --check` 均通过。旧 dashboard backend/frontend 仓库存在任务开始前的脏状态，本轮仅作只读状态确认，未向旧仓库写入文件。

## Round 2 复核修复（2026-08-02）

### 提交

- 后端修复提交：`4c57def`（`fix: add canonical paged market read model`）。
- 前端修复提交：`427d1e2`（`fix: use canonical server-paged market records`）。

### 契约与分层结果

- 正式列表端点统一为 canonical `GET /api/v1/market-records`，查询包含 `productCode`、`pageKind`、`pageNumber`、`pageSize` 和 `filter.*`；响应统一为 `{data:{items,pageNumber,pageSize,totalElements,totalPages}}`。
- 后端前置了 Task 6 的只读市场查询基础：`market` 模块严格拆分 domain/application/interfaceadapter/infrastructure；domain 仅包含不可变 record/query，事务边界在 application reader，PostgreSQL/JDBC/JSON 仅在 infrastructure，HTTP DTO 仅在 interfaceadapter。未实现写入、提交或审核。
- V7 建立 `market.market_record_projection` 正式 PostgreSQL 读投影并通过页面 presentation 外键约束上下文；生产迁移没有 `INSERT`，41 条分页记录只存在于测试数据库 fixture。
- 前端 repository 直接解码服务端分页 DTO；第一页、第二页和末页与后端 Spring/PostgreSQL 集成测试使用同一 `id + values` 行结构和 `41 / 20 / 3` 分页基数，不再对裸数组二次 `slice`。
- 页面定义加载后对 deep link/history 白名单化：仅保留 definition filters，pageSize 必须属于定义 options，非法页码归一为默认页；超出末页的合法页码依据服务端 metadata 归一并重新查询。
- 损坏 percent encoding 显示中文受控地址错误，不抛 `URIError`；domain/pageKind/productCode 与动态产品导航不一致时归一到首个真实适用上下文；页面定义返回的 key 不一致时显示中文受控错误。产品导航失败可中文重试。
- `/api/v1/master-data/products` 保持零参数返回全部产品；domain/pageKind 同时提供时过滤；只给一个、空白或不完整组合返回受控 `400 INVALID_PAGE_APPLICABILITY`。

### V5 恢复与前向迁移

- V5 使用 git 提交 `8e0cc83` 的原文件内容恢复，SHA-256 为 `b7969f210f73ffd3654b33444691f0fba32474eab51a5d4a7faea0043a214404`；`git diff --exit-code 8e0cc83 -- V1...V5` 通过。
- Flyway 固定 V1–V5 checksum：V1 `578287895`、V2 `-1029775028`、V3 `-1102740881`、V4 `2052234299`、V5 `-1133431193`。
- V6 承接 Round 1 曾直接写入 V5 的 VIEW 删除、同页字段唯一、默认 page size 外键、presentation 必有 pagination 等修复；迁移测试先执行旧 V5，再升级 V6/V7，并验证二次启动幂等。
- pagination 复合 key 的 UPDATE constraint trigger 分别检查 OLD 与 NEW identity。专门的回归测试先在临时缺少 UPDATE/OLD 检查时红灯，确认 key 移动被放行；恢复检查后迁移测试 4/4 通过，旧 presentation 不会失去 pagination。

### Round 2 RED → GREEN

- 后端初始迁移 RED：恢复原 V5 后，3 项中 2 项失败，分别证明 latest 前向修复缺失及删除 pagination 未被拒绝。
- 后端 OLD-key 专项 RED：`FlywayMigrationReplayTest` 4 项中 1 项失败，报错为“Expecting code to raise a throwable”，证明 UPDATE 移动复合 key 时旧 presentation 未校验；实现 OLD+NEW 后 4/4 通过。
- 前端初始 RED：`App.spec.tsx` 与新 HTTP adapter spec 共 8/8 失败，覆盖旧端点/裸数组、缺少 pageKind、非法 filter/pageSize、损坏编码、上下文错配、history 和产品导航 retry。
- 前端定向 GREEN：App 与 HTTP adapter 2 files、10 tests 全部通过。
- 后端定向 GREEN：market REST、master-data REST、ArchitectureTest 共 11 tests 全部通过；Flyway 迁移回放 4 tests 全部通过。
- 首次后端全量门禁为 58 tests 中 1 项架构失败：V5 定向 Flyway 构造绕过受保护测试数据库入口。将 target-version 构造收回 `ProtectedTestDatabase` 后门禁通过；随后增加 PostgreSQL 动态 filter 跨契约测试，最终为 59/59。

### 最终门禁与扫描

- 后端最终 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn verify`：59 tests，0 failures，0 errors；可执行 jar 构建成功。
- 前端最终 `npm run verify`：Prettier、ESLint、dependency-cruiser 全部通过；9 files、27 tests 全部通过；TypeScript/Vite build 105 modules transformed。
- 两仓 `git diff --check` 通过。正式源码扫描未发现 `/api/v1/market-collections`，未发现 `records.slice`、`items.slice` 或 `.slice(start` 的客户端分页；前端非测试源码未发现 `CORN/SOYBEAN/RICE/玉米/大豆/稻谷/2026-07-31/2026-08-02` 业务 hardcode；V7 未发现 `INSERT` 或测试记录。
- 旧 dashboard backend/frontend 仓库继续保持只读；未修改计划或 ledger。

## Round 3 复核修复（2026-08-02）

### 提交

- 后端修复提交：`a97695a`（`fix: validate market query and scalar values`）。
- 前端跨仓契约提交：`4524e94`（`test: align market record scalar contract`）。

### 服务端页面定义白名单

- `MarketRecordController` 继续只解析 HTTP 参数和基本协议形状，没有承载页面定义规则。
- `DefaultMarketRecordReader` 通过 shared application 的 `PageDefinitionQuery.allowsListQuery` 加载真实 `BusinessPageDefinition`；pageSize 必须属于 `pageSizeOptions`，全部 filter code 必须存在于 definition filters，否则抛出受控 `400 INVALID_MARKET_RECORD_QUERY`，非法查询不会到达 PostgreSQL repository。
- 不再单独用 presentation existence 查询代替页面定义校验；未知页面仍由定义查询返回 `404 PAGE_DEFINITION_NOT_FOUND`。
- 动态 filter 集成测试先在 test DB 的 `platform.page_filter_definition` 声明 `subjectName`，测试结束即删除；没有绕过页面事实或向生产迁移写入该 filter。

### 动态单元格标量契约

- 统一跨仓行值契约为 `string | number | null`。`MarketRecord` domain 构造时拒绝 boolean、array 和 nested object；JDBC JSON 解码不能把其他类型带入 application/HTTP。
- 新增 forward-only `V8__enforce_scalar_market_record_values.sql`。immutable SQL predicate 配合 table CHECK 检查 JSON object 的每个顶层 value，只允许 `string`、`number`、`null`；数据库测试分别覆盖 boolean、array、nested object 非法插入。
- V7 未修改。V8 不包含生产 `INSERT`，已有空生产投影继续为空。
- 后端 Spring/PostgreSQL REST 与前端真实 adapter fixture 使用同一结构：`record-41` 的 values 为 `{subjectName:"记录41", score:41.5, note:null}`；前端 zod schema 继续严格拒绝其他 JSON 类型。

### RED → GREEN

- 首个 RED 为 test compile 失败：`DefaultMarketRecordReader` 只有 repository 构造参数，缺少加载 `PageDefinitionQuery` 的应用层依赖。
- 仅接入该依赖后获得行为 RED：目标集合 14 tests 中 7 failures、0 errors。两项 application 测试证明非法 size/filter 到达 repository；domain 测试证明 boolean 未被拒绝；REST `pageSize=7` 期望 400 实际 200；Boot/Flyway 期望 8 个迁移实际 7；数据库 boolean 插入未被拒绝。
- 实现 application 白名单、domain guard 和 V8 后，同一目标集合 14/14 通过；拆分 size/filter REST 场景后 `MarketRecordRestIntegrationTest` 5/5 通过。前端 adapter 定向 1 file、2 tests 通过。
- 首次后端全量门禁 65 tests 中 1 error：market application 直接引用 shared domain 未暴露类型，违反 Spring Modulith 边界。将定义加载与规则读取封装在已暴露的 shared application 方法，market application 只消费 boolean 并决定受控错误；架构与 application 定向 6/6 通过。

### 最终门禁与扫描

- 后端 fresh `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn verify`：66 tests，0 failures，0 errors；可执行 jar 构建成功。
- 前端 fresh `npm run verify`：Prettier、ESLint、dependency-cruiser 全部通过；9 files、27 tests 全部通过；TypeScript/Vite build 105 modules transformed。
- `git diff --exit-code 4c57def -- V1...V7` 通过；V1–V7 SHA-256 分别为 `843903c9...`、`6373625c...`、`59054dce...`、`52b5b3e0...`、`b7969f21...`、`ba67fe85...`、`7413c84e...`，均未改写。
- 两仓 `git diff --check` 通过。正式源码无旧 `/api/v1/market-collections`；前端无 `records.slice`、`items.slice` 或 `.slice(start` 二次分页；前端非测试源码无产品/日期业务 hardcode；V8 无生产 seed。
- 旧 dashboard backend/frontend 仓库继续保持只读；未修改计划或 ledger。

## Round 4 复核修复（2026-08-02）

### 提交

- 后端修复提交：`5d08e2b`（`fix: reject ambiguous market query parameters`）。
- 前端无代码或测试改动；仍执行 fresh 全量门禁。

### 协议形状与多值校验

- `MarketRecordController` 只允许 `productCode`、`pageKind`、`pageNumber`、`pageSize` 四个核心参数，或符合 `filter.<identifier>` 的筛选参数；未知非 filter 参数、拼写错误、空 code、重复点号和其他畸形名称统一返回受控 `400 INVALID_MARKET_RECORD_QUERY`。
- 每个出现的 request parameter 必须恰有一个值；重复核心参数和重复 filter 均在构造 `MarketRecordQuery` 前拒绝，不再调用 `getFirst()` 静默选值。
- 单个 filter 值的空白语义明确为非法请求，避免空值被丢弃后扩大结果集。合法单值 `filter.subjectName=记录21` 仍由 application 的 PageDefinition 白名单验证并返回唯一记录。
- 页面定义白名单没有移动到 controller。畸形名测试先在 test definition 中故意声明 `.subjectName`，请求 `filter..subjectName` 仍由协议层拒绝，证明 400 不依赖 application 的“未定义 filter”结果。

### RED → GREEN 与最终门禁

- 真实 Spring/PostgreSQL REST RED：11 tests 中 6 failures、0 errors。重复空+有效 filter、重复核心 pageSize、`pageNubmer` 拼写错误、`filter.` 空 code、已声明 `.subjectName` 对应的畸形参数名、单值空白 filter 均期望 400 而实际返回 200；合法单值 filter 和其余既有场景保持通过。
- 实现 controller 协议形状/多值校验后，同一目标测试 11/11 通过，0 failures、0 errors。
- 后端 fresh `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn verify`：72 tests，0 failures，0 errors；可执行 jar 构建成功。
- 前端 fresh `npm run verify`：Prettier、ESLint、dependency-cruiser 全部通过；9 files、27 tests 全部通过；TypeScript/Vite build 105 modules transformed。
- `git diff --exit-code a97695a -- src/main/resources/db/migration` 通过，本轮未改写 V1–V8。两仓 `git diff --check` 通过；扫描无旧 endpoint、`getFirst()` 歧义读取、客户端二次分页、前端生产业务 hardcode 或新增生产 seed。
- 旧 dashboard backend/frontend 仓库继续保持只读；未修改计划或 ledger。

## Round 5 复核修复（2026-08-02）

### 提交

- 后端修复提交：`dadedb0`（`fix: parse market pagination from raw query`）。
- 前端无代码或测试改动；仍执行 fresh 全量门禁。

### 原始参数优先的数值解析

- `MarketRecordController` 不再将 `pageNumber`、`pageSize` 预绑定为 `int`，也不再单独预绑定其他核心参数；唯一 HTTP 输入是原始 `MultiValueMap<String, String>`，因此 Spring 数值转换不会先于控制器协议校验发生。
- 控制器先完整检查全部参数名、每个参数恰好一个值、值非空白，再读取必需核心参数并手工执行整数解析与范围校验；缺失、空白、非数字、溢出、重复及混合非法值均统一返回受控 `400 INVALID_MARKET_RECORD_QUERY`。未提供 `pageNumber` 时仍使用协议默认值 `0`。
- 动态 filter 的页面定义白名单仍由 application 的 `DefaultMarketRecordReader` 通过 `PageDefinitionQuery.allowsListQuery` 执行；controller 只承担 HTTP 协议形状和标量解析，没有承载页面事实。
- 真实 Spring/PostgreSQL REST 覆盖空白+合法重复 `pageSize`、非数字+合法重复 `pageNumber`、单个空白 `pageSize`、单个非数字 `pageNumber` 和合法数值分页；Round 4 的所有名称、多值、filter 和页面定义场景继续保留。

### RED → GREEN 与最终门禁

- REST RED：16 tests 中 4 failures、0 errors。四个新增非法数值场景均由 Spring `MethodArgumentTypeMismatchException` 提前拦截，实际错误码为通用 `BAD_REQUEST`，而不是期望的 `INVALID_MARKET_RECORD_QUERY`；合法数值场景和全部既有场景通过。
- 改为 raw `MultiValueMap` 优先校验并手工解析后，同一目标测试 16/16 通过，0 failures、0 errors。
- 后端 fresh `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn verify`：77 tests，0 failures，0 errors；可执行 jar 构建成功。
- 前端 fresh `npm run verify`：Prettier、ESLint、dependency-cruiser 全部通过；9 files、27 tests 全部通过；TypeScript/Vite build 105 modules transformed。
- `git diff --exit-code a97695a -- src/main/resources/db/migration` 通过，本轮未改写 V1–V8。两仓 `git diff --check` 通过；正式源码扫描无旧 endpoint、`getFirst()`、market controller 数值预绑定、客户端二次分页或前端生产业务 hardcode；V8 无生产 seed。
- 旧 dashboard backend/frontend 仓库继续保持只读；未修改计划或 ledger。
