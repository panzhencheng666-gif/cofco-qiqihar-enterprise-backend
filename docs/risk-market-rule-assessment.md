# 正式市场事实到风险研判

启用 `RISK_MARKET_CONNECTOR_ENABLED=true` 后，服务每分钟从正式市场事实投影摄取来源快照，并逐批检查 `risk.risk_rule_set_version` 中当前生效、`domain_code='MARKET'` 的 `ACTIVE` 规则。没有生效规则时，处理器不会创建研判或预警。本次发布不预置价格阈值。

规则定义只接受版本 1 的受限 JSON，不执行任意 SQL 或脚本：

```json
{
  "schemaVersion": 1,
  "field": "actualTradePrice",
  "operator": "GT",
  "threshold": "3000.0000",
  "productCode": "CORN",
  "regionCode": "230221",
  "riskLevel": "LOW",
  "reasonCode": "PRICE_ABOVE_CONFIGURED_LIMIT"
}
```

`field` 只能是 `actualTradePrice`、`purchaseBasePrice`、`saleBasePrice`；`operator` 只能是 `GT`、`GTE`、`LT`、`LTE`。`productCode` 和 `regionCode` 可省略。阈值必须是正数的十进制文本，最多四位小数。只有正式来源且状态为 `APPROVED` 的最新有效版本会匹配。

规则的 `scope_definition` 必须明确 `{"sourceRecordType":"MARKET_RECORD"}`，并与规则定义中的可选区域、产品范围完全一致。规则须走数据库已有的 `DRAFT → REVIEW_PENDING → APPROVED → ACTIVE` 版本生命周期，并由实际操作者留下身份与生效时间。上面的 JSON 仅说明格式，**不是批准的业务阈值**，不得直接在生产启用。

每条来源快照与规则版本会产生一条不可重复的评估账本记录。匹配时，同一事务内写入 `risk.risk_assessment`，固化来源快照 ID、来源版本与哈希、区域、产品、实际值、阈值和规则哈希；未匹配也记账，确保重试和同时间戳补录可复查。新版本来源事实不会覆盖历史研判；若未来启用规则，应同时制定撤销/更正事实后的事件处置策略。
