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
