import hashlib
import json
import os
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import remote_worker


class RemoteWorkerContractTest(unittest.TestCase):
    def expert_worker(self, root):
        worker = remote_worker.Worker.__new__(remote_worker.Worker)
        worker.cloud = "https://risk.example/training-node"
        worker.token = "cloud-token"
        worker.node = "mac-node"
        worker.trainer = "http://127.0.0.1:63201/v1/train"
        worker.expert_trainer = "http://127.0.0.1:63201/v1/expert-train"
        worker.expert_cancel = "http://127.0.0.1:63201/v1/expert-train-cancel"
        worker.trainer_token = "trainer-token"
        worker.expert_state = root / "state/active-expert-claim.json"
        worker.artifact_root = root / "artifacts"
        worker.heartbeat_seconds = .01
        return worker

    def expert_claim(self):
        return {
            "taskId": "11111111-1111-1111-1111-111111111111",
            "runId": "expert-run-1",
            "snapshotId": "22222222-2222-2222-2222-222222222222",
            "datasetId": "authoritative-dataset",
            "datasetVersion": 3,
            "datasetSha256": "a" * 64,
            "dataset": {"schemaVersion": 1, "records": ["private"]},
            "config": {"iterations": 1, "learningRate": 1e-5,
                       "maxSeqLength": 256, "numLayers": 1, "seed": 0},
            "modelReference": "fixed-cloud-model",
            "leaseUntil": "2026-09-22T05:00:00Z",
            "attempt": 1,
            "promptPath": "/must/not/be/used",
        }

    def candidate(self, worker):
        artifact = worker.artifact_root / "expert-sft/expert-run-1"
        artifact.mkdir(parents=True, mode=0o700)
        dataset = artifact / "dataset"
        dataset.mkdir(mode=0o700)
        for name in ("records.json", "manifest.json", "train.jsonl", "valid.jsonl", "test.jsonl"):
            (dataset / name).write_text("{}\n", encoding="utf-8")
        for name, content in (("adapters.safetensors", b"weights"),
                              ("adapter_config.json", b"{}"),
                              ("training_metrics.json", b"{}"),
                              ("model_manifest.json", b"{}")):
            (artifact / name).write_bytes(content)
        for path in (artifact, dataset):
            path.chmod(0o700)
        for path in artifact.rglob("*"):
            if path.is_file():
                path.chmod(0o600)
        content_hash = remote_worker.expert_artifact_hash(artifact)
        return artifact, {
            "runId": "expert-run-1", "kind": "EXPERT_SFT_ADAPTER",
            "status": "CANDIDATE", "publicationStatus": "NOT_EVALUATED",
            "artifactSha256": content_hash, "artifactPath": str(artifact),
            "metrics": {"iterations": 1, "testCount": 1},
        }

    def test_maps_cloud_job_to_local_trainer_contract(self):
        job = {
            "modelId": "11111111-1111-1111-1111-111111111111",
            "modelCode": "qiliang-risk-llm-v1",
            "baseModelReference": "mlx-community/Qwen3.8-27B-4bit",
            "domainCode": "CROSS_DOMAIN",
            "modelVersion": 3,
            "trainingSnapshotId": "22222222-2222-2222-2222-222222222222",
            "randomSeed": 7,
            "examples": [{
                "resolvedAt": "2026-09-21T01:00:00Z",
                "input": "真实证据",
                "positive": True,
            }],
        }

        payload = remote_worker.trainer_payload(job)

        self.assertEqual(payload["candidateVersion"], 3)
        self.assertEqual(payload["trainingKind"], "LORA_ADAPTER")
        self.assertEqual(payload["examples"][0]["input"], "真实证据")

    def test_bundles_only_the_declared_adapter_directory_deterministically(self):
        with tempfile.TemporaryDirectory() as temporary:
            artifact = Path(temporary) / "adapter"
            artifact.mkdir()
            (artifact / "adapter_config.json").write_text("{}", encoding="utf-8")
            (artifact / "adapters.safetensors").write_bytes(b"weights")

            first = remote_worker.bundle_artifact(artifact)
            second = remote_worker.bundle_artifact(artifact)

            self.assertEqual(first, second)
            self.assertEqual(hashlib.sha256(first).hexdigest(), hashlib.sha256(second).hexdigest())

    def test_state_file_is_private_and_round_trips_claim(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary) / "state.json"
            expected = {"executionId": "execution-1", "trainingRunId": "run-1"}

            remote_worker.save_state(state, expected)

            self.assertEqual(remote_worker.load_state(state), expected)
            self.assertEqual(state.stat().st_mode & 0o777, 0o600)

    def test_expired_claim_is_discarded_so_later_daily_work_can_continue(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = remote_worker.Worker.__new__(remote_worker.Worker)
            worker.state = Path(temporary) / "active-claim.json"
            remote_worker.save_state(worker.state, {
                "executionId": "execution-1", "trainingRunId": "run-1"})
            worker.process = Mock(side_effect=remote_worker.ClaimExpired("expired"))

            with self.assertRaises(remote_worker.ClaimExpired):
                worker.run_once()

            self.assertFalse(worker.state.exists())

    def test_upload_conflict_marks_the_claim_expired(self):
        with tempfile.TemporaryDirectory() as temporary:
            artifact = Path(temporary) / "adapter"
            artifact.mkdir()
            (artifact / "adapters.safetensors").write_bytes(b"weights")
            worker = remote_worker.Worker.__new__(remote_worker.Worker)
            worker.cloud = "https://risk.example/training-node"
            worker.token = "cloud-token"
            worker.node = "mac-node"
            worker.trainer = "http://127.0.0.1:63201/v1/train"
            worker.trainer_token = "trainer-token"
            worker.state = Path(temporary) / "active-claim.json"
            worker.artifact_index = Path(temporary) / "artifact-index.json"
            worker.artifact_root = Path(temporary)
            worker.heartbeat_seconds = 300
            job = {
                "executionId": "11111111-1111-1111-1111-111111111111",
                "trainingRunId": "22222222-2222-2222-2222-222222222222",
                "modelId": "33333333-3333-3333-3333-333333333333",
                "modelCode": "qiliang-risk-llm-v1",
                "baseModelReference": "mlx-community/Qwen3.8-27B-4bit",
                "domainCode": "CROSS_DOMAIN",
                "modelVersion": 3,
                "trainingSnapshotId": "44444444-4444-4444-4444-444444444444",
                "randomSeed": 7,
                "examples": [],
            }
            trained = json.dumps({
                "artifactReference": str(artifact),
                "artifactSha256": "a" * 64,
            }).encode()
            with patch.object(remote_worker, "request", side_effect=[(200, trained), (409, b"")]):
                with self.assertRaises(remote_worker.ClaimExpired):
                    worker.process(job)

    def test_remote_shadow_scoring_uses_the_exact_local_adapter(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "models" / "model-1" / "v3-snapshot"
            artifact.mkdir(parents=True)
            (artifact / "adapter_config.json").write_text("{}", encoding="utf-8")
            (artifact / "adapters.safetensors").write_bytes(b"weights")
            content_hash = remote_worker.canonical_hash(artifact)
            bundle_hash = hashlib.sha256(remote_worker.bundle_artifact(artifact)).hexdigest()
            worker = remote_worker.Worker.__new__(remote_worker.Worker)
            worker.cloud = "https://risk.example/training-node"
            worker.token = "cloud-token"
            worker.node = "mac-node"
            worker.trainer = "http://127.0.0.1:63201/v1/train"
            worker.trainer_token = "trainer-token"
            worker.artifact_root = root / "models"
            worker.artifact_index = root / "state/artifact-index.json"
            task = {
                "modelId": "model-1", "modelVersion": 3,
                "baseModelReference": "base", "assessmentId": "assessment-1",
                "artifactReference": "risk-artifact://sha256/" + bundle_hash,
                "artifactSha256": bundle_hash, "input": "真实证据",
            }
            responses = [
                (200, json.dumps(task).encode()),
                (200, b'{"predictedPositive":true,"positiveProbability":0.81}'),
                (204, b""),
            ]
            with patch.object(remote_worker, "request", side_effect=responses) as call:
                self.assertTrue(worker.score_once())

            sent = json.loads(call.call_args_list[2].args[3])
            self.assertEqual(sent["artifactSha256"], bundle_hash)
            index = remote_worker.load_state(worker.artifact_index)
            self.assertEqual(index[task["artifactReference"]]["contentSha256"], content_hash)

    def test_expert_claim_maps_only_fixed_local_contract_and_is_saved_privately(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            claim = self.expert_claim()
            with patch.object(remote_worker, "request", return_value=(200, json.dumps(claim).encode())):
                saved = worker.expert_claim()
            self.assertEqual(set(saved), {"taskId", "runId", "dataset", "config", "progress"})
            self.assertEqual({key: saved[key] for key in ("runId", "dataset", "config")},
                             {key: claim[key] for key in ("runId", "dataset", "config")})
            self.assertNotIn("promptPath", json.dumps(saved))
            remote_worker.save_state(worker.expert_state, saved)
            self.assertEqual(worker.expert_state.stat().st_mode & 0o777, 0o600)

    def test_saved_expert_state_resumes_before_requesting_new_claim(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            saved = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, saved)
            with patch.object(worker, "process_expert") as process, patch.object(
                    worker, "expert_claim", side_effect=AssertionError("must resume")):
                self.assertTrue(worker.expert_once())
            process.assert_called_once_with(saved | {"progress": 0})

    def test_preflight_cancellation_acknowledges_without_calling_local_trainer(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            responses = [(200, b'{"cancelRequested":true,"leaseUntil":"later"}'), (204, b"")]
            with patch.object(remote_worker, "request", side_effect=responses) as call:
                worker.process_expert(state)
            self.assertEqual(call.call_args_list[0].args[1], worker.cloud +
                             "/expert-tasks/11111111-1111-1111-1111-111111111111/heartbeat")
            self.assertEqual(call.call_args_list[1].args[1], worker.cloud +
                             "/expert-tasks/11111111-1111-1111-1111-111111111111/cancelled")
            self.assertFalse(worker.expert_state.exists())

    def test_inflight_cancellation_calls_exact_local_cancel_and_never_uploads(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            local_started = threading.Event()
            calls = []
            def transport(method, url, headers, body, timeout):
                calls.append((url, body))
                if url.endswith("/heartbeat"):
                    count = sum(item[0].endswith("/heartbeat") for item in calls)
                    return (200, json.dumps({"cancelRequested": count > 1, "leaseUntil": "later"}).encode())
                if url == worker.expert_trainer:
                    local_started.set()
                    time.sleep(.05)
                    return 409, b'{"error":"EXPERT_TRAINING_CANCELLED"}'
                if url == worker.expert_cancel:
                    return 200, b'{"status":"CANCEL_REQUESTED"}'
                if url.endswith("/cancelled"):
                    return 204, b""
                if url.endswith("/progress"):
                    return 204, b""
                raise AssertionError(url)
            with patch.object(remote_worker, "request", side_effect=transport):
                worker.process_expert(state)
            cancel = next(body for url, body in calls if url == worker.expert_cancel)
            self.assertEqual(json.loads(cancel), {"runId": "expert-run-1"})
            self.assertFalse(any(url.endswith("/artifacts") or url.endswith("/completion") for url, _ in calls))
            self.assertFalse(worker.expert_state.exists())

    def test_lease_loss_discards_saved_state_without_upload_or_completion(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            with patch.object(remote_worker, "request", return_value=(409, b"")):
                with self.assertRaises(remote_worker.ClaimExpired):
                    worker.process_expert(state)
            self.assertFalse(worker.expert_state.exists())

    def test_transient_cloud_failure_retains_expert_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            with patch.object(remote_worker, "request", side_effect=OSError("offline-secret")):
                with self.assertRaises(OSError):
                    worker.process_expert(state)
            self.assertEqual(remote_worker.load_state(worker.expert_state), state)

    def test_permanent_local_failure_is_redacted_and_deletes_only_after_ack(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            responses = [(200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b""), (204, b""),
                         (503, b'raw stderr credential secret'),
                         (200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b"")]
            with patch.object(remote_worker, "request", side_effect=responses) as call:
                worker.process_expert(state)
            failure = json.loads(call.call_args_list[-1].args[3])
            self.assertEqual(failure, {"code": "LOCAL_TRAINING_FAILED",
                                       "message": "本地专家训练失败，未生成候选工件。"})
            self.assertFalse(worker.expert_state.exists())

    def test_strict_artifact_validation_rejects_outside_root_and_unbounded_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            worker = self.expert_worker(root)
            artifact, candidate = self.candidate(worker)
            worker.validate_expert_result(candidate)
            outside = root / "outside"
            artifact.rename(outside)
            candidate["artifactPath"] = str(outside)
            with self.assertRaises(ValueError):
                worker.validate_expert_result(candidate)
            candidate["artifactPath"] = str(artifact)
            outside.rename(artifact)
            candidate["metrics"] = {"value": "x" * 5000}
            with self.assertRaises(ValueError):
                worker.validate_expert_result(candidate)

    def test_deterministic_upload_and_exact_completion_then_deletes_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            artifact, candidate = self.candidate(worker)
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            expected_bundle = remote_worker.bundle_artifact(artifact)
            expected_bundle_hash = hashlib.sha256(expected_bundle).hexdigest()
            responses = [(200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b""), (204, b""), (200, json.dumps(candidate).encode()),
                         (200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                         (204, b""), (204, b""),
                         (201, json.dumps({"artifactReference": "expert://stored/1",
                                          "bundleSha256": expected_bundle_hash,
                                          "contentSha256": candidate["artifactSha256"],
                                          "sizeBytes": len(expected_bundle)}).encode()),
                         (204, b""), (204, b"")]
            with patch.object(remote_worker, "request", side_effect=responses) as call:
                worker.process_expert(state)
            local = next(item for item in call.call_args_list if item.args[1] == worker.expert_trainer)
            self.assertEqual(json.loads(local.args[3]), {
                "runId": state["runId"], "dataset": state["dataset"], "config": state["config"]})
            upload = next(item for item in call.call_args_list if item.args[1].endswith("/artifacts"))
            self.assertEqual(upload.args[3], expected_bundle)
            completion = next(item for item in call.call_args_list if item.args[1].endswith("/completion"))
            self.assertEqual(json.loads(completion.args[3]), {
                "artifactReference": "expert://stored/1", "artifactSha256": expected_bundle_hash,
                "metrics": candidate["metrics"]})
            self.assertFalse(worker.expert_state.exists())

    def test_completion_response_loss_replays_only_exact_completion(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = self.expert_worker(Path(temporary))
            artifact, candidate = self.candidate(worker)
            state = {key: self.expert_claim()[key] for key in ("taskId", "runId", "dataset", "config")}
            remote_worker.save_state(worker.expert_state, state)
            bundle = remote_worker.bundle_artifact(artifact)
            bundle_hash = hashlib.sha256(bundle).hexdigest()
            stored = {"artifactReference": "expert://stored/replay",
                      "bundleSha256": bundle_hash,
                      "contentSha256": candidate["artifactSha256"], "sizeBytes": len(bundle)}
            first = [(200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                     (204, b""), (204, b""), (200, json.dumps(candidate).encode()),
                     (200, b'{"cancelRequested":false,"leaseUntil":"later"}'),
                     (204, b""), (204, b""), (201, json.dumps(stored).encode()),
                     (204, b"")]
            with patch.object(remote_worker, "request", side_effect=first + [OSError("lost response")]):
                with self.assertRaises(OSError):
                    worker.process_expert(state)
            retained = remote_worker.load_state(worker.expert_state)
            replay_calls = []
            def replay(method, url, headers, body, timeout):
                replay_calls.append((url, body))
                if url.endswith("/heartbeat"):
                    return 200, b'{"cancelRequested":false,"leaseUntil":"later"}'
                if url.endswith("/completion"):
                    return 204, b""
                raise AssertionError("completion replay regressed or repeated work: " + url)
            with patch.object(remote_worker, "request", side_effect=replay):
                worker.process_expert(retained)
            self.assertEqual(json.loads(replay_calls[-1][1]), {
                "artifactReference": stored["artifactReference"],
                "artifactSha256": stored["bundleSha256"], "metrics": candidate["metrics"]})
            self.assertFalse(worker.expert_state.exists())

    def test_trainer_url_must_be_loopback_and_expert_urls_are_derived(self):
        base = {"RISK_TRAINING_CLOUD_URL": "https://risk.example/training-node",
                "RISK_TRAINING_NODE_TOKEN": "cloud", "RISK_TRAINING_NODE_ID": "node",
                "RISK_LLM_BEARER_TOKEN": "local"}
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
                os.environ, base | {"RISK_TRAINING_NODE_STATE_ROOT": temporary,
                                    "RISK_LLM_ARTIFACT_ROOT": temporary,
                                    "RISK_LLM_TRAINER_URL": "http://127.0.0.1:63201/v1/train"}, clear=True):
            worker = remote_worker.Worker()
            self.assertEqual(worker.expert_trainer, "http://127.0.0.1:63201/v1/expert-train")
            self.assertEqual(worker.expert_cancel, "http://127.0.0.1:63201/v1/expert-train-cancel")
        for value in ("https://example.com/v1/train", "http://127.0.0.1:63201/wrong",
                      "http://user@127.0.0.1:63201/v1/train?prompt=bad"):
            with self.subTest(value=value), patch.dict(os.environ, base | {
                    "RISK_LLM_TRAINER_URL": value}, clear=True):
                with self.assertRaises(RuntimeError):
                    remote_worker.Worker()


if __name__ == "__main__":
    unittest.main()
