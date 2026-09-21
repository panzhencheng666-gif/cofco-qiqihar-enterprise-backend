import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import remote_worker


class RemoteWorkerContractTest(unittest.TestCase):
    def test_maps_cloud_job_to_local_trainer_contract(self):
        job = {
            "modelId": "11111111-1111-1111-1111-111111111111",
            "modelCode": "risk-reasoning-llm-v1",
            "baseModelReference": "mlx-community/Qwen3-0.6B-4bit",
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
                "modelCode": "risk-reasoning-llm-v1",
                "baseModelReference": "mlx-community/Qwen3-0.6B-4bit",
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


if __name__ == "__main__":
    unittest.main()
