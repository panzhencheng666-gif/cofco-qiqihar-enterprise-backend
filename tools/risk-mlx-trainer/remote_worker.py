from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
import re
import sys
import tarfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any

import expert_training_artifacts


class ClaimExpired(RuntimeError):
    pass


EXPERT_FAILURES = {
    "DATASET_PREPARATION_FAILED": "专家训练数据准备失败，未生成候选工件。",
    "MODEL_LOAD_FAILED": "本地专家模型加载失败，未生成候选工件。",
    "LOCAL_TRAINING_FAILED": "本地专家训练失败，未生成候选工件。",
    "OUT_OF_MEMORY": "本地专家训练内存不足，未生成候选工件。",
    "PACKAGING_FAILED": "专家训练工件打包失败，未上传候选工件。",
}
EXPERT_PROGRESS = ((5, "PREPARING"), (10, "LOCAL_TRAINING"),
                   (75, "PACKAGING"), (85, "UPLOADING"), (95, "COMPLETING"))
SAFE_RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}")


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


def expert_artifact_hash(directory: Path) -> str:
    return expert_training_artifacts.layout_hashes(
        directory, time.monotonic() + 300, manifest=True)[1]


def json_object(body: bytes) -> dict[str, Any]:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result
    value = json.loads(body, object_pairs_hook=unique,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))
    if type(value) is not dict:
        raise ValueError("Expected JSON object")
    return value


def local_expert_urls(trainer: str) -> tuple[str, str]:
    parsed = urllib.parse.urlsplit(trainer)
    try:
        port = parsed.port
    except ValueError as error:
        raise RuntimeError("本地训练地址不合法") from error
    if (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "::1")
            or port is None or parsed.username is not None or parsed.password is not None
            or parsed.path != "/v1/train" or parsed.query or parsed.fragment):
        raise RuntimeError("本地训练地址必须是固定回环端点")
    return (urllib.parse.urlunsplit(parsed._replace(path="/v1/expert-train")),
            urllib.parse.urlunsplit(parsed._replace(path="/v1/expert-train-cancel")))


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
        self.expert_trainer, self.expert_cancel = local_expert_urls(self.trainer)
        self.trainer_token = required("RISK_LLM_BEARER_TOKEN")
        state_root = Path(os.environ.get(
            "RISK_TRAINING_NODE_STATE_ROOT", "var/risk-training-node")).resolve()
        self.state = state_root / "active-claim.json"
        self.expert_state = state_root / "active-expert-claim.json"
        self.artifact_index = state_root / "artifact-index.json"
        configured_artifact_root = Path(os.environ.get(
            "RISK_LLM_ARTIFACT_ROOT", "var/risk-llm-adapters")).absolute()
        if configured_artifact_root.is_symlink():
            raise RuntimeError("本地训练工件根目录不合法")
        self.artifact_root = configured_artifact_root.resolve()
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

    def _expert_state_from_claim(self, claim: dict[str, Any]) -> dict[str, Any]:
        task_id = str(uuid.UUID(str(claim["taskId"])))
        run_id = claim["runId"]
        if type(run_id) is not str or not SAFE_RUN_ID.fullmatch(run_id):
            raise ValueError("专家训练 runId 不合法")
        if type(claim["dataset"]) is not dict or type(claim["config"]) is not dict:
            raise ValueError("专家训练任务数据不合法")
        return {"taskId": task_id, "runId": run_id,
                "dataset": claim["dataset"], "config": claim["config"], "progress": 0}

    def _saved_expert_state(self, state: dict[str, Any]) -> dict[str, Any]:
        required = {"taskId", "runId", "dataset", "config"}
        allowed = required | {"progress", "result", "stored"}
        if type(state) is not dict or not required.issubset(state) or not set(state).issubset(allowed):
            raise ValueError("专家训练状态不合法")
        clean = self._expert_state_from_claim(state)
        progress = state.get("progress", 0)
        if type(progress) is not int or progress not in {0, 5, 10, 75, 85, 95}:
            raise ValueError("专家训练状态进度不合法")
        clean["progress"] = progress
        for key in ("result", "stored"):
            if key in state:
                if type(state[key]) is not dict:
                    raise ValueError("专家训练状态检查点不合法")
                clean[key] = state[key]
        return clean

    def expert_claim(self) -> dict[str, Any] | None:
        status, body = request("POST", self.cloud + "/expert-claims",
                               self.cloud_headers(), b"", 30)
        if status == 204:
            return None
        if status != 200:
            raise RuntimeError("云端专家训练任务领取失败")
        return self._expert_state_from_claim(json_object(body))

    def _expert_url(self, state: dict[str, Any], operation: str) -> str:
        return self.cloud + f"/expert-tasks/{state['taskId']}/{operation}"

    def expert_heartbeat(self, state: dict[str, Any]) -> bool:
        status, body = request("POST", self._expert_url(state, "heartbeat"),
                               self.cloud_headers(), b"", 30)
        if status == 409:
            raise ClaimExpired("专家训练租约已失效")
        if status != 200:
            raise RuntimeError("云端专家训练心跳失败")
        value = json_object(body)
        if type(value.get("cancelRequested")) is not bool:
            raise RuntimeError("云端专家训练心跳响应不合法")
        return value["cancelRequested"]

    def expert_progress(self, state: dict[str, Any], percent: int, phase: str) -> None:
        body = json.dumps({"percent": percent, "phase": phase},
                          separators=(",", ":")).encode()
        status, _ = request("POST", self._expert_url(state, "progress"),
                            self.cloud_headers() | {"Content-Type": "application/json"}, body, 30)
        if status == 409:
            raise ClaimExpired("专家训练租约已失效")
        if status != 204:
            raise RuntimeError("云端专家训练进度登记失败")

    def advance_expert_progress(self, state: dict[str, Any], percent: int, phase: str) -> None:
        if state["progress"] >= percent:
            return
        self.expert_progress(state, percent, phase)
        state["progress"] = percent
        save_state(self.expert_state, state)

    def acknowledge_expert_cancel(self, state: dict[str, Any]) -> None:
        status, _ = request("POST", self._expert_url(state, "cancelled"),
                            self.cloud_headers(), b"", 30)
        if status == 204:
            self.expert_state.unlink(missing_ok=True)
            return
        if status == 409:
            self.expert_state.unlink(missing_ok=True)
            raise ClaimExpired("专家训练取消确认时租约已失效")
        raise RuntimeError("云端专家训练取消确认失败")

    def report_expert_failure(self, state: dict[str, Any], code: str) -> None:
        body = json.dumps({"code": code, "message": EXPERT_FAILURES[code]},
                          ensure_ascii=False, separators=(",", ":")).encode()
        status, _ = request("POST", self._expert_url(state, "failure"),
                            self.cloud_headers() | {"Content-Type": "application/json"}, body, 30)
        if status == 204:
            self.expert_state.unlink(missing_ok=True)
            return
        if status == 409:
            self.expert_state.unlink(missing_ok=True)
            raise ClaimExpired("专家训练失败确认时租约已失效")
        raise RuntimeError("云端专家训练失败确认未完成")

    def _cancel_local_expert(self, state: dict[str, Any]) -> None:
        body = json.dumps({"runId": state["runId"]}, separators=(",", ":")).encode()
        try:
            request("POST", self.expert_cancel, {
                "Authorization": f"Bearer {self.trainer_token}",
                "Content-Type": "application/json",
            }, body, 30)
        except Exception:
            pass

    def _expert_heartbeat_loop(self, state: dict[str, Any], stop: threading.Event,
                               cancelled: threading.Event,
                               lease_lost: threading.Event) -> None:
        while not stop.wait(self.heartbeat_seconds):
            try:
                if self.expert_heartbeat(state):
                    cancelled.set()
                    self._cancel_local_expert(state)
            except ClaimExpired:
                lease_lost.set()
                return
            except Exception:
                continue

    def validate_expert_result(self, value: dict[str, Any],
                               expected_run_id: str | None = None) -> tuple[Path, str, dict[str, Any]]:
        if (type(value) is not dict or type(value.get("runId")) is not str
                or (expected_run_id is not None and value.get("runId") != expected_run_id)):
            raise ValueError("本地专家训练响应不合法")
        if (value.get("kind") != "EXPERT_SFT_ADAPTER" or value.get("status") != "CANDIDATE"
                or value.get("publicationStatus") != "NOT_EVALUATED"):
            raise ValueError("本地专家训练候选状态不合法")
        content_hash = value.get("artifactSha256")
        if type(content_hash) is not str or not re.fullmatch(r"[0-9a-f]{64}", content_hash):
            raise ValueError("本地专家训练工件哈希不合法")
        if type(value.get("artifactPath")) is not str:
            raise ValueError("本地专家训练工件路径不合法")
        raw_artifact = Path(value["artifactPath"])
        if raw_artifact.is_symlink():
            raise ValueError("本地专家训练工件路径不合法")
        artifact = raw_artifact.resolve(strict=True)
        root = self.artifact_root.resolve(strict=True)
        if artifact == root or root not in artifact.parents or not artifact.is_dir():
            raise ValueError("本地专家训练工件不在受控目录")
        if artifact.stat().st_uid != os.getuid() or expert_artifact_hash(artifact) != content_hash:
            raise ValueError("本地专家训练工件校验失败")
        metrics = self.validate_expert_metrics(value.get("metrics"))
        return artifact, content_hash, metrics

    def validate_expert_metrics(self, metrics: Any) -> dict[str, Any]:
        if type(metrics) is not dict:
            raise ValueError("本地专家训练指标不合法")
        encoded_metrics = json.dumps(metrics, ensure_ascii=False, separators=(",", ":"),
                                     allow_nan=False).encode()
        if len(encoded_metrics) > 4096:
            raise ValueError("本地专家训练指标超限")
        return metrics

    def validate_stored_checkpoint(self, stored: dict[str, Any]) -> dict[str, Any]:
        if (type(stored) is not dict
                or set(stored) != {"artifactReference", "bundleSha256", "contentSha256", "sizeBytes"}
                or type(stored["artifactReference"]) is not str or not stored["artifactReference"]
                or type(stored["bundleSha256"]) is not str
                or not re.fullmatch(r"[0-9a-f]{64}", stored["bundleSha256"])
                or type(stored["contentSha256"]) is not str
                or not re.fullmatch(r"[0-9a-f]{64}", stored["contentSha256"])
                or type(stored["sizeBytes"]) is not int or stored["sizeBytes"] <= 0):
            raise RuntimeError("云端专家训练工件检查点不合法")
        return stored

    def validate_stored_expert(self, stored: dict[str, Any], bundle_hash: str,
                               content_hash: str, size: int) -> dict[str, Any]:
        stored = self.validate_stored_checkpoint(stored)
        if (stored["bundleSha256"] != bundle_hash
                or stored["contentSha256"] != content_hash
                or stored["sizeBytes"] != size):
            raise RuntimeError("云端专家训练工件回执不合法")
        return stored

    def complete_expert(self, state: dict[str, Any], stored: dict[str, Any],
                        metrics: dict[str, Any]) -> None:
        completion = json.dumps({"artifactReference": stored["artifactReference"],
                                 "artifactSha256": stored["bundleSha256"],
                                 "metrics": metrics}, ensure_ascii=False,
                                separators=(",", ":"), allow_nan=False).encode()
        status, _ = request("POST", self._expert_url(state, "completion"),
                            self.cloud_headers() | {"Content-Type": "application/json"},
                            completion, 60)
        if status == 409:
            raise ClaimExpired("专家训练完成登记时租约已失效")
        if status != 204:
            raise RuntimeError("云端专家训练完成登记未确认")
        self.expert_state.unlink(missing_ok=True)

    def process_expert(self, state: dict[str, Any]) -> None:
        state = self._saved_expert_state(state)
        try:
            completion_replay = "stored" in state and state["progress"] == 95
            if completion_replay:
                stored = self.validate_stored_checkpoint(state["stored"])
                result = state.get("result")
                if type(result) is not dict:
                    raise RuntimeError("专家训练完成检查点不合法")
                metrics = self.validate_expert_metrics(result.get("metrics"))
                self.complete_expert(state, stored, metrics)
                return
            if not completion_replay:
                if self.expert_heartbeat(state):
                    self.acknowledge_expert_cancel(state)
                    return
            if "result" not in state:
                self.advance_expert_progress(state, *EXPERT_PROGRESS[0])
                self.advance_expert_progress(state, *EXPERT_PROGRESS[1])
                stopped = threading.Event()
                cancelled = threading.Event()
                lease_lost = threading.Event()
                heartbeat = threading.Thread(target=self._expert_heartbeat_loop,
                                             args=(state, stopped, cancelled, lease_lost), daemon=True)
                heartbeat.start()
                payload = json.dumps({"runId": state["runId"], "dataset": state["dataset"],
                                      "config": state["config"]},
                                     ensure_ascii=False, separators=(",", ":")).encode()
                try:
                    status, body = request("POST", self.expert_trainer, {
                        "Authorization": f"Bearer {self.trainer_token}",
                        "Content-Type": "application/json",
                    }, payload, 1900)
                finally:
                    stopped.set()
                    # request() bounds each heartbeat to 30 seconds. Wait for an
                    # in-flight response so a witnessed cancellation cannot lose
                    # a race with packaging/upload.
                    heartbeat.join()
                if lease_lost.is_set():
                    raise ClaimExpired("专家训练期间租约已失效")
                if cancelled.is_set():
                    self.acknowledge_expert_cancel(state)
                    return
                if self.expert_heartbeat(state):
                    self._cancel_local_expert(state)
                    self.acknowledge_expert_cancel(state)
                    return
                if status != 200:
                    try:
                        local_error = json_object(body).get("error")
                    except (ValueError, TypeError, RecursionError):
                        local_error = None
                    if status in (400, 422):
                        self.report_expert_failure(state, "DATASET_PREPARATION_FAILED")
                        return
                    if status == 503 and local_error == "EXPERT_TRAINING_UNAVAILABLE":
                        self.report_expert_failure(state, "LOCAL_TRAINING_FAILED")
                        return
                    raise RuntimeError("本地专家训练服务繁忙或暂不可用")
            try:
                trained = state["result"] if "result" in state else json_object(body)
                if trained.get("runId") != state["runId"]:
                    raise ValueError("本地专家训练 runId 不匹配")
                artifact, content_hash, metrics = self.validate_expert_result(
                    trained, state["runId"])
            except (OSError, ValueError, TypeError, KeyError, RecursionError):
                self.report_expert_failure(state, "LOCAL_TRAINING_FAILED")
                return
            if "result" not in state:
                state["result"] = {key: trained[key] for key in (
                    "runId", "kind", "status", "publicationStatus", "artifactSha256",
                    "artifactPath", "metrics")}
                save_state(self.expert_state, state)
            self.advance_expert_progress(state, *EXPERT_PROGRESS[2])
            try:
                bundle = bundle_artifact(artifact)
                bundle_hash = hashlib.sha256(bundle).hexdigest()
            except Exception:
                self.report_expert_failure(state, "PACKAGING_FAILED")
                return
            self.advance_expert_progress(state, *EXPERT_PROGRESS[3])
            if "stored" in state:
                stored = self.validate_stored_expert(
                    state["stored"], bundle_hash, content_hash, len(bundle))
            else:
                upload_headers = self.cloud_headers() | {
                    "Content-Type": "application/octet-stream",
                    "X-Risk-Artifact-Sha256": bundle_hash,
                    "X-Risk-Artifact-Content-Sha256": content_hash,
                }
                status, upload_body = request("POST", self._expert_url(state, "artifacts"),
                                              upload_headers, bundle, 300)
                if status == 409:
                    raise ClaimExpired("专家训练上传时租约已失效")
                if status != 201:
                    raise RuntimeError("云端专家训练工件上传未完成")
                stored = self.validate_stored_expert(
                    json_object(upload_body), bundle_hash, content_hash, len(bundle))
                state["stored"] = stored
                save_state(self.expert_state, state)
            self.advance_expert_progress(state, *EXPERT_PROGRESS[4])
            self.complete_expert(state, stored, metrics)
        except ClaimExpired:
            self.expert_state.unlink(missing_ok=True)
            raise

    def expert_once(self) -> bool:
        saved = load_state(self.expert_state)
        state = self._saved_expert_state(saved) if saved is not None else self.expert_claim()
        if state is None:
            return False
        if saved is None:
            save_state(self.expert_state, state)
        self.process_expert(state)
        return True

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
                raise RuntimeError(f"本地 MLX 训练失败 HTTP {status}")
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
                self.expert_once()
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
