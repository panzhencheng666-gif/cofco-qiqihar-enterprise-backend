"""Exercise the install gate without contacting official remotes or services."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "verify-local-install-source.py"
SPEC = importlib.util.spec_from_file_location("verify_local_install_source", SCRIPT)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)
COMMIT = "a" * 40


class InstallSourceGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="qiliang-install-gate-")
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name)
        for name in gate.ORIGINS:
            (self.workspace / f"cofco-qiqihar-enterprise-{name}").mkdir()
        self.manifest = self.workspace / "release.json"
        self.manifest.write_text(json.dumps({
            "manifest": {
                "environment": "candidate",
                "repositories": {
                    name: {"origin": origin, "ref": "main", "commitSha": COMMIT}
                    for name, origin in gate.ORIGINS.items()
                },
            },
        }))
        self.calls = []

    def command(self, *args, cwd=None, timeout=20):
        self.calls.append((args, cwd, timeout))
        if args[:2] == ("node", str(self.workspace / "cofco-qiqihar-enterprise-web/scripts/release-manifest-cli.mjs")):
            return "manifest-valid" if args[2] == "validate" else "runtime-valid"
        if args[:3] == ("git", "rev-parse", "--show-toplevel"):
            return str(cwd.resolve())
        if args[:2] == ("git", "status"):
            return ""
        if args[:3] == ("git", "remote", "get-url"):
            return gate.ORIGINS[cwd.name.removeprefix("cofco-qiqihar-enterprise-")]
        if args[:3] == ("git", "rev-parse", "HEAD"):
            return COMMIT
        if args[:2] == ("git", "ls-remote"):
            return f"{COMMIT}\trefs/heads/main"
        if args[:2] == ("bash", "-c"):
            return "/opt/homebrew/opt/openjdk@21"
        if args[0].endswith("/bin/java"):
            return 'openjdk version "21.0.12"'
        if args == ("node", "--version"):
            return "v24.19.0"
        if args == ("npm", "--version"):
            return "11.17.0"
        self.fail(f"unexpected command: {args}")

    def test_verified_manifest_binds_three_roots_and_versions(self):
        with patch.object(gate, "command", side_effect=self.command):
            gate.verify(str(self.manifest), self.workspace)
        verify = [args for args, _, _ in self.calls if len(args) > 2 and args[0] == "node" and args[2] == "verify"]
        self.assertEqual(len(verify), 1)
        self.assertIn("--backend-root", verify[0])
        self.assertIn("--frontend-root", verify[0])
        self.assertIn("--web-root", verify[0])
        self.assertIn("21.0.12", verify[0])

    def test_content_mismatch_stops_install(self):
        def reject_content(*args, **kwargs):
            if len(args) > 2 and args[0] == "node" and args[2] == "verify":
                raise ValueError("asset sha256 mismatch")
            return self.command(*args, **kwargs)

        with patch.object(gate, "command", side_effect=reject_content):
            with self.assertRaisesRegex(ValueError, "asset sha256 mismatch"):
                gate.verify(str(self.manifest), self.workspace)


if __name__ == "__main__":
    unittest.main()
