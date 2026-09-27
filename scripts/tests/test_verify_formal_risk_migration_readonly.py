import importlib.util
import json
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "verify-formal-risk-migration-readonly.py"


def snapshot(**changes):
    value = {
        "database": "qiqihar_enterprise_dev",
        "serverAddress": "127.0.0.1",
        "serverPort": 5432,
        "transactionReadOnly": "on",
        "latestVersion": "213",
        "historyCount": 213,
        "failedCount": 0,
        "riskSchemaPresent": False,
        "riskMigrations": [],
    }
    value.update(changes)
    return value


class FormalRiskReadonlyTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.exists(), "read-only preflight script missing")
        spec = importlib.util.spec_from_file_location("formal_risk_readonly", SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_exact_pending_range_uses_fixed_target_and_readonly_session(self):
        calls = []

        def run(command, **kwargs):
            calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, 0, json.dumps(snapshot()), "")

        result = self.module.probe(run=run)
        self.assertEqual(result["dbScope"], "MATCH_PENDING_RISK_MIGRATIONS")
        self.assertFalse(result["applyAllowed"])
        self.assertEqual(len(calls), 1)
        command, kwargs = calls[0]
        self.assertIn("--host=127.0.0.1", command)
        self.assertIn("--port=5432", command)
        self.assertIn("--dbname=qiqihar_enterprise_dev", command)
        self.assertIn("default_transaction_read_only=on", kwargs["env"]["PGOPTIONS"])
        self.assertIn("current_setting('transaction_read_only')", command[-1])
        self.assertNotIn("INSERT", command[-1].upper())
        self.assertNotIn("UPDATE", command[-1].upper())
        self.assertNotIn("DELETE", command[-1].upper())

    def test_wrong_target_or_session_fails_closed(self):
        for change in (
            {"database": "other"},
            {"serverAddress": "127.0.0.2"},
            {"serverPort": 5544},
            {"transactionReadOnly": "off"},
        ):
            with self.subTest(change=change):
                def run(command, **kwargs):
                    return subprocess.CompletedProcess(command, 0, json.dumps(snapshot(**change)), "")

                result = self.module.probe(run=run)
                self.assertEqual(result["dbScope"], "BLOCKED")
                self.assertIn("TARGET_OR_READONLY_MISMATCH", result["blockers"])
                self.assertFalse(result["applyAllowed"])

    def test_inherited_postgres_routing_cannot_override_fixed_target(self):
        captured = {}

        def run(command, **kwargs):
            captured.update(kwargs["env"])
            return subprocess.CompletedProcess(command, 0, json.dumps(snapshot()), "")

        with patch.dict("os.environ", {
            "PGHOSTADDR": "192.0.2.8", "PGHOST": "example.invalid",
            "PGPORT": "9999", "PGDATABASE": "other", "PGSERVICE": "other",
            "PGOPTIONS": "-c default_transaction_read_only=off",
        }):
            self.module.probe(run=run)
        for key in ("PGHOSTADDR", "PGHOST", "PGPORT", "PGDATABASE", "PGSERVICE"):
            self.assertFalse(key in captured, f"{key} leaked into the psql environment")
        self.assertEqual(captured["PGOPTIONS"].count("default_transaction_read_only=on"), 1)

    def test_partial_or_advanced_migration_fails_closed(self):
        cases = (
            snapshot(latestVersion="214", historyCount=214, riskSchemaPresent=True,
                     riskMigrations=[{"version": "214", "script": "V214__create_inventory_risk_foundation.sql", "success": True}]),
            snapshot(latestVersion="217", historyCount=217, riskSchemaPresent=True,
                     riskMigrations=[{"version": str(v), "script": "x", "success": True} for v in range(214, 218)]),
            snapshot(failedCount=1),
            snapshot(riskSchemaPresent=True),
        )
        for value in cases:
            with self.subTest(value=value):
                def run(command, **kwargs):
                    return subprocess.CompletedProcess(command, 0, json.dumps(value), "")

                result = self.module.probe(run=run)
                self.assertEqual(result["dbScope"], "BLOCKED")
                self.assertFalse(result["applyAllowed"])

    def test_query_failure_fails_closed_without_leaking_error(self):
        def run(command, **kwargs):
            raise subprocess.CalledProcessError(1, command, stderr="secret connection detail")

        result = self.module.probe(run=run)
        self.assertEqual(result["dbScope"], "BLOCKED")
        self.assertEqual(result["blockers"], ["QUERY_FAILED"])
        self.assertNotIn("secret connection detail", json.dumps(result))
        self.assertFalse(result["applyAllowed"])


if __name__ == "__main__":
    unittest.main()
