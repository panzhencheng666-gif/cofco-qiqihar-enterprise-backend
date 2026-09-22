# 本地风险大模型训练器

该服务只监听回环地址，使用 Apple MLX-LM 为独立的“齐粮智研模型 QL-Risk-27B”训练真实 LoRA/QLoRA 权重。Qwen3.8-27B 只是基础权重来源，领域身份、数据集、适配器、评估和版本生命周期都由风险研判系统独立管理。`/v1/score` 会加载同一底座和专属适配器，用实际模型 logits 计算风险概率。

```bash
uv sync
RISK_LLM_BEARER_TOKEN=change-me uv run python server.py
```

后端需配置：

```text
QIQIHAR_RISK_TRAINING_LLM_ENDPOINT=http://127.0.0.1:63200/v1/train
QIQIHAR_RISK_TRAINING_LLM_SCORING_ENDPOINT=http://127.0.0.1:63200/v1/score
QIQIHAR_RISK_TRAINING_LLM_BASE_MODEL_REFERENCE=mlx-community/Qwen3.8-27B-4bit
QIQIHAR_RISK_TRAINING_LLM_BEARER_TOKEN=change-me
```

## 本地资料辅助回答（Task1）

`POST /v1/expert-answer` 为 **FOUNDATION_RAG**：基础模型加有限来源摘要检索。
本阶段未进行领域训练、不加载适配器，不证明专家资格、预测准确率或意识能力。
`expert_knowledge.json` 仅含5条原创简短转述，来自2026-09-22核验材料，均标为
`RETRIEVAL_ONLY`。不复制标准全文，不推定公开访问授予训练或再分发许可。

请求须携带已有服务的非空 Bearer 令牌，JSON 仅允许 `{"question":"..."}`。
question 为不超过2000个Unicode字符的非空字符串；模型路径、提示词、来源文本和
适配器均不接受客户端传入。未配置令牌返回403，缺失或错误令牌返回401。
请求体上限16KiB；无效输入返回400。

由服务启动环境配置 `RISK_EXPERT_MODEL_PATH` 为已存在的本地模型快照目录；
未设置或快照缺失返回503，禁止回退到 Hub 下载。必须使用安装了固定
`mlx-lm==0.31.3` 的 Python 启动服务；子进程使用同一 `sys.executable`，
离线加载本地权重和 tokenizer，禁用远程代码，调用 tokenizer 聊天模板，关闭思考输出，
使用 `make_sampler(temp=0.0)` 贪心采样，最多生成768 tokens。
`RISK_EXPERT_TIMEOUT_SECONDS` 默认120，允许1至300秒，非法配置返回503。
超时会终止并收取推理子进程；模型错误、非JSON输出或未知引用均返回503，不伪造答案。

结果字段为 `status, mode, modelReference, knowledgeVersion, answer, citations, limitations`。
status 仅为 `ANSWERED` 或 `INSUFFICIENT_EVIDENCE`；mode 始终为 `FOUNDATION_RAG`。
关键词匹配确定性排序后仅取前3条，问题和证据角色与系统指令分离，内容总长不超过8000字符。
无命中不调用模型；命中但不足以作答时要求模型拒答。引用只能来自本次服务器选中的记录，
URL只采用原始记录，模型输出的链接会被拒绝。答案应作为纯文本显示，不能按HTML执行。
来源的适用范围及限制随引用返回；无置信概率字段。

知识版本 `2026-09-22.task1.v2` 增加字段级来源归属：`summary` 标记
`VERIFIED_SOURCE_SUMMARY`，`applicability` 与 `limitations` 标记
`SYSTEM_EDITORIAL_NOTE`，`coverage=PARTIAL_NOT_FULL_TEXT` 表示仅收录部分转述。
模型上下文分为 `verifiedSource` 与 `editorialNotes`；提示词明确禁止把系统分析
归为标准/通报的明确条文，或把摘要缺项推断为原文没有规定。返回引用保留这些标记，
顶层来源限制也注明“系统分析说明（非原文）”。这属于归属缺陷的提示与资料修复，
不证明模型必然遵守；父任务须重跑原冻结问题作回归，不得称为新留出评估。

引用校验只验证来源身份，不自动证明每句话有充分依据；提示词隔离不能保证消除提示注入。
关键词检索不是语义检索质量认证，来源核验日期不等于持续现行性确认。上线使用前须人工
检查依据和范围。真实27B推理、延迟及冻结问题验收由父任务另行执行，本轮只验证确定性边界。

三条工作接口共用进程内非阻塞资源锁，繁忙返回503 `WORKLOAD_BUSY`，异常后释放。
训练和评分的成功输出契约及原有鉴权策略保持兼容。该锁只覆盖同一服务进程的HTTP请求，
不协调多个服务进程或服务外GPU任务；本阶段不修改部署、业务数据、权限或云端运行环境。
