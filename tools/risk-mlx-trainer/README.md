# 本地风险大模型训练器

该服务只监听回环地址，使用 Apple MLX-LM 对 Qwen3 进行真实 LoRA/QLoRA 权重训练，返回不可覆盖的适配器目录及内容哈希。`/v1/score` 会加载同一基座和适配器，用 0/1 标签的实际模型 logits 计算风险概率。

```bash
uv sync
RISK_LLM_BEARER_TOKEN=change-me uv run python server.py
```

后端需配置：

```text
QIQIHAR_RISK_TRAINING_LLM_ENDPOINT=http://127.0.0.1:63200/v1/train
QIQIHAR_RISK_TRAINING_LLM_SCORING_ENDPOINT=http://127.0.0.1:63200/v1/score
QIQIHAR_RISK_TRAINING_LLM_BASE_MODEL_REFERENCE=mlx-community/Qwen3-0.6B-4bit
QIQIHAR_RISK_TRAINING_LLM_BEARER_TOKEN=change-me
```
