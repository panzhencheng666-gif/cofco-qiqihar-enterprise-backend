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
