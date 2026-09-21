from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
import sys
import tarfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


class ClaimExpired(RuntimeError):
    pass


def trainer_payload(job: dict[str, Any]) -> dict[str, Any]:
    return {
        "modelId": job["modelId"],
        "modelCode": job["modelCode"],
        "baseModelReference": job["baseModelReference"],
        "domainCode": job["domainCode"],
        "candidateVersion": int(job["modelVersion"]),
        "trainingSnapshotId": job["trainingSnapshotId"],
        "randomSeed": int(job["randomSeed"]),
        "trainingKind": "LORA_ADAPTER",
        "examples": [dict(example, domainCode=job["domainCode"])
                     for example in job["examples"]],
    }


def bundle_artifact(directory: Path) -> bytes:
    directory = directory.resolve(strict=True)
    files = sorted(path for path in directory.rglob("*") if path.is_file())
    if not files or any(path.is_symlink() for path in directory.rglob("*")):
        raise ValueError("训练工件目录为空或包含符号链接")
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for path in files:
            relative = path.relative_to(directory).as_posix()
            info = archive.gettarinfo(str(path), arcname=relative)
            info.uid = 0
            info.gid = 0
            info.uname = ""
            info.gname = ""
            info.mtime = 0
            info.mode = 0o600
            with path.open("rb") as source:
                archive.addfile(info, source)
    compressed = io.BytesIO()
    with gzip.GzipFile(fileobj=compressed, mode="wb", mtime=0) as output:
        output.write(raw.getvalue())
    return compressed.getvalue()


def canonical_hash(directory: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in directory.rglob("*") if item.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def save_state(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_suffix(".new")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        json.dump(payload, output, ensure_ascii=False, separators=(",", ":"))
        output.write("\n")
    os.replace(temporary, path)
    path.chmod(0o600)


def load_state(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    if path.stat().st_mode & 0o077:
        raise RuntimeError("训练节点状态文件权限不安全")
    return json.loads(path.read_text(encoding="utf-8"))


def request(method: str, url: str, headers: dict[str, str], body: bytes | None,
            timeout: int) -> tuple[int, bytes]:
    call = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(call, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


class Worker:
    def __init__(self) -> None:
        self.cloud = required("RISK_TRAINING_CLOUD_URL").rstrip("/")
        if not self.cloud.startswith("https://") and os.environ.get(
                "RISK_TRAINING_ALLOW_HTTP", "false").lower() != "true":
            raise RuntimeError("云端训练 API 必须使用 HTTPS")
        self.token = required("RISK_TRAINING_NODE_TOKEN")
        self.node = required("RISK_TRAINING_NODE_ID")
        self.trainer = os.environ.get("RISK_LLM_TRAINER_URL",
                                      "http://127.0.0.1:63201/v1/train")
        self.trainer_token = required("RISK_LLM_BEARER_TOKEN")
        state_root = Path(os.environ.get(
            "RISK_TRAINING_NODE_STATE_ROOT", "var/risk-training-node")).resolve()
        self.state = state_root / "active-claim.json"
        self.artifact_index = state_root / "artifact-index.json"
        self.artifact_root = Path(os.environ.get(
            "RISK_LLM_ARTIFACT_ROOT", "var/risk-llm-adapters")).resolve()
        self.poll_seconds = max(10, int(os.environ.get("RISK_TRAINING_NODE_POLL_SECONDS", "60")))
        self.heartbeat_seconds = max(
            30, int(os.environ.get("RISK_TRAINING_NODE_HEARTBEAT_SECONDS", "300")))

    def cloud_headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.token}",
            "X-Risk-Training-Node-Id": self.node,
        }

    def claim(self) -> dict[str, Any] | None:
        status, body = request("POST", self.cloud + "/claims", self.cloud_headers(), b"", 30)
        if status == 204:
            return None
        if status != 200:
            raise RuntimeError(f"云端训练任务领取失败 HTTP {status}")
        return json.loads(body)

    def process(self, job: dict[str, Any]) -> None:
        save_state(self.state, job)
        stop = threading.Event()
        lease_lost = threading.Event()
        heartbeat = threading.Thread(
            target=self._heartbeat_loop, args=(job, stop, lease_lost), daemon=True)
        heartbeat.start()
        try:
            payload = json.dumps(
                trainer_payload(job), ensure_ascii=False, separators=(",", ":")).encode()
            status, body = request("POST", self.trainer, {
                "Authorization": f"Bearer {self.trainer_token}",
                "Content-Type": "application/json",
            }, payload, 1900)
            if status != 200:
                raise RuntimeError(f"本地 MLX 训练失败 HTTP {status}: {body[-1000:].decode(errors='replace')}")
            trained = json.loads(body)
            artifact = Path(trained["artifactReference"])
            content_hash = str(trained["artifactSha256"])
            if not artifact.is_dir() or len(content_hash) != 64:
                raise RuntimeError("本地训练未返回可验证工件")
            bundle = bundle_artifact(artifact)
            bundle_hash = hashlib.sha256(bundle).hexdigest()
            upload_headers = self.cloud_headers() | {
                "Content-Type": "application/octet-stream",
                "X-Risk-Training-Execution-Id": job["executionId"],
                "X-Risk-Training-Run-Id": job["trainingRunId"],
                "X-Risk-Artifact-Sha256": bundle_hash,
                "X-Risk-Artifact-Content-Sha256": content_hash,
            }
            status, body = request(
                "POST", self.cloud + "/artifacts", upload_headers, bundle, 300)
            if status == 409:
                raise ClaimExpired("云端训练租约已过期，训练工件未接收")
            if status != 201:
                raise RuntimeError(f"训练工件上传失败 HTTP {status}")
            stored = json.loads(body)
            self.remember_artifact(stored["artifactReference"], artifact,
                                   content_hash, stored["bundleSha256"])
            if lease_lost.is_set():
                raise ClaimExpired("云端训练租约已经失效，拒绝提交候选模型")
            completion = json.dumps({
                "trainingRunId": job["trainingRunId"],
                "modelVersion": job["modelVersion"],
                "artifactReference": stored["artifactReference"],
                "artifactSha256": stored["bundleSha256"],
                "metrics": trained.get("metrics", {}),
                "thresholds": trained.get("thresholds", {}),
            }, ensure_ascii=False, separators=(",", ":")).encode()
            status, _ = request(
                "POST",
                self.cloud + f"/executions/{job['executionId']}/completion",
                self.cloud_headers() | {"Content-Type": "application/json"},
                completion, 60)
            if status == 409:
                raise ClaimExpired("云端训练租约已过期，候选模型未登记")
            if status != 204:
                raise RuntimeError(f"候选模型登记失败 HTTP {status}")
            self.state.unlink(missing_ok=True)
        finally:
            stop.set()
            heartbeat.join(timeout=2)

    def remember_artifact(self, reference: str, artifact: Path,
                          content_hash: str, bundle_hash: str) -> None:
        index = load_state(self.artifact_index) or {}
        index[reference] = {"path": str(artifact), "contentSha256": content_hash,
                            "bundleSha256": bundle_hash}
        save_state(self.artifact_index, index)

    def resolve_artifact(self, task: dict[str, Any]) -> tuple[Path, str]:
        reference = str(task["artifactReference"])
        bundle_hash = str(task["artifactSha256"])
        index = load_state(self.artifact_index) or {}
        candidate = index.get(reference)
        if candidate:
            path = Path(candidate["path"])
            if (path.is_dir() and candidate.get("bundleSha256") == bundle_hash
                    and canonical_hash(path) == candidate.get("contentSha256")):
                return path, str(candidate["contentSha256"])
        model_root = self.artifact_root / str(task["modelId"])
        for path in sorted(model_root.glob(f"v{int(task['modelVersion'])}-*")):
            if path.is_dir() and hashlib.sha256(bundle_artifact(path)).hexdigest() == bundle_hash:
                content_hash = canonical_hash(path)
                self.remember_artifact(reference, path, content_hash, bundle_hash)
                return path, content_hash
        raise RuntimeError("本地没有与云端候选版本匹配的不可变 LoRA 工件")

    def score_once(self) -> bool:
        status, body = request(
            "POST", self.cloud + "/scoring-claims", self.cloud_headers(), b"", 30)
        if status == 204:
            return False
        if status != 200:
            raise RuntimeError(f"云端影子评分任务领取失败 HTTP {status}")
        task = json.loads(body)
        artifact, content_hash = self.resolve_artifact(task)
        payload = json.dumps({
            "modelId": task["modelId"],
            "modelVersion": task["modelVersion"],
            "baseModelReference": task["baseModelReference"],
            "artifactReference": str(artifact),
            "artifactSha256": content_hash,
            "input": task["input"],
        }, ensure_ascii=False, separators=(",", ":")).encode()
        status, body = request("POST", self.trainer.replace("/v1/train", "/v1/score"), {
            "Authorization": f"Bearer {self.trainer_token}",
            "Content-Type": "application/json",
        }, payload, 300)
        if status != 200:
            raise RuntimeError(f"本地 MLX 影子评分失败 HTTP {status}")
        score = json.loads(body)
        completion = json.dumps({
            "modelId": task["modelId"],
            "modelVersion": task["modelVersion"],
            "assessmentId": task["assessmentId"],
            "artifactReference": task["artifactReference"],
            "artifactSha256": task["artifactSha256"],
            "predictedPositive": bool(score["predictedPositive"]),
            "positiveProbability": float(score["positiveProbability"]),
        }, separators=(",", ":")).encode()
        status, _ = request("POST", self.cloud + "/scoring-completion",
                            self.cloud_headers() | {"Content-Type": "application/json"},
                            completion, 60)
        if status == 409:
            raise ClaimExpired("云端影子评分租约已过期")
        if status != 204:
            raise RuntimeError(f"云端影子评分登记失败 HTTP {status}")
        return True

    def _heartbeat_loop(self, job: dict[str, Any], stop: threading.Event,
                        lease_lost: threading.Event) -> None:
        while not stop.wait(self.heartbeat_seconds):
            body = json.dumps({"trainingRunId": job["trainingRunId"]}).encode()
            try:
                status, _ = request(
                    "POST", self.cloud + f"/executions/{job['executionId']}/heartbeat",
                    self.cloud_headers() | {"Content-Type": "application/json"}, body, 30)
                if status != 204:
                    lease_lost.set()
                    return
            except Exception:
                continue

    def run_once(self) -> None:
        job = load_state(self.state) or self.claim()
        if job is None:
            return
        try:
            self.process(job)
        except ClaimExpired:
            self.state.unlink(missing_ok=True)
            raise

    def run_forever(self) -> None:
        while True:
            try:
                self.run_once()
            except Exception as error:
                print(f"risk-training-node: {str(error)[:2000]}", file=sys.stderr, flush=True)
            for _ in range(4):
                try:
                    if not self.score_once():
                        break
                except Exception as error:
                    print(f"risk-training-node-score: {str(error)[:2000]}",
                          file=sys.stderr, flush=True)
                    break
            time.sleep(self.poll_seconds)


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"缺少训练节点配置: {name}")
    return value


if __name__ == "__main__":
    Worker().run_forever()
