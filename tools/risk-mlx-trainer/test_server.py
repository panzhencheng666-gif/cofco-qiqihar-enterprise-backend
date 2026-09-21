import json
import tempfile
import unittest
from pathlib import Path

import server


class TrainerContractTest(unittest.TestCase):
    def test_dataset_contains_real_binary_completions_and_hash_detects_change(self):
        with tempfile.TemporaryDirectory() as temporary:
            data = Path(temporary) / "data"
            server.write_dataset(data, [
                {"input": "风险证据一", "positive": True},
                {"input": "正常证据一", "positive": False},
                {"input": "风险证据二", "positive": True},
                {"input": "正常证据二", "positive": False},
            ])
            rows = [json.loads(line) for line in (data / "train.jsonl").read_text().splitlines()]
            self.assertEqual({row["completion"] for row in rows}, {"0", "1"})
            before = server.canonical_hash(data)
            (data / "train.jsonl").write_text("{}\n", encoding="utf-8")
            self.assertNotEqual(server.canonical_hash(data), before)

    def test_dataset_rejects_single_class_training(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "正负真实标签"):
                server.write_dataset(Path(temporary) / "data", [
                    {"input": str(index), "positive": True} for index in range(4)
                ])


if __name__ == "__main__":
    unittest.main()
