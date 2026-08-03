# Task 7 物流监测与供需账户执行报告

## 交付结论与提交

Task 7 已在正式后端和正式前端仓库完成，分支均为 `codex/formal-rebuild`，未 push。实现覆盖玉米、大豆、稻谷三产品的物流事件闭环与可解释供需账户。

- 后端实现：`22248cf70874f9464f302da1a22ab59b2f19dba2`
- 前端实现：`c2270d778d75734c90657ef629530298555d9b8d`
- 本报告：本文件所在后端文档提交（最终 SHA 见交付回执）

## 范围与迁移

- V1–V24 未修改；新增唯一前向迁移 `V25__create_logistics_and_supply.sql`。
- V25 SHA-256：`f8c92749313076f50f578933725ac7171b59fd3817d447a93c38f096358d5a8f`。
- 物流采用规范 `route_event` 与 `route_fact`，运输方式是同一物理事件的明细属性，不作为可重复相加的第二份总量。
- 供需采用版本化公式、输入角色、不可变核定来源发布、采用决定、批准调整、计算运行、来源快照与结果版本。
- 三产品均获得 `LOGISTICS/MONITORING` 与 `SUPPLY/ACCOUNT` 页面定义、中文面包屑、筛选、字段、动作和分页元数据。
- V25 不写入物流节点、物流事件、供需来源或计算结果等业务记录；空库回放断言这些表均为 0。

## 物流闭环

- REST 提供严格列表、详情、新建、PUT 保存、提交、审核通过和带原因退回。
- 状态机为 `DRAFT -> PENDING_REVIEW -> APPROVED`，以及 `PENDING_REVIEW -> RETURNED -> DRAFT`；非法迁移失败关闭。
- 写接口在请求体解析前校验身份；服务端使用 Asia/Shanghai 日期边界、可信当前用户和数据库事务。
- 所有写操作携带 CAS 版本；冲突返回稳定 409，测试证明陈旧请求不覆盖、不产生部分写入。
- 列表以一次头查询和一次事实批量查询组装当前页，不按行查询事实；铁路、公路共用事件模型并均在三产品 REST 场景覆盖。
- 前端通过数据库页面定义渲染筛选、列、运输方式选项、中文标签、单位与动作；编辑器仅维护 API 字段能力映射，不持有业务标签、业务字段顺序或数值/日期默认值。

## 供需公式、符号与来源资格

历史资料中存在相反的库存差额描述。经旧正式 workflow/model 与原型交叉审计，本任务固定唯一口径：

`INVENTORY_RECONCILIATION_DIFFERENCE = SURVEYED_ENDING_INVENTORY - ADOPTED_ENDING_INVENTORY`

中文标签为“库存核对差额（调查期末库存－采用后账面期末库存）”，正值表示调查库存更高。该表达式同时写入 V25 公式版本、数据库列注释、后端计算、REST 契约、前端解释区和 Chromium 断言；示例 `2.750 - 3.000 = -0.250` 全链一致。

- 总供给、总使用、计算期末库存、采用后账面期末库存和库存核对差额均来自版本化公式元数据；API 按公式版本 scale 输出十进制字符串。
- `source_release` 是上游核定通过后形成的不可变版本边界，仅允许 `APPROVED` 发布；草稿/退回记录不能形成 release，因而不可能进入试算或正式结果。
- 每个采用来源保存来源域、记录 ID、来源版本、审批时间、质量状态、来源值、采用值、理由和下钻路由；前端逐项展示来源状态与理由。
- 缺失、重复或 `BLOCKING` 来源只能形成带 validation code 的 `TRIAL`；完整通过来源才可形成候选或正式结果。
- 采用理由和调整理由均必填；采用决定和批准调整均使用 CAS。正式结果引用计算时的来源快照，不复制可变上游行作为第二事实源。

## 前端与浏览器

- 新增 `src/modules/logistics-monitoring` 与计划约定的 `src/modules/supply-analysis`，保持 domain/application/infrastructure/ui 分层。
- App 和侧栏支持规范深链 `LOGISTICS/MONITORING/{product}` 与 `SUPPLY/ACCOUNT/{product}`，产品仍由适用性接口提供。
- 物流页显示数据库驱动的宽表、筛选、铁路/公路明细与后端 `allowedActions`；新建、保存、提交、审核和退回均传递版本。
- 供需页显示公式名/版本/表达式/容差、五项结果、来源值/采用值/理由/状态和来源下钻；重新计算表单在理由为空时不可提交。
- 新增一个 Chromium 聚合工作流：物流铁路草稿提交后刷新为待审核，再进入供需深链验证公式、符号、来源理由并执行带 CAS 的重新计算。

## TDD 与 RED/GREEN 证据

- 领域 RED：物流与供需测试先因状态机、公式和来源类型缺失而编译失败；实现后 3/3 GREEN。
- 迁移 RED：V25 首次暴露公式 SQL 别名歧义和字段定义中文唯一约束碰撞；前向迁移修复后空库、分段和第二次启动 GREEN。
- 供需 REST RED：数据库 `numeric(18,4)` 导致 API 返回 `-0.2500`，违背公式版本 `scale=3`；查询按版本 scale 归一后 GREEN。重跑同时暴露测试清理遗漏 `source_release_value`，修正依赖顺序后单场景 GREEN。
- 前端聚合测试 GREEN：物流元数据与两种状态动作 1/1，供需 formula/sign/source/reason/drilldown/run 1/1；HTTP 契约 2/2。
- Chromium 定向门禁：1/1 GREEN，无固定 sleep。

## 最终验证与审计

后端唯一一次必要全量门禁：

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify`
- 结果：216 tests，0 failures，0 errors；JAR 构建成功；真实 PostgreSQL、Flyway 空库/分段/second startup 通过。

前端唯一一次必要全量门禁：

- `npm run verify`
- 首次在 ESLint 阶段发现供需汇总的 `keyof SupplyAccount` 过宽触发 `no-base-to-string`。收窄为五个字符串结果字段后，仅续跑失败阶段与此前尚未执行的阶段，未重复整套。
- `npm run lint`：通过。
- `npm run architecture && npm run test && npm run build && npm run verify:e2e`：70 modules / 0 dependency violations；141 Vitest 全通过；TypeScript/Vite 构建通过；12 Chromium 全通过。
- Prettier 在全量门禁中已通过；两仓 `git diff --check` 通过。

最终审计未发现需要新增 V26 的问题：V25 是唯一新增迁移，物流列表无 N+1，供需只读批准发布边界，理由/CAS/符号在 DB、API、UI、测试一致。旧 dashboard backend 与旧 enterprise web 仅只读审计，未修改、未提交。未执行 push、合并或破坏性工作区清理。

## Round 1 Review Addendum（2026-08-03）

本轮在冻结 V1–V25 的前提下新增唯一前向修复 `V26__close_logistics_supply_review_findings.sql`，SHA-256 为 `d52e107a08cef9f9a8b9848e1443f651fb3d33de6fd3e36f832c0ebd13989a7f`。未修改旧迁移，未写业务种子，未 push。

- 公式不再执行 Java 固定算术或表达式文本：V26 用 `formula_result_role` 与有序 `formula_term` 回填 V1；服务端按版本、精度、scale、rounding mode、tolerance 执行线性依赖图。V2 系数改变会改变结果，环、缺失 output/operand 均失败关闭。
- 来源发布改为受控服务：生产、市场、物流只能从完全匹配 product/region/id/version/field 的真实 `APPROVED` 上游记录派生值；其他角色只能来自带理由、执行人、时间和版本的 `APPROVED manual_input_decision`。draft、returned、错误版本、错误字段和自报值全部拒绝。
- `source_release`、`source_release_binding`、旧 `source_release_value` 与 approved manual decision 禁止 UPDATE/DELETE。计算引用保存域、记录、版本、字段、单位、审批、质量、角色和来源值完整快照；历史 API 只读快照，不回连当前上游事实。
- `balanced=false` 或差额超 tolerance 时，即使请求 publish 也只能生成 `TRIAL`，并返回 `publishable=false` 与稳定原因。`resultVersion` 每次试算递增，`decisionVersion` 只在合格采用决定提交时递增；上下文 advisory lock、CAS 与事务保证冲突无部分写入。调整值、理由、执行人、时间和决策版本均进入 calculation/result/API/UI 审计。
- 物流写契约由 V26 字段定义、适用性、选项和 page actions 控制。REST definition 暴露 control/options/required/readonly/precision/scale/actions；create/update 仅接收 `{productCode, values:{fieldCode:value}}`，节点使用 `nodeCode`，扩展字段走通用存储，未知 code/binding 失败关闭。前端已删除全部 LOG_*→draft 字段枚举、数字 node ID 和 `__` 私有字段，拆分为 thin page、race-safe hook、generic editor 与 return dialog。

本轮仅做定向门禁，未重复既有全量：公式 V2/cycle/missing 3 项 GREEN；供需 REST 真实来源/试算修复/失衡/CAS/不可变快照 2 项 GREEN；物流 DB definition/code-key create-update/extension/CAS 1 项 GREEN；V26 空库回放 selector GREEN。前端 HTTP/page/race 4 files、6 tests GREEN；受影响单条 Chromium 工作流严格运行一次并 1/1 GREEN；`tsc --noEmit`、touched ESLint 与定向 dependency-cruiser（26 modules / 55 dependencies / 0 violations）GREEN。Round 1 后是否运行全量由独立只读复审决定。

## Round 2 Review Addendum（2026-08-03）

本轮冻结 V1–V26，仅新增前向迁移 `V27__close_logistics_supply_round_2_findings.sql`，SHA-256 为 `92e8d8b926b33073c3b178c3c38b49b899ed92e08d321902bac0367222e031c7`。Flyway 在受保护测试库成功校验 27 个迁移，未修改旧迁移，未 push。

- 来源资格改为版本化 `role_source_applicability`。发布命令不再接收或信任 value/unit；服务从生产/物流已批准事实读取真实字段、单位和方向，按映射快照换算为账户单位。价格字段、错误字段/版本/单位/方向均稳定拒绝，人工决定固定使用账户规范单位。
- 新增不可变 `source_adoption_set` 与 item，计算命令必须显式引用完整输入集。数据库和服务共同保证一个角色一个 release、一个 release 不跨角色、同一上游事实不重复、必需角色全集完整；计算不再使用 latest/`row_number` 推断来源，失败事务不产生部分输入集。
- 运行引用完整公式 DAG 快照，包含版本、precision、scale、HALF_UP、tolerance、result、expression 与有序 term。被运行引用的 formula/result/term 均不可更新或删除；历史查询只读运行时公式和来源快照。V2 系数变化可形成不同结果，V1 历史保持不变；`.5005` 在 scale 3、HALF_UP 下为 `.501`。
- 服务层显式编排公式验证、来源解析与换算、输入集完整性、calculator、正式门禁、决定和公式快照；JDBC 仓储仅加载材料并持久化已判定结果。`TRIAL`/`FORMAL_CANDIDATE` 只保存调整建议，不产生批准审计或决定推进；只有合格且请求发布的 `FORMAL` 写批准审计和决定。
- 物流所有 UI `allowedActions` 与 create/save/submit/approve/return 写入口共用数据库动作适用性策略。删除 `APPROVE` 配置后，列表不返回该动作、服务稳定拒绝且状态/版本不变；补回配置后才允许审核。
- 物流读模型同时返回 raw `values` 和同一元数据来源解析的 `displayValues`。编辑器/写请求保留 `RAIL`、`INFLOW`、node/period code，业务表展示铁路、流入、节点、期间和状态中文标签；字段定义加载拥有独立 retry，不依赖列表重试。
- 供需前端查询键覆盖 product、region、marketingYear、resultState、version。race-safe hook 以完整上下文隔离 account/runner/busy/issue，晚到的 A 不可覆盖 B，B runner 使用 B 的 `inputSetId` 与 `decisionVersion`；页面拆成 thin composer、hook 和展示组件，并以中文区分“试算调整建议”与“正式批准调整”。

本轮严格执行聚焦验证，未运行全量或 E2E：

- 后端：`mvn -q -DskipTests compile` GREEN；`SupplyAccountRestIntegrationTest` 2/2 GREEN；`LogisticsRestIntegrationTest` 1/1 GREEN；舍入 selector `SupplyAccountCalculatorTest#marksAnAccountOutsideToleranceAsUnbalanced` 1/1 GREEN。供需 REST 最终复验同时显示 Flyway 27 migrations validation GREEN。
- 前端：物流/供需 HTTP 与 page 共 4 files、7 tests GREEN；`tsc --noEmit` GREEN；touched ESLint GREEN；定向 dependency-cruiser 为 30 modules / 66 dependencies / 0 violations。
- 两仓 `git diff --check` GREEN；前端无 `pnpm-lock.yaml` 变更或新增。Round 2 后全量与 E2E 是否执行交由独立只读复审决定。
