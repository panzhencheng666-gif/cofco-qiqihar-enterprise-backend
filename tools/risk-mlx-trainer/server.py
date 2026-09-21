from __future__ import annotations

import hashlib
import json
import math
import os
import re
import subprocess
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

ROOT = Path(os.environ.get("RISK_LLM_ARTIFACT_ROOT", "var/risk-llm-adapters")).resolve()
TOKEN = os.environ.get("RISK_LLM_BEARER_TOKEN", "")
ITERATIONS = int(os.environ.get("RISK_LLM_TRAIN_ITERS", "80"))
MAX_BODY = 8 * 1024 * 1024
SAFE = re.compile(r"^[A-Za-z0-9._-]{1,120}$")


def canonical_hash(directory: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in directory.rglob("*") if item.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def write_dataset(directory: Path, examples: list[dict[str, Any]]) -> None:
    rows = []
    for example in examples:
        label = "1" if bool(example["positive"]) else "0"
        rows.append({
            "prompt": (
                "你是粮食经营风险研判模型。只能依据输入证据判断风险是否成立。"
                "证据不足时不得臆测。风险成立输出1，不成立输出0。\n输入："
                + str(example["input"]) + "\n结论："
            ),
            "completion": label,
        })
    if len(rows) < 4 or len({row["completion"] for row in rows}) < 2:
        raise ValueError("LoRA 训练至少需要 4 条且同时包含正负真实标签")
    split = max(1, min(len(rows) // 5, len(rows) - 2))
    partitions = {"train": rows[split:], "valid": rows[:split], "test": rows[:split]}
    directory.mkdir(parents=True, exist_ok=False)
    for name, values in partitions.items():
        (directory / f"{name}.jsonl").write_text(
            "".join(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n"
                    for value in values), encoding="utf-8")


def train(payload: dict[str, Any]) -> dict[str, Any]:
    model_id = str(payload["modelId"])
    version = int(payload["candidateVersion"])
    snapshot = str(payload["trainingSnapshotId"])
    base = str(payload["baseModelReference"])
    if not SAFE.fullmatch(model_id) or not SAFE.fullmatch(snapshot) or version < 1:
        raise ValueError("模型训练标识不合法")
    target = ROOT / model_id / f"v{version}-{snapshot}"
    if target.exists():
        raise ValueError("同一模型版本工件已经存在，禁止覆盖")
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="risk-lora-") as temporary:
        data = Path(temporary) / "data"
        write_dataset(data, list(payload["examples"]))
        command = [
            sys.executable, "-m", "mlx_lm.lora", "--model", base, "--train",
            "--data", str(data), "--adapter-path", str(target), "--mask-prompt",
            "--iters", str(ITERATIONS), "--batch-size", "1", "--num-layers", "8",
            "--learning-rate", "1e-5", "--steps-per-report", "10",
            "--steps-per-eval", str(max(10, ITERATIONS)), "--val-batches", "1",
            "--save-every", str(max(10, ITERATIONS)), "--max-seq-length", "1024",
            "--seed", str(int(payload["randomSeed"])),
        ]
        result = subprocess.run(command, text=True, capture_output=True, timeout=1800)
        if result.returncode != 0:
            raise RuntimeError("MLX LoRA 训练失败: " + result.stderr[-2000:])
    weights = target / "adapters.safetensors"
    config = target / "adapter_config.json"
    if not weights.is_file() or weights.stat().st_size == 0 or not config.is_file():
        raise RuntimeError("MLX LoRA 未生成可验证适配器工件")
    return {
        "artifactReference": str(target),
        "artifactSha256": canonical_hash(target),
        "metrics": {
            "trainingExamples": len(payload["examples"]),
            "iterations": ITERATIONS,
            "adapterBytes": weights.stat().st_size,
            "engine": "mlx-lm-0.31.3",
        },
        "thresholds": {"positiveProbability": 0.5},
    }


def score(payload: dict[str, Any]) -> dict[str, Any]:
    import mlx.core as mx
    from mlx_lm import load

    artifact = Path(str(payload["artifactReference"])).resolve()
    if not artifact.is_dir() or canonical_hash(artifact) != str(payload["artifactSha256"]):
        raise ValueError("LoRA 工件不存在或哈希不匹配")
    model, tokenizer = load(str(payload["baseModelReference"]), adapter_path=str(artifact))
    prompt = (
        "你是粮食经营风险研判模型。只能依据输入证据判断风险是否成立。"
        "证据不足时不得臆测。风险成立输出1，不成立输出0。\n输入："
        + str(payload["input"]) + "\n结论："
    )
    tokens = tokenizer.encode(prompt)
    logits = model(mx.array(tokens)[None])[:, -1, :]
    positive_id = tokenizer.encode("1", add_special_tokens=False)[0]
    negative_id = tokenizer.encode("0", add_special_tokens=False)[0]
    pair = mx.softmax(mx.array([logits[0, negative_id], logits[0, positive_id]]))
    probability = float(pair[1].item())
    if not math.isfinite(probability):
        raise RuntimeError("LoRA 评分产生非有限概率")
    return {"predictedPositive": probability >= 0.5, "positiveProbability": probability}


class Handler(BaseHTTPRequestHandler):
    server_version = "QiqiharRiskMLX/1"

    def do_GET(self) -> None:
        if self.path == "/health":
            self.reply(200, {"status": "UP", "engine": "mlx-lm", "iterations": ITERATIONS})
        else:
            self.reply(404, {"error": "NOT_FOUND"})

    def do_POST(self) -> None:
        if TOKEN and self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.reply(401, {"error": "UNAUTHORIZED"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY:
                raise ValueError("请求体大小不合法")
            payload = json.loads(self.rfile.read(length))
            if self.path == "/v1/train":
                self.reply(200, train(payload))
            elif self.path == "/v1/score":
                self.reply(200, score(payload))
            else:
                self.reply(404, {"error": "NOT_FOUND"})
        except (KeyError, TypeError, ValueError) as error:
            self.reply(400, {"error": "INVALID_REQUEST", "message": str(error)})
        except Exception as error:
            self.reply(500, {"error": "TRAINER_FAILURE", "message": str(error)[:2000]})

    def log_message(self, message: str, *args: Any) -> None:
        sys.stderr.write("risk-mlx-trainer: " + message % args + "\n")

    def reply(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True)
    host = os.environ.get("RISK_LLM_BIND", "127.0.0.1")
    port = int(os.environ.get("RISK_LLM_PORT", "63200"))
    ThreadingHTTPServer((host, port), Handler).serve_forever()
