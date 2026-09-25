"""Market install may not apply pending risk migrations."""

import importlib.util
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


if __name__ == "__main__":
    unittest.main()
