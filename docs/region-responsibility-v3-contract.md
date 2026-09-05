# 员工地区责任 V3 服务端契约

地区责任以 `platform.region_responsibility` 为唯一事实，与允许重叠的员工访问授权分开。员工清单和详情的 `responsibilityRegionCodes` 表示负责地区；原 `regionCodes` 仍表示访问范围，不能拿来显示独立责任。

## 预览、保存、回查

路径：`/api/v1/identity/employees/{subjectId}/region-responsibility`。

- `GET`：当前责任和对应有效样本。要求员工读取权限、单位边界及全部所查责任地区的访问权限。
- `POST /preview`：请求 `{ "regionCodes": [...] }`。地区选项使用该员工单位的权威 assignment-options。服务端返回 `{ subjectId, regionCodes, regions, samples, previewToken }`（均在标准 `data` 信封内）。
- `PUT`：一次提交 `{ "regionCodes": [...], "previewToken": "...", "reason": "..." }`。空地区列表表示解除该员工当前所有地区责任；原因必填，最长 500 字符。成功后返回重新读取的当前责任。

`regionCodes` 是该员工保存后的完整地区集合。所选地区自动接管，取消的原本人责任自动解除；不提供逐样本勾选或逐样本请求。地区选项是单位范围内的乡镇，或治理范围内没有乡镇下级的县。地区归属沿权威父子关系展开，不能使用代码字符串前缀。

`regions` 含本次涉及的原地区责任（regionCode、regionName、subjectId、displayName、version）。`samples` 含有效样本（id、canonicalName、regionCode、regionName、previousSubjectId、previousDisplayName、nextSubjectId、nextDisplayName、version）。未分配/解除责任的人员字段为 null。前端不得把内部代码或空值占位伪装成人员名称。

调整要求有效员工、业务填报权限；操作人须有 IDENTITY_ADMIN、FORMAL_SAMPLE_MANAGE 及对应地区/单位权限。事务内锁定责任、样本及授权目录，重新计算完整预览摘要。员工/授权/地区责任/样本版本、样本新增或移区等导致摘要不一致时，返回 409 REGION_RESPONSIBILITY_CONFLICT，整批不写入。前端必须重新预览，不能复用旧摘要自动重试。

## 新旧业务入口与县级填报

- 样本表触发器使后续新样本、移入已分配地区的样本继承负责人，旧单样本维护人入口不得覆盖地区责任。原实际填报人和历史观测不改写。
- 一般业务填报/修改按独立地区责任校验，即使原员工仍保留重叠访问范围，也不能继续代替新负责人填报。保留已有 FORMAL_SAMPLE_MANAGE 管理员代办权限和原业务审计。
- 县级年度产情、供需平衡按同一规则校验：全部乡镇统一由一个员工负责，且县位于其单位治理范围内时，动态获得该县的填报资格；不写入额外县级访问授权，不改变原员工编辑表单的乡镇约束。部分乡镇或多人分工时，普通员工不能填报整县，由已有县级管理员办理。显式历史县级授权也不能绕过这一责任检查。
- 地区年度产情列表按个人可读县范围过滤，查询所属地级市不等于授予整个地级市访问权限。

责任变更和样本维护人变更同事务写入审计和 outbox。账号事件为 SECURITY_USER / REGION_RESPONSIBILITY_CHANGED；样本事件为 FORMAL_SAMPLE_POINT / FORMAL_SAMPLE_MAINTAINER_REASSIGNED。账号和样本页面接收后应回查权威数据。

## 验证边界

已验证预览/保存/回查、新样本继承、旧入口拒绝、过期预览、并发双存只成功一次、审计失败整批回滚、历史填报人不变、失效员工/越区、解除责任、运行角色锁权限、县级产情与供需平衡责任及员工编辑兼容性。Web V3、源码 CI/main、本地受管发布、真实浏览器保存/刷新和模板下载验收另行完成；此契约不代表本地发布或企业 OIDC 已验收。
