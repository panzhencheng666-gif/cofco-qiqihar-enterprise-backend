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

当模型返回 `INSUFFICIENT_EVIDENCE` 时，仍先完成原有格式、链接及引用校验，
随后丢弃模型自由生成的回答，统一返回服务端文本：“当前收录证据不足，不能据此作出
所问结论，需补充核验资料。”保留有效引用、模式和限制；无检索命中使用同一答复。
这是按状态执行的通用防线，不针对问题或案例；`ANSWERED` 的回答与校验规则不变。

引用校验只验证来源身份，不自动证明每句话有充分依据；提示词隔离不能保证消除提示注入。
关键词检索不是语义检索质量认证，来源核验日期不等于持续现行性确认。上线使用前须人工
检查依据和范围。真实27B推理、延迟及冻结问题验收由父任务另行执行，本轮只验证确定性边界。

三条工作接口共用进程内非阻塞资源锁，繁忙返回503 `WORKLOAD_BUSY`，异常后释放。
训练和评分的成功输出契约及原有鉴权策略保持兼容。该锁只覆盖同一服务进程的HTTP请求，
不协调多个服务进程或服务外GPU任务；本阶段不修改部署、业务数据、权限或云端运行环境。

## 独立专家 SFT 数据集契约（Expert SFT Task1）

`expert_dataset.py` 仅提供离线、标准库 Python >=3.11 数据校验和目录准备。
这是独立专家 SFT 路径的第一项依赖，不是已有风险二分类训练，也不修改上面的
FOUNDATION_RAG、服务接口、鉴权或部署。不加载 MLX、不调用 GPU、不访问任何提交的 URL。
聊天 JSONL 面向后续 `mlx-lm==0.31.3` 引擎；本任务不声称已验证模型或运行训练。

### 输入 schemaVersion 1

所有对象只允许下列字段，且全部必填。严格 JSON 类型，不做字符串/数字转换，
`schemaVersion` 必须是整数 `1`（布尔值不接受）。数组必须是列表。
ID 均匹配 ASCII `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`。
字符限制按 Unicode 字符数计算；字符串不得为空或仅含空白。拒绝 surrogate、
Unicode 控制字符与格式控制字符；question/context/answer 允许 TAB、LF、CR。
保留问答和上下文的原始文本，不裁剪空格或改写内容。

| 对象 | 必填字段与约束 |
| --- | --- |
| 顶层 | `schemaVersion: 1`、`datasetId: ID`、`sources: 3..1000`、`examples: 3..10000` |
| source | `sourceId: ID`；`title: 1..300`；`url`、`licenseEvidenceUrl`: HTTPS、有 host、无用户名密码、无空白、各最多2048字符；`license`: `CC0-1.0` / `CC-BY-4.0` / `PUBLIC_DOMAIN` / `OWNED`；`contentSha256`: 64位小写十六进制；`usage`: 仅 `TRAINING_ALLOWED` |
| example | `exampleId: ID`；`sourceIds`: 1..10个唯一且存在的 source ID；`groupId: ID`；`split`: `train` / `valid` / `test`；`question: 1..2000`；`context: 1..12000`；`answer: 1..6000`；`origin`: `HUMAN_AUTHORED` / `SYNTHETIC`；`verification`: 下述对象 |
| verification | `status`: 仅 `VERIFIED`；`reference`: 非空核验记录引用字符串，1..300字符 |

`RETRIEVAL_ONLY` 来源一律拒绝，不把现有 RAG 材料自动转为训练数据。
每条来源都必须被使用，sourceId 和 exampleId 各自不得重复。
三个 split 均必须非空，仅表示结构就绪，不代表样本量或统计质量达标。
不同 split 不得共享 sourceId、来源 contentSha256、groupId 或规范化问题。
规范化规则为 `NFKC → casefold → 合并并去除首尾空白`；规范化问题和答案组成的
二元组在整个数据集内不得重复。同一 split 可以复用来源、group 或问题，
但不得重复该问答二元组。来源哈希相同但 ID 不同也不能绕过 split 隔离。

许可、内容哈希、来源归属、人工核验状态都是可信调用方的声明，模块不会自动验证其
法律效力或事实真伪。调用方仍须核验许可范围、第三方材料和其他排除项；公开可读不等于
可以训练。`SYNTHETIC` 保留合成来源身份，必须有真实外部核验记录，不允许生成器自动
给自己标记已核验。模块不提供生成或自我核验功能，也无法仅凭字符串证明人工审查已发生。

### Python 接口、哈希与导出

`validate_dataset(payload: dict) -> dict` 返回新的规范化数据对象，外加
`datasetSha256` 和 `counts: {train, valid, test}`。来源按 sourceId、样本按
exampleId、每条 sourceIds 按 ID 排序，保留完整 provenance；输入对象不变。
哈希是**仅四个原始顶层字段**规范 JSON 的 UTF-8 SHA-256，不含 computed 字段，
也不含文件末尾换行。规范 JSON 使用键排序、非 ASCII 原文、无多余空白
（separators `(',', ':')`）。顺序变化不改变哈希，内容或 provenance 变化改变哈希。
直接向 validate 传入 `counts` / `datasetSha256` 仍视为未知字段。

非法输入抛出 `DatasetValidationError(ValueError)`，其 `errors` 最多50项，
每项为 `{row: int, field: str, code: str, message: str}`。
顶层 row=0、field=`$` 或顶层字段名；来源/样本 row 按原始输入位置从1计数，
field 如 `examples[2].verification.reference`。消息为固定说明，不回显原始值或未知键。
code 包括 TYPE、REQUIRED、UNKNOWN_FIELD、VERSION、IDENTIFIER、LENGTH、CHARACTERS、
VALUE、URL、SHA256、REFERENCE、DUPLICATE、SPLIT_LEAKAGE、UNUSED_SOURCE、EMPTY_SPLIT。
校验器限制数组数量与字段长度；HTTP 原始字节上限与鉴权留给后续服务任务。

`write_dataset(directory: Path, validated: dict) -> dict` 接受原始对象或 validate
结果，只剥离 `counts` / `datasetSha256` 后重新校验和计算，绝不信任传入的计算字段。
父目录须已存在、由调用方控制；目标必须全新，包括已有目录、普通文件、有效或悬空
符号链接都拒绝。创建目录权限0700、文件0600，仅独占创建，不覆盖，成功后不得原地
更新；新版本使用新目录。这里的不可变性是 API 不覆盖语义，不是文件系统防篡改锁。

| 文件 | 内容 |
| --- | --- |
| `records.json` | 完整规范数据、来源、样本、核验声明以及重算的 counts 和 datasetSha256 |
| `train.jsonl`, `valid.jsonl`, `test.jsonl` | 对应 split 的样本，按 exampleId 排序，每行仅一个 messages 对象 |
| `manifest.json` | records 全部字段，另加 `purpose: EXPERT_SFT_DATASET`、`qualityStatus: NOT_EVALUATED`、`files: {文件名: SHA256}` |

`files` 记录 records 和三个 JSONL 的实际字节哈希，不记录 manifest 自身以避免自引用。
所有文件 UTF-8，JSON/JSONL 各记录以 LF 结束；无时间戳、目标路径或随机内容。
manifest 最后写入，所有文件和目录执行 fsync；失败只尝试清理本次创建且 inode 未变的
文件及自身空目录，不递归删除其他文件。进程崩溃/断电不保证自动清理未完成目录；后续
引擎须在接纳前检查 manifest、实际文件哈希及契约，不把“目录存在”当作准备成功。

每行聊天格式是 `{"messages":[{"role":"system","content":...},
{"role":"user","content":...},{"role":"assistant","content":...}]}`。
system 为模块固定英文指令：只依据引用上下文回答、区分证据与推断、不编造事实或
引用、证据不足明确拒答，并尊重来源适用范围。user 严格拼接为
`Question:\n{question}\n\nReference context:\n{context}`，assistant 为原始 answer。
origin/verification 和完整来源保存在 records 与 manifest，不伪装成模型系统指令。
这种格式和提示词不证明模型一定遵守，也不证明答案质量。
后续训练引擎只能用 train 训练、valid 验证，**test 必须留出，不用于训练或调参**。

确定性验证（测试夹具只在测试进程和临时目录使用，不提供生产训练数据）：

```bash
python3 -m unittest discover -s tools/risk-mlx-trainer -p test_expert_dataset.py -v
python3 -m unittest discover -s tools/risk-mlx-trainer -p 'test_*.py' -v
```
