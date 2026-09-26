"""Exercise install's stop/port boundary without touching launchd or real ports."""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "local-runtime.sh"


class PortTimeoutTest(unittest.TestCase):
    def run_install(self, *, occupied=True, old_plist=True, record_failure=False):
        temporary = tempfile.TemporaryDirectory(prefix="runtime port recovery ")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "agents").mkdir()
        (root / "runtime").mkdir()
        (root / "runtime/version").write_text("old runtime")
        (root / "source.plist").write_text("candidate plist")
        if old_plist:
            (root / "agents/label.plist").write_text("old plist")
        source = SCRIPT.read_text()
        start = source.index("install_agent() {")
        if "record_port_release_timeout() {" in source:
            start = source.index("record_port_release_timeout() {")
        functions = source[start:source.index("\nstart_agent() {")]
        harness = r'''#!/bin/bash
set -euo pipefail
backend_root="$1"
source_workspace_root="$1"
runtime_home="$1"
runtime_root="$1/state"
snapshot_workspace="$1/runtime"
launch_agents_dir="$1/agents"
launchd_log_dir="$1/logs"
source_plist="$1/source.plist"
installed_plist="$1/agents/label.plist"
trace="$1/trace"
domain=fixture
service_target=fixture
backend_port=8090
business_port=63182
overview_port=63200
python3() {
  case "$1" in
    */verify-*.py) return 0 ;;
  esac
  if [[ "$RECORD_FAILURE" == 1 ]]; then return 1; fi
  command python3 "$@"
}
plutil(){ return 0; }
assert_source_git_metadata_is_safe(){ return 0; }
agent_is_loaded(){ return 0; }
launchctl(){ printf '%s\n' "$*" >> "$trace"; }
kill(){ printf 'unexpected-kill\n' >> "$trace"; return 1; }
wait_for_ports_released(){ printf 'ports-checked\n' >> "$trace"; return "$OCCUPIED"; }
# Stop after reaching staging; this test does not simulate health rollback.
refresh_runtime_snapshot(){ printf 'stage-reached\n' >> "$trace"; exit 42; }
'''
        harness += f"\nOCCUPIED={int(occupied)}\nRECORD_FAILURE={int(record_failure)}\n"
        harness += functions + "\ninstall_agent\n"
        result = subprocess.run(["/bin/bash", "-c", harness, "fixture", str(root)],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual((root / "runtime/version").read_text(), "old runtime")
        if old_plist:
            self.assertEqual((root / "agents/label.plist").read_text(), "old plist")
        else:
            self.assertFalse((root / "agents/label.plist").exists())
        return root, result

    def test_timeout_preserves_recovery_record_and_does_not_stage_or_restart(self):
        root, result = self.run_install()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual((root / "trace").read_text().splitlines(),
                         ["bootout fixture", "ports-checked"])
        records = list(root.glob(".runtime-install.recovery.*.json"))
        self.assertEqual(len(records), 1, result.stderr)
        self.assertEqual(records[0].stat().st_mode & 0o777, 0o600)
        record = json.loads(records[0].read_text())
        self.assertEqual(record["state"], "MAINTENANCE_REQUIRED")
        self.assertEqual(record["reason"], "PORT_RELEASE_TIMEOUT")
        self.assertFalse(record["runtimeReplaced"])
        self.assertFalse(record["serviceRestored"])
        self.assertEqual(record["ports"], [8090, 63182, 63200])
        self.assertEqual(record["snapshotWorkspace"], str(root / "runtime"))
        self.assertEqual(record["installedPlist"], str(root / "agents/label.plist"))
        backup = Path(record["previousPlist"])
        self.assertEqual(backup.read_text(), "old plist")
        self.assertEqual(backup.stat().st_mode & 0o777, 0o600)
        self.assertIn("MAINTENANCE_REQUIRED", result.stderr)
        self.assertIn(str(records[0]), result.stderr)

    def test_absent_previous_plist_is_recorded_without_inventing_backup(self):
        root, result = self.run_install(old_plist=False)
        self.assertEqual(result.returncode, 1)
        records = list(root.glob(".runtime-install.recovery.*.json"))
        self.assertEqual(len(records), 1, result.stderr)
        self.assertIsNone(json.loads(records[0].read_text())["previousPlist"])

    def test_record_write_failure_still_reports_recovery_paths_and_stops(self):
        root, result = self.run_install(record_failure=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Could not persist recovery record", result.stderr)
        self.assertIn(str(root / "runtime"), result.stderr)
        backup, = root.glob(".runtime-install.plist.*")
        self.assertIn(str(backup), result.stderr)
        self.assertEqual(backup.read_text(), "old plist")
        self.assertEqual((root / "trace").read_text().splitlines(),
                         ["bootout fixture", "ports-checked"])

    def test_released_ports_continue_to_staging_without_maintenance_record(self):
        root, result = self.run_install(occupied=False)
        self.assertEqual(result.returncode, 42, result.stderr)
        self.assertEqual(list(root.glob(".runtime-install.recovery.*.json")), [])
        self.assertEqual((root / "trace").read_text().splitlines(),
                         ["bootout fixture", "ports-checked", "stage-reached"])


if __name__ == "__main__":
    unittest.main()
