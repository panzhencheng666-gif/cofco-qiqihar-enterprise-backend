"""Market install may not apply pending risk migrations."""

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "verify-market-risk-migration-scope.py"
SPEC = importlib.util.spec_from_file_location("verify_market_risk_scope", SCRIPT)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class RiskMigrationScopeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="qiliang-risk-scope-")
        self.addCleanup(self.temporary.cleanup)
        self.backend = Path(self.temporary.name)
        self.migrations = self.backend / "src/main/resources/db/migration"
        self.migrations.mkdir(parents=True)
        self.config = self.backend / "runtime.env"
        self.config.write_text("QIQIHAR_DB_URL=jdbc:postgresql://127.0.0.1:5432/qiqihar_enterprise_dev\n")
        self.config.chmod(0o600)
        self.environment = patch.dict(os.environ, {"COFCO_ENTERPRISE_LOCAL_ENV_FILE": str(self.config)}, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_market_only_source_needs_no_database_lookup(self):
        with patch.object(gate, "installed_migrations") as lookup:
            gate.verify(self.backend)
        lookup.assert_not_called()

    def test_pending_risk_migration_blocks_market_install(self):
        (self.migrations / gate.RISK_MIGRATIONS["216"]).write_text("UPDATE risk.ai_training_policy")
        with patch.object(gate, "installed_migrations", return_value={}):
            with self.assertRaisesRegex(ValueError, "V216__automate_risk_model_promotion"):
                gate.verify(self.backend)

    def test_matching_applied_risk_migration_passes(self):
        name = gate.RISK_MIGRATIONS["216"]
        (self.migrations / name).write_text("already applied")
        with patch.object(gate, "installed_migrations", return_value={"216": name}):
            gate.verify(self.backend)

    def test_unavailable_database_fails_closed(self):
        with patch.object(gate.subprocess, "run", return_value=CompletedProcess([], 1, "", "offline")):
            with self.assertRaisesRegex(ValueError, "history is unavailable"):
                gate.installed_migrations()

    def test_configured_other_database_rejected_before_query(self):
        self.config.write_text("QIQIHAR_DB_URL=jdbc:postgresql://127.0.0.1:55439/other_db?password=private-value\n")
        with patch.object(gate.subprocess, "run") as query:
            with self.assertRaisesRegex(ValueError, "database target") as error:
                gate.installed_migrations()
        self.assertNotIn("private-value", str(error.exception))
        query.assert_not_called()

    def test_missing_explicit_config_target_rejected(self):
        self.config.write_text("QIQIHAR_RISK_TRAINING_ENABLED=false\n")
        with patch.object(gate.subprocess, "run") as query:
            with self.assertRaisesRegex(ValueError, "database target"):
                gate.installed_migrations()
        query.assert_not_called()

    def test_missing_config_rejected(self):
        self.config.unlink()
        with patch.object(gate.subprocess, "run") as query:
            with self.assertRaises(ValueError):
                gate.installed_migrations()
        query.assert_not_called()

    def test_world_readable_config_rejected(self):
        self.config.chmod(0o644)
        with patch.object(gate.subprocess, "run") as query:
            with self.assertRaisesRegex(ValueError, "permissions"):
                gate.installed_migrations()
        query.assert_not_called()

    def test_spring_url_override_rejected(self):
        for key in ["SPRING_DATASOURCE_URL", "SPRING_FLYWAY_URL", "SPRING_APPLICATION_JSON"]:
            with self.subTest(key=key), patch.dict(os.environ, {key: "alternate-target"}), patch.object(gate.subprocess, "run") as query:
                with self.assertRaisesRegex(ValueError, "override"):
                    gate.installed_migrations()
                query.assert_not_called()

    def test_config_overrides_shell_environment_like_launcher(self):
        with patch.dict(os.environ, {"QIQIHAR_DB_URL": "jdbc:postgresql://other/db"}), patch.object(
            gate.subprocess, "run", return_value=CompletedProcess([], 0, "", "")
        ) as query:
            self.assertEqual({}, gate.installed_migrations())
        self.assertIn("--dbname=qiqihar_enterprise_dev", query.call_args.args[0])

    def test_last_config_value_wins_like_launcher(self):
        self.config.write_text(self.config.read_text()+"QIQIHAR_DB_URL=jdbc:postgresql://other/db\n")
        with patch.object(gate.subprocess, "run") as query:
            with self.assertRaisesRegex(ValueError, "database target"):
                gate.installed_migrations()
        query.assert_not_called()

    def test_crlf_config_and_literal_nonexecuted_values(self):
        self.config.write_bytes(b"# comment\r\nQIQIHAR_DB_URL=jdbc:postgresql://127.0.0.1:5432/qiqihar_enterprise_dev\r\nQIQIHAR_DB_PASSWORD=$(not-a-command)\r\n")
        with patch.object(gate.subprocess, "run", return_value=CompletedProcess([],0,"", "")) as query:
            self.assertEqual({}, gate.installed_migrations())
        self.assertEqual(1, query.call_count)


if __name__ == "__main__":
    unittest.main()
