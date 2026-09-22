from __future__ import annotations

import hashlib
import hmac
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import expert

ROOT = Path(os.environ.get("RISK_LLM_ARTIFACT_ROOT", "var/risk-llm-adapters")).resolve()
TOKEN = os.environ.get("RISK_LLM_BEARER_TOKEN", "")
ITERATIONS = int(os.environ.get("RISK_LLM_TRAIN_ITERS", "80"))
MAX_BODY = 8 * 1024 * 1024
WORKLOAD_GATE = threading.Lock()
SAFE = re.compile(r"^[A-Za-z0-9._-]{1,120}$")
MODEL_IDENTITY = "齐粮智研模型 QL-Risk-27B"
DOMAIN_INSTRUCTION = (
    f"你是{MODEL_IDENTITY}，是粮食行业风险研判预警系统的专属模型。"
    "只能依据输入证据判断风险是否成立，证据不足时不得臆测。"
    "风险成立输出1，不成立输出0。\n输入："
)
INSTRUCTION_SHA256 = hashlib.sha256(DOMAIN_INSTRUCTION.encode()).hexdigest()


def canonical_hash(directory: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in directory.rglob("*") if item.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def split_examples(examples: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    rows = []
    for example in examples:
        label = "1" if bool(example["positive"]) else "0"
        rows.append({
            "prompt": DOMAIN_INSTRUCTION + str(example["input"]) + "\n结论：",
            "completion": label,
            "domainCode": str(example.get("domainCode", "UNKNOWN")),
        })
    if len(rows) < 4 or len({row["completion"] for row in rows}) < 2:
        raise ValueError("LoRA 训练至少需要 4 条且同时包含正负真实标签")
    holdout = max(1, min(len(rows) // 5, (len(rows) - 2) // 2))
    return {
        "train": rows[:len(rows) - 2 * holdout],
        "valid": rows[len(rows) - 2 * holdout:len(rows) - holdout],
        "test": rows[len(rows) - holdout:],
    }


def write_dataset(directory: Path, examples: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    partitions = split_examples(examples)
    directory.mkdir(parents=True, exist_ok=False)
    for name, values in partitions.items():
        (directory / f"{name}.jsonl").write_text(
            "".join(json.dumps({"prompt": value["prompt"], "completion": value["completion"]},
                               ensure_ascii=False, separators=(",", ":")) + "\n"
                    for value in values), encoding="utf-8")
    return partitions


def request_manifest(payload: dict[str, Any]) -> dict[str, Any]:
    definition = {
        "modelIdentity": MODEL_IDENTITY,
        "modelCode": str(payload["modelCode"]),
        "modelId": str(payload["modelId"]),
        "candidateVersion": int(payload["candidateVersion"]),
        "foundationModel": str(payload["baseModelReference"]),
        "trainingKind": "QLORA_DOMAIN_ADAPTER",
        "trainingSnapshotId": str(payload["trainingSnapshotId"]),
        "randomSeed": int(payload["randomSeed"]),
        "iterations": ITERATIONS,
        "instructionSha256": INSTRUCTION_SHA256,
        "examplesSha256": hashlib.sha256(json.dumps(
            payload["examples"], ensure_ascii=False, sort_keys=True,
            separators=(",", ":")).encode()).hexdigest(),
    }
    definition["requestSha256"] = hashlib.sha256(json.dumps(
        definition, ensure_ascii=False, sort_keys=True,
        separators=(",", ":")).encode()).hexdigest()
    return definition


def completed_artifact(target: Path, training_examples: int,
                       expected_manifest: dict[str, Any]) -> dict[str, Any]:
    weights = target / "adapters.safetensors"
    config = target / "adapter_config.json"
    metrics_file = target / "training_metrics.json"
    manifest_file = target / "model_manifest.json"
    if (not weights.is_file() or weights.stat().st_size == 0 or not config.is_file()
            or not metrics_file.is_file() or not manifest_file.is_file()):
        raise RuntimeError("MLX LoRA 工件目录不完整，拒绝覆盖")
    measured = json.loads(metrics_file.read_text(encoding="utf-8"))
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    for key, expected in expected_manifest.items():
        if manifest.get(key) != expected:
            raise RuntimeError(f"MLX LoRA 工件身份不匹配: {key}")
    return {
        "artifactReference": str(target),
        "artifactSha256": canonical_hash(target),
        "metrics": {
            "trainingExamples": training_examples,
            "iterations": ITERATIONS,
            "adapterBytes": weights.stat().st_size,
            "engine": "mlx-lm-0.31.3",
            "modelIdentity": manifest["modelIdentity"],
            "foundationModel": manifest["foundationModel"],
            "offlineEvaluation": measured,
        },
        "thresholds": {"positiveProbability": 0.5},
    }


def train(payload: dict[str, Any]) -> dict[str, Any]:
    model_id = str(payload["modelId"])
    version = int(payload["candidateVersion"])
    snapshot = str(payload["trainingSnapshotId"])
    base = str(payload["baseModelReference"])
    if not SAFE.fullmatch(model_id) or not SAFE.fullmatch(snapshot) or version < 1:
        raise ValueError("模型训练标识不合法")
    target = ROOT / model_id / f"v{version}-{snapshot}"
    examples = list(payload["examples"])
    partitions = split_examples(examples)
    expected_manifest = request_manifest(payload)
    if target.exists():
        return completed_artifact(target, len(examples), expected_manifest)
    target.parent.mkdir(parents=True, exist_ok=True)
    staging_root = Path(tempfile.mkdtemp(prefix=f".{target.name}.", dir=target.parent))
    staging = staging_root / "artifact"
    try:
        with tempfile.TemporaryDirectory(prefix="risk-lora-") as temporary:
            data = Path(temporary) / "data"
            partitions = write_dataset(data, examples)
            command = [
                sys.executable, "-m", "mlx_lm.lora", "--model", base, "--train",
                "--data", str(data), "--adapter-path", str(staging), "--mask-prompt",
                "--iters", str(ITERATIONS), "--batch-size", "1", "--num-layers", "8",
                "--learning-rate", "1e-5", "--steps-per-report", "10",
                "--steps-per-eval", str(max(10, ITERATIONS)), "--val-batches", "1",
                "--save-every", str(max(10, ITERATIONS)), "--max-seq-length", "1024",
                "--seed", str(int(payload["randomSeed"])),
            ]
            result = subprocess.run(command, text=True, capture_output=True, timeout=1800)
            if result.returncode != 0:
                raise RuntimeError("MLX LoRA 训练失败: " + result.stderr[-2000:])
        manifest = dict(expected_manifest)
        manifest["specialization"] = [
            "grain_inventory", "grain_market", "supply", "logistics",
            "quality", "operations", "risk_early_warning",
        ]
        (staging / "model_manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n",
            encoding="utf-8")
        measured = evaluate_adapter(base, staging, partitions)
        (staging / "training_metrics.json").write_text(
            json.dumps(measured, ensure_ascii=False, separators=(",", ":")) + "\n",
            encoding="utf-8")
        if target.exists():
            return completed_artifact(target, len(examples), expected_manifest)
        os.replace(staging, target)
    finally:
        shutil.rmtree(staging_root, ignore_errors=True)
    return completed_artifact(target, len(examples), expected_manifest)


def evaluate_adapter(base: str, artifact: Path,
                     partitions: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    import mlx.core as mx
    from mlx_lm import load

    model, tokenizer = load(base, adapter_path=str(artifact))
    positive_id = tokenizer.encode("1", add_special_tokens=False)[0]
    negative_id = tokenizer.encode("0", add_special_tokens=False)[0]

    def probability(prompt: str) -> float:
        tokens = tokenizer.encode(prompt)
        logits = model(mx.array(tokens)[None])[:, -1, :]
        pair = mx.softmax(mx.array([logits[0, negative_id], logits[0, positive_id]]))
        value = float(pair[1].item())
        if not math.isfinite(value):
            raise RuntimeError("LoRA 离线评估产生非有限概率")
        return value

    def metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
        outcomes = [(row["completion"] == "1", probability(row["prompt"])) for row in rows]
        tp = sum(actual and score >= 0.5 for actual, score in outcomes)
        fp = sum(not actual and score >= 0.5 for actual, score in outcomes)
        fn = sum(actual and score < 0.5 for actual, score in outcomes)
        tn = len(outcomes) - tp - fp - fn
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        return {"count": len(outcomes), "truePositive": tp, "falsePositive": fp,
                "falseNegative": fn, "trueNegative": tn, "precision": precision,
                "recall": recall, "f1": f1,
                "accuracy": (tp + tn) / len(outcomes) if outcomes else 0.0}

    slices: dict[str, Any] = {}
    for domain in sorted({row["domainCode"] for row in partitions["test"]}):
        slices[domain] = metrics([row for row in partitions["test"]
                                   if row["domainCode"] == domain])
    return {"splitStrategy": "chronological_train_validation_test",
            "trainCount": len(partitions["train"]),
            "validation": metrics(partitions["valid"]),
            "test": metrics(partitions["test"]), "testByDomain": slices}


def score(payload: dict[str, Any]) -> dict[str, Any]:
    import mlx.core as mx
    from mlx_lm import load

    artifact = Path(str(payload["artifactReference"])).resolve()
    if not artifact.is_dir() or canonical_hash(artifact) != str(payload["artifactSha256"]):
        raise ValueError("LoRA 工件不存在或哈希不匹配")
    manifest = json.loads((artifact / "model_manifest.json").read_text(encoding="utf-8"))
    if (manifest.get("modelIdentity") != MODEL_IDENTITY
            or manifest.get("modelId") != str(payload["modelId"])
            or manifest.get("candidateVersion") != int(payload["modelVersion"])
            or manifest.get("foundationModel") != str(payload["baseModelReference"])):
        raise ValueError("LoRA 工件身份或底座与评分请求不匹配")
    model, tokenizer = load(str(payload["baseModelReference"]), adapter_path=str(artifact))
    prompt = DOMAIN_INSTRUCTION + str(payload["input"]) + "\n结论："
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
        is_expert = self.path == "/v1/expert-answer"
        if is_expert and not TOKEN.strip():
            self.reply(403, {"error": "EXPERT_AUTH_NOT_CONFIGURED"})
            return
        if TOKEN and not hmac.compare_digest(
                self.headers.get("Authorization", "").encode(), f"Bearer {TOKEN}".encode()):
            self.reply(401, {"error": "UNAUTHORIZED"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > (16384 if is_expert else MAX_BODY):
                raise ValueError("请求体大小不合法")
            body = self.rfile.read(length)
            payload = (json.loads(body, object_pairs_hook=expert.unique_json_object)
                       if is_expert else json.loads(body))
            operations = {"/v1/train": train, "/v1/score": score,
                          "/v1/expert-answer": expert.answer_question}
            operation = operations.get(self.path)
            if operation is None:
                self.reply(404, {"error": "NOT_FOUND"})
                return
            if is_expert:
                expert.validate_question(payload)
            if not WORKLOAD_GATE.acquire(blocking=False):
                self.reply(503, {"error": "WORKLOAD_BUSY"})
                return
            try:
                result = operation(payload)
            finally:
                WORKLOAD_GATE.release()
            self.reply(200, result)
        except expert.ExpertUnavailable:
            self.reply(503, {"error": "EXPERT_UNAVAILABLE"})
        except (KeyError, TypeError, ValueError) as error:
            self.reply(400, {"error": "INVALID_REQUEST", "message": str(error)})
        except Exception as error:
            if is_expert:
                self.reply(503, {"error": "EXPERT_UNAVAILABLE"})
            else:
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
