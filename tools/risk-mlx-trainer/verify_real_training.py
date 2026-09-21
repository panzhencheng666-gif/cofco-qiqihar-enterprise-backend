from __future__ import annotations

import hashlib
import json
import os
import urllib.request
from pathlib import Path


def call(url: str, token: str, payload: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=1900) as response:
        if response.status != 200:
            raise RuntimeError(f"真实训练验收失败 HTTP {response.status}")
        return json.loads(response.read())


def main() -> None:
    endpoint = os.environ["RISK_LLM_TRAINER_URL"].removesuffix("/v1/train")
    token = os.environ["RISK_LLM_BEARER_TOKEN"]
    base = os.environ["RISK_LLM_BASE_MODEL"]
    identity_hash = hashlib.sha256(
        Path(__file__).with_name("server.py").read_bytes() + b"\0" + base.encode()
    ).hexdigest()[:12]
    examples = []
    for index in range(10):
        positive = index % 2 == 0
        examples.append({
            "input": json.dumps({
                "domainCode": "INVENTORY" if index < 5 else "MARKET",
                "evidence": {"sequence": index, "fact": "库存异常" if positive else "数据正常"},
            }, ensure_ascii=False, separators=(",", ":")),
            "positive": positive,
            "domainCode": "INVENTORY" if index < 5 else "MARKET",
        })
    trained = call(endpoint + "/v1/train", token, {
        "modelId": "acceptance-" + identity_hash,
        "modelCode": "qiliang-risk-llm-v1",
        "baseModelReference": base,
        "domainCode": "CROSS_DOMAIN",
        "candidateVersion": 1,
        "trainingSnapshotId": "snapshot-" + identity_hash,
        "randomSeed": 20260921,
        "trainingKind": "LORA_ADAPTER",
        "examples": examples,
    })
    evaluation = trained.get("metrics", {}).get("offlineEvaluation", {})
    metrics = trained.get("metrics", {})
    if (metrics.get("modelIdentity") != "齐粮智研模型 QL-Risk-27B"
            or metrics.get("foundationModel") != base):
        raise RuntimeError("真实训练返回的模型身份或 27B 底座不匹配")
    if (evaluation.get("validation", {}).get("count", 0) < 1
            or evaluation.get("test", {}).get("count", 0) < 1
            or not evaluation.get("testByDomain")):
        raise RuntimeError("真实训练没有生成独立验证、测试及业务切片指标")
    artifact = Path(trained["artifactReference"])
    if not artifact.is_dir() or not (artifact / "adapters.safetensors").is_file():
        raise RuntimeError("真实训练没有生成可持久化 LoRA 权重")
    score = call(endpoint + "/v1/score", token, {
        "modelId": "acceptance-" + identity_hash,
        "modelVersion": 1,
        "baseModelReference": base,
        "artifactReference": str(artifact),
        "artifactSha256": trained["artifactSha256"],
        "input": examples[-1]["input"],
    })
    probability = float(score["positiveProbability"])
    if not 0 <= probability <= 1:
        raise RuntimeError("真实 LoRA 评分概率无效")
    print("RISK_REAL_MLX_TRAINING_OK "
          f"artifactSha256={trained['artifactSha256']} "
          f"validationCount={evaluation['validation']['count']} "
          f"testCount={evaluation['test']['count']} score={probability:.6f}")


if __name__ == "__main__":
    main()
