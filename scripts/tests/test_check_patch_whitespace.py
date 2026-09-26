import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = "src/main/resources/db/migration/V208__share_enabled_employee_business_events.sql"
ORIGINAL = (ROOT / MIGRATION).read_bytes()


class PatchWhitespaceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Whitespace Test")
        self.git("config", "user.email", "test@example.invalid")
        self.path = self.repo / MIGRATION
        self.path.parent.mkdir(parents=True)
        self.path.write_bytes(ORIGINAL.rstrip(b"\n") + b"\n")
        self.commit()
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, text=True)

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "test change")

    def check(self, head="HEAD"):
        if os.environ.get("PATCH_WHITESPACE_LEGACY") == "1":
            command = ["git", "diff", "--check", self.base, head]
        else:
            command = [sys.executable, str(ROOT / "scripts/check-patch-whitespace.py"), self.base, head]
        return subprocess.run(command, cwd=self.repo, capture_output=True, text=True)

    def test_accepts_exact_restored_migration(self):
        self.path.write_bytes(ORIGINAL)
        self.commit()
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_changed_sql_even_without_whitespace_error(self):
        self.path.write_bytes(ORIGINAL.rstrip(b"\n") + b"\n-- changed SQL\n")
        self.commit()
        self.assertNotEqual(self.check().returncode, 0)

    def test_rejects_deleted_migration(self):
        self.path.unlink()
        self.commit()
        self.assertNotEqual(self.check().returncode, 0)

    def test_does_not_exempt_other_whitespace_errors(self):
        self.path.write_bytes(ORIGINAL)
        (self.repo / "another.sql").write_text("SELECT 1;  \n")
        self.commit()
        self.assertNotEqual(self.check().returncode, 0)

    def test_checks_requested_revision_not_working_copy(self):
        self.path.write_bytes(ORIGINAL)
        self.commit()
        self.path.write_text("uncommitted content\n")
        self.assertEqual(self.check().returncode, 0)

    def test_clean_patch_without_migration_change(self):
        (self.repo / "another.sql").write_text("SELECT 1;\n")
        self.commit()
        self.assertEqual(self.check().returncode, 0)

    def test_invalid_revision_fails(self):
        self.assertNotEqual(self.check("missing-revision").returncode, 0)


if __name__ == "__main__":
    unittest.main()
