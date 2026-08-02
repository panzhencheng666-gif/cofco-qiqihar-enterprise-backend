# Task 4 实施报告：“我的工作”收敛与任务列表工作台

## 结果

- 状态：`DONE`
- 后端实现提交：`1fe2405`（`feat: add product-independent workflow workbench`）
- 前端实现提交：`ce4d1da`（`feat: add work management task workbench`）
- 正式后端基线：`0600745`；正式前端基线：`4524e94`。
- 两个旧 dashboard 仓库只读；未修改 plan 或 ledger。

## 架构与契约

- 后端新增严格分层的 `workflow/{domain,application,interfaceadapter,infrastructure}`：domain 无框架，application 持有只读事务，JDBC 仅在 infrastructure，HTTP 与 DTO 仅在 interfaceadapter。
- 前端新增 `modules/work-management/{domain,application,infrastructure,ui}`：真实 HTTP adapter 位于 infrastructure，`WorkItemsPage` 只消费 application port，并复用 shared `ListWorkbench`、地区层级筛选和服务端分页。
- 正式列表接口为 `GET /api/v1/work-items?scope=PENDING|COMPLETED&status=&domain=&regionId=&productCode=&page=&pageSize=`，响应统一为 `{data:{items,pageNumber,pageSize,totalElements,totalPages}}`。
- `scope=COMPLETED` 禁止 status；unknown 参数、重复参数、空白值、非法数字、未知 scope/status/domain/region/product 和非法 pageSize 均返回受控 `400 INVALID_WORK_ITEM_QUERY`。
- market/workflow controller 共用 `StrictQueryParameters`；Task 3 market raw-query 语义没有复制，也未退回 Spring 数值预绑定或 `getFirst()` 静默取值。
- canonical `BusinessPageKey` / TS `BusinessPageKey` / page-definition HTTP 协议均支持 product optional；market 页面与 market API 仍强制真实 product，不存在 `ALL`、虚拟产品或 sentinel。

## 迁移

### V9：product optional 页面身份

- 新增 `V9__generalize_product_optional_page_identity.sql`，未修改 V1–V8。
- `platform.page_definition` 和 `page_presentation` 使用 surrogate identity；上下文使用 `UNIQUE NULLS NOT DISTINCT (product_code, business_domain, page_kind)`，因此每个真正 product-independent 页面只有一个身份。
- page definition/presentation 的所有展示子表新增 surrogate FK；旧复合上下文列继续用于兼容查询与插入，并由 trigger 解析、校验 surrogate identity。
- `market.market_record_projection` 前向映射到 `page_presentation_id`；迁移测试在 V8 插入真实 projection fixture 后升级 V9/V10，记录数量保持 1，再清理 fixture，证明升级不丢数据。
- V6 pagination 完整性函数前向改为 null-safe `IS NOT DISTINCT FROM`；旧 pagination/default-size/field-placement 约束回归继续通过。

### V10：workflow 正式模型

- 新增 `workflow.work_item_status`、`workflow_node`、`responsible_party`、`work_item`、`work_item_audit_trail` 与 pending/completed 查询索引。
- 只 seed 四个已确认 pending 状态：`TO_FILL/待填报`、`TO_REVIEW/待审核`、`RETURNED/退回补充`、`EXCEPTION/异常处理`。
- `COMPLETED` 仅是 query scope，不是第五状态；DB check 强制 pending 为 `completed_at IS NULL + status_code NOT NULL`，completed 为 `completed_at IS NOT NULL + status_code NULL`。
- seed product-independent `WORKFLOW/WORK_ITEMS` canonical 页面定义、中文筛选/列/分页配置和业务域选项；status 来自 workflow status 表，product 来自正式 products API，region 来自正式 hierarchy API。
- 生产迁移没有 workflow node、responsible party、work item 或 audit seed；latest 后四表记录数分别为 `0/0/0/0`。

## RED → GREEN

### 后端

1. optional key + workflow domain RED：目标测试在 testCompile 以 12 个 `cannot find symbol` 失败，缺少 `WorkItemScope/Status/Query`；最小 domain 与 optional key/controller 后 GREEN。
2. migration RED：V5→latest 预期 5 个迁移实际 3 个，且插入 `(NULL,'WORKFLOW','MIGRATION_TEST')` 被旧 `product_code NOT NULL` 拒绝；V9/V10 后 Flyway replay 7/7 GREEN。
3. product-independent JDBC RED：`JdbcPageDefinitionRepository` 返回 empty；null-safe typed JDBC 参数后 GREEN，既有 RICE/SOYBEAN 定义仍通过。
4. workflow JDBC/REST RED：testCompile 缺少 `JdbcWorkItemRepository`；完成 domain/application/JDBC/controller 后 JDBC 2/2、REST 2/2 GREEN。
5. strict option RED：未知 domain/region/product 实际返回 200；application validation + JDBC DB option existence 后统一 400，Task 3 market parser 16/16 回归通过。
6. 四状态纠正：删除曾短暂设计的第五完成状态，增加 DB XOR scope/state 非法插入测试；domain 2/2、migration 7/7 GREEN。

### 前端

1. shell/work module RED：3 suites 因 `WorkItemsPage`、HTTP adapter 不存在及旧中文 hash 实际值不等于 `#/work/pending` 失败；实现模块与两条正式路由后 GREEN。
2. App RED：`#/work/pending` 被判为无效地址，找不到“任务列表”；增加 work location/hash/history composition 后 GREEN。
3. 动态 product RED：产品 select 只有 DB placeholder，没有“玉米”数据库 option；接入无 page applicability 的正式 products API 后 GREEN。
4. full gate 首次失败：React lint 报 App/WorkItemsPage effect 同步 setState，另有 market callback dependency warning；改为 route-key remount、异步成功更新和稳定 productCode 后 fresh verify GREEN。

## 主要文件

### 后端

- `src/main/resources/db/migration/V9__generalize_product_optional_page_identity.sql`
- `src/main/resources/db/migration/V10__create_workflow_work_items.sql`
- `src/main/java/com/cofco/qiqihar/graintrade/workflow/domain/*`
- `src/main/java/com/cofco/qiqihar/graintrade/workflow/application/*`
- `src/main/java/com/cofco/qiqihar/graintrade/workflow/infrastructure/JdbcWorkItemRepository.java`
- `src/main/java/com/cofco/qiqihar/graintrade/workflow/interfaceadapter/WorkItemController.java`
- `src/main/java/com/cofco/qiqihar/graintrade/shared/interfaceadapter/StrictQueryParameters.java`
- optional canonical key/controller/repository、market parser 与 migration/REST/JDBC/domain 测试。

### 前端

- `src/modules/work-management/domain/workItem.ts`
- `src/modules/work-management/application/ports/WorkItemRepository.ts`
- `src/modules/work-management/infrastructure/http/HttpWorkItemRepository.ts`
- `src/modules/work-management/ui/pages/WorkItemsPage.tsx`
- `src/app/App.tsx`、`src/app/shell/EnterpriseShell.tsx`、optional page-definition adapter、动态 master-data products adapter 与对应测试。

## 最终门禁与扫描

- 后端 fresh `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn verify`：88 tests，0 failures，0 errors；可执行 jar 构建成功。
- `ArchitectureTest`：4/4；Spring Modulith 与 domain purity 全部通过。
- 前端 fresh `npm run verify`：Prettier、ESLint 通过；dependency-cruiser 43 modules / 98 dependencies、0 violations；Vitest 12 files / 35 tests；TypeScript/Vite 107 modules transformed。
- Flyway latest 为 10；V5→V8 执行 3，V8→V10 执行 2，二次启动执行 0；duplicate NULL page context 被数据库拒绝。
- `git diff --exit-code 0600745 -- V1...V8` 通过；V1–V8 未改写，V5 固定 checksum 回归继续通过。
- 正式前端源码扫描无旧 `#/我的工作`、`待我处理`、`退回与异常`；待填报/待审核/退回补充/异常处理只由后端 definition 返回，不在前端生产源码硬编码。
- 正式前端非测试源码无 `CORN/SOYBEAN/RICE/玉米/大豆/稻谷/固定日期` 业务 hardcode；work-management UI/App 无 `fetch`。
- V10 无生产 work item/node/responsible/audit INSERT；无 `ALL`/虚拟产品/sentinel；没有假 action 定义或响应。
- 两仓 `git diff --check` 通过。

## 自审

- [x] 侧栏“我的工作”只有待办任务、已办事项；completed 不复制状态菜单。
- [x] pending 的全部/四状态 label 与 code 来自 DB/canonical definition。
- [x] 列包含任务、业务域、地区、产品、业务期间、截止时间、流程节点、状态、责任人；无 handler 时不返回操作 action。
- [x] hash/history/deep-link、真实 HTTP、服务端分页、空库 0、错误重试均有生产组合测试。
- [x] shared `ListWorkbench` 保持单一横向滚动容器；work status tabs 可横向滚动，1280×720 不引入 fixed/absolute/sticky 表头叠压。
- [x] 旧仓库只读；未修改 plan/ledger。
