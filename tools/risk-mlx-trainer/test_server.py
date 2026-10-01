import json
import tempfile
import unittest
from pathlib import Path

import server


class TrainerContractTest(unittest.TestCase):
    def test_dataset_contains_real_binary_completions_and_hash_detects_change(self):
        with tempfile.TemporaryDirectory() as temporary:
            data = Path(temporary) / "data"
            partitions = server.write_dataset(data, [
                {"input": "最早风险", "positive": True},
                {"input": "较早正常", "positive": False},
                {"input": "最近风险", "positive": True},
                {"input": "最新正常", "positive": False},
            ])
            rows = [json.loads(line) for line in (data / "train.jsonl").read_text().splitlines()]
            self.assertEqual({row["completion"] for row in rows}, {"0", "1"})
            before = server.canonical_hash(data)
            (data / "train.jsonl").write_text("{}\n", encoding="utf-8")
            self.assertNotEqual(server.canonical_hash(data), before)
            self.assertIn("最早风险", partitions["train"][0]["prompt"])
            self.assertIn("最近风险", partitions["valid"][0]["prompt"])
            self.assertIn("最新正常", partitions["test"][0]["prompt"])
            self.assertNotEqual(partitions["valid"], partitions["test"])

    def test_dataset_rejects_single_class_training(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "正负真实标签"):
                server.write_dataset(Path(temporary) / "data", [
                    {"input": str(index), "positive": True} for index in range(4)
                ])

    def test_existing_verified_adapter_is_a_safe_retry_result(self):
        payload = {
            "modelId": "model", "modelCode": "qiliang-risk-llm-v1",
            "candidateVersion": 3,
            "baseModelReference": "mlx-community/Qwen3.8-27B-4bit",
            "trainingSnapshotId": "snapshot", "randomSeed": 7,
            "examples": [
                {"input": "a", "positive": True},
                {"input": "b", "positive": False},
                {"input": "c", "positive": True},
                {"input": "d", "positive": False},
            ],
        }
        manifest = server.request_manifest(payload)
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "model" / "v3-snapshot"
            target.mkdir(parents=True)
            (target / "adapters.safetensors").write_bytes(b"real-weights")
            (target / "adapter_config.json").write_text("{}", encoding="utf-8")
            (target / "model_manifest.json").write_text(
                json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
            (target / "training_metrics.json").write_text(
                '{"splitStrategy":"chronological_train_validation_test","test":{"f1":0.5}}\n',
                encoding="utf-8")

            result = server.completed_artifact(target, 4, manifest)

            self.assertEqual(result["artifactReference"], str(target))
            self.assertEqual(result["artifactSha256"], server.canonical_hash(target))
            self.assertEqual(result["metrics"]["trainingExamples"], 4)
            self.assertEqual(result["metrics"]["modelIdentity"],
                             "齐粮智研模型 QL-Risk-27B")
            self.assertEqual(result["metrics"]["offlineEvaluation"]["test"]["f1"], 0.5)

    def test_existing_adapter_with_different_foundation_is_rejected(self):
        payload = {
            "modelId": "model", "modelCode": "qiliang-risk-llm-v1",
            "candidateVersion": 3, "baseModelReference": "wrong-foundation",
            "trainingSnapshotId": "snapshot", "randomSeed": 7,
            "examples": [
                {"input": "a", "positive": True},
                {"input": "b", "positive": False},
                {"input": "c", "positive": True},
                {"input": "d", "positive": False},
            ],
        }
        expected = server.request_manifest(payload)
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            (target / "adapters.safetensors").write_bytes(b"real-weights")
            (target / "adapter_config.json").write_text("{}", encoding="utf-8")
            stale = dict(expected)
            stale["foundationModel"] = "old-foundation"
            (target / "model_manifest.json").write_text(
                json.dumps(stale, ensure_ascii=False), encoding="utf-8")
            (target / "training_metrics.json").write_text("{}", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "foundationModel"):
                server.completed_artifact(target, 4, expected)


if __name__ == "__main__":
    unittest.main()
