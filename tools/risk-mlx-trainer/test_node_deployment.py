"""Deployment transactions in temporary roots; no actual launchd or MLX calls."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


REPO = Path(__file__).resolve().parents[2]
LABEL = "com.cofco.qiqihar.risk-training-node.local"
SFT_MODULES = ('expert_dataset.py', 'expert_training.py', 'expert_training_worker.py',
               'expert_training_artifacts.py', 'expert_training_process.py')


class NodeDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="node deployment-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.source = self.root / "source"
        for directory in ("scripts", "tools/risk-mlx-trainer"):
            (self.source / directory).mkdir(parents=True)
        for name in ("run-risk-training-node-launch-agent.sh", "local-process-ownership.sh"):
            shutil.copyfile(REPO / "scripts" / name, self.source / "scripts" / name)
        for name in ("server.py", "remote_worker.py", "expert.py", "expert_knowledge.json") + SFT_MODULES:
            shutil.copyfile(REPO / "tools/risk-mlx-trainer" / name,
                            self.source / "tools/risk-mlx-trainer" / name)
        self.runtime = self.root / "runtime"
        self.previous = self.runtime / "releases" / "previous"
        self.previous.mkdir(parents=True)
        (self.runtime / "state").mkdir()
        (self.runtime / ".risk-training-node-runtime").touch()
        self.current = self.runtime / "current"
        self.current.symlink_to(self.previous)
        self.config = self.root / "training-node.env"
        self.original = (b"# retain comment and bytes\nRISK_TRAINING_NODE_TOKEN=fixture-only-secret\n"
                         b"RISK_LLM_BEARER_TOKEN=fixture-only-bearer\n"
                         b"RISK_LLM_PORT=63201\nRISK_TRAINING_NODE_ID=node-fixture\n"
                         b"RISK_EXPERT_MODEL_PATH=/old/snapshot\nRISK_EXPERT_TIMEOUT_SECONDS=120\n"
                         b"\n# final comment without newline")
        self.config.write_bytes(self.original)
        self.config.chmod(0o600)
        self.plist = self.root / (LABEL + ".plist")
        self.plist.touch()
        self.model = self.root / "model snapshot $(touch SHOULD_NOT_EXIST)"
        self.model.mkdir()
        for name in ("config.json", "tokenizer_config.json"):
            (self.model / name).write_text("{}")
        (self.model / "model.safetensors").write_bytes(b"fixture-weights")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.actions = self.root / "actions"
        self.loaded = self.root / "loaded"
        self.loaded.touch()
        self.executable(self.bin / "launchctl", '''#!/bin/bash
printf '%s\\n' "$*" >> "$FIXTURE_ROOT/actions"
case "$1" in
 print) test -f "$FIXTURE_ROOT/loaded" ;;
 bootout) rm -f "$FIXTURE_ROOT/loaded" ;;
 bootstrap|kickstart)
   if [[ -f "$FIXTURE_ROOT/fail-start" && "$(readlink "$FIXTURE_ROOT/runtime/current")" != */previous ]]; then exit 1; fi
   touch "$FIXTURE_ROOT/loaded" ;;
 *) exit 99 ;;
esac
''')
        self.executable(self.bin / "ps", '#!/bin/bash\ncat "$FIXTURE_ROOT/processes"\n')
        self.executable(self.bin / "lsof", '#!/bin/bash\nexit 1\n')
        (self.root / "processes").write_text("")
        self.executable(self.bin / "curl", '#!/bin/bash\necho 200\n')
        self.executable(self.source / "scripts/healthcheck-risk-training-node-local.sh", '''#!/bin/bash
echo health >> "$FIXTURE_ROOT/health-calls"
if [[ -f "$FIXTURE_ROOT/slow-health" && "$(readlink "$FIXTURE_ROOT/runtime/current")" != */previous ]]; then exec sleep 2; fi
if [[ -f "$FIXTURE_ROOT/fail-health" && "$(readlink "$FIXTURE_ROOT/runtime/current")" != */previous ]]; then exit 1; fi
if [[ -f "$FIXTURE_ROOT/fail-rollback" ]]; then exit 1; fi
if [[ -f "$FIXTURE_ROOT/delay-health" ]]; then rm "$FIXTURE_ROOT/delay-health"; exit 1; fi
test -f "$FIXTURE_ROOT/loaded"
''')
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith("RISK_")}
        self.env.update(PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        FIXTURE_ROOT=str(self.root), RISK_EXPERT_MODEL_PATH=str(self.model))

    def executable(self, path, content):
        path.write_text(content)
        path.chmod(0o700)

    def run_upgrade(self):
        # Source only function definitions, then inject every filesystem boundary.
        # On the pre-implementation baseline strip dispatch to keep RED safe.
        script = (REPO / "scripts/risk-training-node-local.sh").read_text()
        script = script.split('case "${1:-}" in')[0]
        script = script.replace("wait_until_healthy() {", "real_wait_until_healthy() {")
        script = script.replace("wait_until_stopped() {", "real_wait_until_stopped() {")
        harness = self.root / "harness.sh"
        harness.write_text(script + '''
backend_root="$FIXTURE_ROOT/source"
runtime_home="$FIXTURE_ROOT/runtime"
releases_dir="$runtime_home/releases"
current_release="$runtime_home/current"
marker="$runtime_home/.risk-training-node-runtime"
config_file="$FIXTURE_ROOT/training-node.env"
config_dir="$FIXTURE_ROOT"
installed_plist="$FIXTURE_ROOT/com.cofco.qiqihar.risk-training-node.local.plist"
log_dir="$FIXTURE_ROOT/logs"
# Exercise the same bounded polling with a shorter budget in isolated tests.
if declare -f real_wait_until_healthy >/dev/null; then
  wait_until_healthy() { real_wait_until_healthy 0.12; }
fi
if declare -f real_wait_until_stopped >/dev/null; then
  wait_until_stopped() { real_wait_until_stopped 2; }
fi
upgrade_node
''')
        result = subprocess.run(["bash", str(harness)], env=self.env, cwd=self.root,
                                text=True, capture_output=True, timeout=10)
        self.assertNotIn("fixture-only-secret", result.stdout + result.stderr)
        self.assertNotIn("fixture-only-bearer", result.stdout + result.stderr)
        self.assertFalse((self.root / "SHOULD_NOT_EXIST").exists())
        return result

    def assert_rejected_before_stop(self, result, reason):
        self.assertNotEqual(0, result.returncode)
        self.assertIn(reason, result.stderr)
        actions = self.actions.read_text() if self.actions.exists() else ""
        self.assertNotIn("bootout", actions)
        self.assertEqual(self.previous, self.current.resolve())
        self.assertEqual(self.original, self.config.read_bytes())

    def test_success_packages_modules_preserves_config_and_targets_exact_service(self):
        result = self.run_upgrade()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotEqual(self.previous, self.current.resolve())
        for name in ("expert.py", "expert_knowledge.json") + SFT_MODULES:
            self.assertTrue((self.current / name).is_file(), f"Missing packaged {name}")
            self.assertEqual((self.source / "tools/risk-mlx-trainer" / name).read_bytes(),
                             (self.current / name).read_bytes())
            self.assertEqual(0o400, (self.current / name).stat().st_mode & 0o777)
        self.assertEqual(self.original.replace(b"RISK_EXPERT_MODEL_PATH=/old/snapshot",
                        b"RISK_EXPERT_MODEL_PATH=" + str(self.model).encode()), self.config.read_bytes())
        self.assertEqual(0o600, self.config.stat().st_mode & 0o777)
        self.assertTrue(self.previous.is_dir())
        backups = list(self.runtime.glob("upgrade.*"))
        self.assertEqual(1, len(backups))
        self.assertEqual(self.original, (backups[0] / "training-node.env").read_bytes())
        self.assertEqual(self.previous, (backups[0] / "release").resolve())
        self.assertTrue((self.root / "health-calls").exists())
        target = f"gui/{os.getuid()}/{LABEL}"
        for action in self.actions.read_text().splitlines():
            self.assertIn(action, [f"print {target}", f"bootout {target}",
                                  f"kickstart {target}", f"bootstrap gui/{os.getuid()} {self.plist}"])

    def test_missing_sources_rejected_before_stop(self):
        for name in ("expert.py", "expert_knowledge.json", "server.py", "remote_worker.py") + SFT_MODULES:
            with self.subTest(name=name):
                path = self.source / "tools/risk-mlx-trainer" / name
                saved = path.read_bytes()
                path.unlink()
                self.assert_rejected_before_stop(self.run_upgrade(), "source")
                path.write_bytes(saved)

    def test_invalid_model_rejected_before_stop(self):
        for model in ("org/model", str(self.root / "missing"), str(self.root)):
            with self.subTest(model=model):
                self.env["RISK_EXPERT_MODEL_PATH"] = model
                self.assert_rejected_before_stop(self.run_upgrade(), "model")

    def test_each_missing_model_metadata_rejected_before_stop(self):
        for name in ("config.json", "tokenizer_config.json", "model.safetensors"):
            with self.subTest(name=name):
                path = self.model / name
                saved = path.read_bytes()
                path.unlink()
                self.assert_rejected_before_stop(self.run_upgrade(), "model")
                path.write_bytes(saved)

    def test_active_claim_rejected_before_stop(self):
        (self.runtime / "state/active-claim.json").write_text("{}")
        self.assert_rejected_before_stop(self.run_upgrade(), "active claim")

    def test_active_expert_claim_rejected_before_stop(self):
        (self.runtime / "state/active-expert-claim.json").write_text("{}")
        self.assert_rejected_before_stop(self.run_upgrade(), "active claim")

    def test_custom_state_claim_rejected_before_stop(self):
        state = self.root / "custom state"
        state.mkdir()
        (state / "active-claim.json").touch()
        self.original += b"\nRISK_TRAINING_NODE_STATE_ROOT=" + str(state).encode() + b"\n"
        self.config.write_bytes(self.original)
        self.assert_rejected_before_stop(self.run_upgrade(), "active claim")

    def test_custom_state_expert_claim_rejected_before_stop(self):
        state = self.root / "custom expert state"
        state.mkdir()
        (state / "active-expert-claim.json").touch()
        self.original += b"\nRISK_TRAINING_NODE_STATE_ROOT=" + str(state).encode() + b"\n"
        self.config.write_bytes(self.original)
        self.assert_rejected_before_stop(self.run_upgrade(), "active claim")

    def test_model_worker_child_rejected_before_stop(self):
        (self.root / "processes").write_text(
            f"100 1 /python {self.previous}/risk-mlx-trainer.py\n"
            f"101 100 /python {self.previous}/expert.py --worker\n")
        self.assert_rejected_before_stop(self.run_upgrade(), "worker")

    def test_unknown_runtime_rejected_before_stop(self):
        (self.runtime / ".risk-training-node-runtime").unlink()
        self.assert_rejected_before_stop(self.run_upgrade(), "unrecognized")

    def test_failed_readiness_restores_config_release_and_reports_healthy_rollback(self):
        (self.root / "fail-health").touch()
        result = self.run_upgrade()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.previous, self.current.resolve())
        self.assertEqual(self.original, self.config.read_bytes())
        self.assertEqual(0o600, self.config.stat().st_mode & 0o777)
        self.assertIn("rollback healthy=yes", result.stderr)

    def test_failed_start_restores_config_and_release(self):
        (self.root / "fail-start").touch()
        result = self.run_upgrade()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.previous, self.current.resolve())
        self.assertEqual(self.original, self.config.read_bytes())
        self.assertIn("rollback healthy=yes", result.stderr)

    def test_packaging_failure_restores_config_and_release(self):
        self.executable(self.bin / "install", "#!/bin/bash\nexit 1\n")
        result = self.run_upgrade()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.previous, self.current.resolve())
        self.assertEqual(self.original, self.config.read_bytes())
        self.assertIn("rollback healthy=yes", result.stderr)

    def test_failed_release_directory_creation_never_attempts_install(self):
        real_mktemp = shutil.which("mktemp")
        self.executable(self.bin / "mktemp", f'''#!/bin/bash
case "$*" in *'/.install.'*) exit 1 ;; esac
exec "{real_mktemp}" "$@"
''')
        self.executable(self.bin / "install", '''#!/bin/bash
touch "$FIXTURE_ROOT/unexpected-install"
exit 1
''')
        result = self.run_upgrade()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / "unexpected-install").exists())
        self.assertEqual(self.previous, self.current.resolve())
        self.assertEqual(self.original, self.config.read_bytes())

    def test_relative_current_link_has_resolvable_checkpoint(self):
        self.current.unlink()
        self.current.symlink_to("releases/previous")
        result = self.run_upgrade()
        self.assertEqual(0, result.returncode, result.stderr)
        backup = next(self.runtime.glob("upgrade.*"))
        self.assertEqual(self.previous, (backup / "release").resolve())

    def test_slow_healthcheck_is_bounded_and_rolls_back(self):
        (self.root / "slow-health").touch()
        started = time.monotonic()
        result = self.run_upgrade()
        self.assertLess(time.monotonic() - started, 4)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.previous, self.current.resolve())
        self.assertIn("rollback healthy=yes", result.stderr)

    def test_missing_expert_key_is_appended_without_rewriting_other_bytes(self):
        self.original = self.original.replace(b"RISK_EXPERT_MODEL_PATH=/old/snapshot\n", b"")
        self.config.write_bytes(self.original)
        result = self.run_upgrade()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.original + b"\nRISK_EXPERT_MODEL_PATH=" + str(self.model).encode() + b"\n",
                         self.config.read_bytes())

    def test_failed_rollback_reports_unhealthy(self):
        (self.root / "fail-rollback").touch()
        result = self.run_upgrade()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("rollback healthy=no", result.stderr)

    def test_readiness_polls_after_initial_failure(self):
        (self.root / "delay-health").touch()
        result = self.run_upgrade()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.root / "health-calls").exists(), "Healthcheck was never called")
        self.assertGreaterEqual(len((self.root / "health-calls").read_text().splitlines()), 2)

    def test_no_explicit_model_keeps_config_bytes(self):
        self.env.pop("RISK_EXPERT_MODEL_PATH")
        result = self.run_upgrade()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.original, self.config.read_bytes())

    def test_loader_accepts_literal_expert_values(self):
        script = (REPO / "scripts/run-risk-training-node-launch-agent.sh").read_text()
        # Source loader only; never start trainer or cloud worker.
        script = script.split('\nload_config\n')[0]
        harness = self.source / "scripts/loader-fixture.sh"
        harness.write_text(script + '''
config_file="$FIXTURE_ROOT/training-node.env"
load_config
[[ "$RISK_EXPERT_MODEL_PATH" == "$EXPECTED_MODEL" ]]
[[ "$RISK_EXPERT_TIMEOUT_SECONDS" == 120 ]]
''')
        self.config.write_bytes(self.original.replace(b"/old/snapshot", str(self.model).encode()))
        self.env["EXPECTED_MODEL"] = str(self.model)
        result = subprocess.run(["bash", str(harness)], env=self.env, cwd=self.root,
                                text=True, capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.root / "SHOULD_NOT_EXIST").exists())

    def asynchronous_stop_boundary(self, stuck=""):
        """Three separate exit stages, advanced by target observations, no processes."""
        (self.root / "stuck").write_text(stuck)
        shutil.copyfile(self.config, self.root / "original-config")
        boundary = '''#!/usr/bin/env python3
import os
from pathlib import Path
import sys
r = Path(os.environ["FIXTURE_ROOT"])
name = Path(sys.argv[0]).name
args = sys.argv[1:]
stuck = (r / "stuck").read_text()
pending = (r / "stopping").exists()
n = int((r / "count").read_text()) if (r / "count").exists() else 0
if name == "launchctl":
    with (r / "actions").open("a") as f: f.write(" ".join(args) + "\\n")
    if args[0] == "bootout":
        (r / "stopping").touch()
    elif args[0] == "print":
        if pending:
            if ((r / "runtime/current").resolve() != r / "runtime/releases/previous"
                    or (r / "training-node.env").read_bytes() != (r / "original-config").read_bytes()):
                (r / "early-switch").touch()
            n += 1
            (r / "count").write_text(str(n))
            if n >= 2 and stuck != "registration":
                (r / "loaded").unlink(missing_ok=True)
        sys.exit(0 if (r / "loaded").exists() else 1)
    elif args[0] == "bootstrap":
        if not (r / "drained").exists(): sys.exit(4)
        (r / "stopping").unlink(missing_ok=True)
        (r / "loaded").touch()
        (r / "new-registration").touch()
    elif args[0] != "kickstart": sys.exit(99)
elif name == "ps":
    if not (r / "new-registration").exists() and (not pending or n < 4 or stuck == "trainer"):
        print(f"100 1 /python {r}/runtime/releases/previous/risk-mlx-trainer.py")
elif name == "lsof":
    with (r / "port-checks").open("a") as f: f.write(" ".join(args) + "\\n")
    if n < 6 or stuck == "port":
        print("100" if stuck != "port" else "999")
    else:
        if stuck not in ("registration", "trainer"): (r / "drained").touch()
        sys.exit(1)
'''
        for name in ("launchctl", "ps", "lsof"):
            self.executable(self.bin / name, boundary)
        self.executable(self.source / "scripts/healthcheck-risk-training-node-local.sh", '''#!/bin/bash
if [[ -f "$FIXTURE_ROOT/new-registration" ]]; then
  echo new >> "$FIXTURE_ROOT/health-generation"
else
  echo old >> "$FIXTURE_ROOT/health-generation"
fi
test -f "$FIXTURE_ROOT/loaded"
''')

    def test_async_stop_waits_for_registration_trainer_and_port_before_activation(self):
        self.asynchronous_stop_boundary()
        result = self.run_upgrade()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.root / "new-registration").exists(),
                        "UPGRADE_OK accepted old registration and old healthy listener")
        self.assertFalse((self.root / "early-switch").exists())
        self.assertEqual("new\n", (self.root / "health-generation").read_text())
        self.assertNotIn("kickstart", self.actions.read_text())

    def test_async_stop_timeout_leaves_release_and_config_untouched(self):
        self.asynchronous_stop_boundary("registration")
        started = time.monotonic()
        result = self.run_upgrade()
        self.assertNotEqual(0, result.returncode, "Old healthy listener caused false success")
        self.assertLess(time.monotonic() - started, 5)
        self.assertEqual(self.previous, self.current.resolve())
        self.assertEqual(self.original, self.config.read_bytes())
        self.assertIn("STOP_INCOMPLETE", result.stderr)
        self.assertFalse((self.root / "health-generation").exists())
        self.assertNotIn("bootstrap", self.actions.read_text())

    def test_async_stop_waits_for_trainer_even_after_registration_disappears(self):
        self.asynchronous_stop_boundary("trainer")
        result = self.run_upgrade()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("STOP_INCOMPLETE", result.stderr)
        self.assertEqual(self.previous, self.current.resolve())
        self.assertEqual(self.original, self.config.read_bytes())
        self.assertFalse((self.root / "early-switch").exists())

    def test_async_stop_does_not_kill_unfamiliar_listener_on_configured_port(self):
        self.original = self.original.replace(b"RISK_LLM_PORT=63201", b"RISK_LLM_PORT=64321")
        self.config.write_bytes(self.original)
        self.asynchronous_stop_boundary("port")
        result = self.run_upgrade()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("STOP_INCOMPLETE", result.stderr)
        self.assertEqual(self.previous, self.current.resolve())
        self.assertEqual(self.original, self.config.read_bytes())
        self.assertIn("-tiTCP:64321", (self.root / "port-checks").read_text())
        self.assertNotIn("kickstart", self.actions.read_text())


if __name__ == "__main__":
    unittest.main()
