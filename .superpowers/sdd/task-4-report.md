# Task 4 实施报告：“我的工作”收敛与任务列表工作台

## 结果

- 状态：`DONE`
- 后端实现提交：`1fe2405`（`feat: add product-independent workflow workbench`）
- 前端实现提交：`ce4d1da`（`feat: add work management task workbench`）
- Round 1 后端修复提交：`130ef67`（`fix: harden workflow workbench review findings`）
- Round 1 前端修复提交：`368f342`（`fix: normalize workflow workbench navigation`）
- Round 2 前端修复提交：`c3c9c1c`（`fix: cancel obsolete work item requests`）
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

### V11：页面身份不可变

- V9/V10 已发布后保持不变；新增 forward-only `V11__make_page_identity_immutable.sql`，无 seed 或数据重写。
- 通用 `BEFORE UPDATE` guard 覆盖 `page_definition`、`page_presentation` 以及所有仍同时保留 legacy context 与 surrogate FK 的 11 个关联表，共 13 个 trigger。
- `page_definition_id`、`page_presentation_id`、`product_code`、`business_domain`、`page_kind` 创建后不可变，避免 surrogate 关联与 canonical context 查询分裂；title 等非身份字段仍可更新。
- replay 明确先升级到 V10，再执行 V11；定义/展示/子表身份更新均被数据库拒绝，非身份更新后 `JdbcPageDefinitionRepository` 仍完整返回唯一 workflow definition。

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

### Round 1 复核修复

1. 页面身份 RED：V10→latest 实际执行 0 而非预期 V11 的 1，且 `page_definition.page_kind` 更新未被拒；V11 后 replay 8/8、身份拒绝与 canonical repository 查询 GREEN。
2. 大页码 RED：`2147483647 * 100` 在 workflow 与既有 market repository 中溢出为负 OFFSET，真实 REST 返回 500；两个 JDBC adapter 均显式转为 long 后返回一致空页与正确 metadata，workflow repository/REST 与 market REST GREEN。
3. workflow 分页 RED：空库深链只请求 `[1]` 而非 `[1,0]`，结果缩页只请求 `[2]` 而非 `[2,1]`；单次 clamp/refetch 后 GREEN，正常有效页保持一次请求。
4. 竞态/history：每次响应及 refetch 后检查 request version；stale 越界响应不会触发第三次请求、覆盖当前结果或调用 `onQueryNormalized` 改写当前 history。真实 App 深链把空库 `page=1` replace 为 `page=0`。
5. pending 状态只保留 backend definition 驱动的 `ListWorkbench` select；筛选变更仅更新草稿并重置到第 0 页，点击“查询”后才请求，不再有重复 tabs/buttons。
6. Shell 删除虚构“待办 9”；active route 使用不含 query 的 hash pathname，待办/已办和顶部“我的工作”正确激活并提供 `aria-current="page"`。

### Round 2 生命周期修复

1. RED：真实 App 从 deferred `pending?page=2` 切换并完成 completed page0 后，再 resolve 旧 pending 越界响应，旧实例额外请求 `PENDING page0`，并可能用旧 callback 将 completed hash normalize 回 pending。
2. GREEN：`WorkItemsPage` effect cleanup 在作废 definition lifecycle 的同时立即推进 list request generation；初次列表、clamp refetch、catch/finally 的既有 version guard 因此会拒绝所有旧生命周期 continuation。
3. deferred 回归确认 pending 只请求 page2；completed 结果和 hash 保持不变，旧响应不再 refetch、set state、显示错误/加载态或调用旧 `onQueryNormalized`。既有空库 clamp、缩页 clamp、普通 stale 与有效页单请求测试继续通过。

## 主要文件

### 后端

- `src/main/resources/db/migration/V9__generalize_product_optional_page_identity.sql`
- `src/main/resources/db/migration/V10__create_workflow_work_items.sql`
- `src/main/resources/db/migration/V11__make_page_identity_immutable.sql`
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

- 后端 fresh `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn verify`：92 tests，0 failures，0 errors；可执行 jar 构建成功。
- `ArchitectureTest`：4/4；test-database safety architecture 2/2；Spring Modulith、domain purity 与受保护测试数据库规则全部通过。
- 前端 fresh `npm run verify`：Prettier、ESLint 通过；dependency-cruiser 43 modules / 98 dependencies、0 violations；Vitest 12 files / 43 tests；TypeScript/Vite 107 modules transformed。
- Flyway latest 为 11；V5→V8 执行 3，V8→V10 执行 2，V10→V11 执行 1，二次启动执行 0；duplicate NULL page context 被数据库拒绝。
- `git diff --exit-code 933dbef -- V1...V10` 通过；V1–V10 未改写，V5 固定 checksum 回归继续通过。
- 正式前端源码扫描无旧 `#/我的工作`、`待我处理`、`退回与异常`；待填报/待审核/退回补充/异常处理只由后端 definition 返回，不在前端生产源码硬编码。
- 正式前端非测试源码无 `CORN/SOYBEAN/RICE/玉米/大豆/稻谷/固定日期` 业务 hardcode；work-management UI/App 无 `fetch`。
- V10 无生产 work item/node/responsible/audit INSERT；无 `ALL`/虚拟产品/sentinel；没有假 action 定义或响应。
- V11 无 INSERT；所有 repository 分页 offset 均先提升为 long，全仓无裸 `int pageNumber * pageSize`。
- 两仓 `git diff --check` 通过。

## 自审

- [x] 侧栏“我的工作”只有待办任务、已办事项；completed 不复制状态菜单。
- [x] pending 只有一个状态 select；全部/四状态 label 与 code 来自 DB/canonical definition，筛选后点击查询才请求。
- [x] 列包含任务、业务域、地区、产品、业务期间、截止时间、流程节点、状态、责任人；无 handler 时不返回操作 action。
- [x] hash/history/deep-link、真实 HTTP、服务端分页、越界 clamp/refetch、stale response、空库 0、错误重试均有生产组合测试。
- [x] scope replacement/unmount 会立即取消旧 definition/list/refetch lifecycle；旧 pending 响应不能污染 completed state 或 hash。
- [x] shared `ListWorkbench` 保持单一横向滚动容器；1280×720 不引入 fixed/absolute/sticky 表头叠压。
- [x] 旧仓库只读；未修改 plan/ledger。
