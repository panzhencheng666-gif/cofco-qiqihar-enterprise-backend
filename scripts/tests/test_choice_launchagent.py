"""Generated launchd contract only; never registers or starts a job."""
import importlib
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1] / 'market_data'))
MODULE = Path(__file__).parents[1] / 'market_data/choice_launchagent.py'


class LaunchAgentTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(), 'disabled LaunchAgent generator missing')
        self.api = importlib.import_module('choice_launchagent')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.paths = dict(python='/private/Choice Space/venv/bin/python',
                          launcher='/private/Choice Space/choice_launcher.py',
                          config='/private/Choice Space/config&name.json',
                          sdk_file='/private/Choice Space/EmQuantAPI.py',
                          runtime_dir=str(self.root))

    def test_disabled_no_retry_and_private_runtime_contract(self):
        value = self.api.build_plist(**self.paths)
        self.assertTrue(value['Disabled'])
        self.assertFalse(value['KeepAlive'])
        self.assertFalse(value['RunAtLoad'])
        self.assertNotIn('StartInterval', value)
        self.assertNotIn('StartCalendarInterval', value)
        self.assertEqual(15, value['ExitTimeOut'])
        self.assertEqual(0o077, value['Umask'])
        self.assertEqual(str(self.root), value['WorkingDirectory'])
        self.assertEqual(str(self.root / 'stdout.log'), value['StandardOutPath'])
        self.assertEqual(str(self.root / 'stderr.log'), value['StandardErrorPath'])

    def test_literal_arguments_preserve_venv_and_spaces(self):
        value = plistlib.loads(plistlib.dumps(self.api.build_plist(**self.paths)))
        self.assertEqual([self.paths['python'], '-B', self.paths['launcher'], '--config',
                          self.paths['config'], '--run', '--lock-file', str(self.root / 'choice.lock'),
                          '--sdk-file', self.paths['sdk_file']], value['ProgramArguments'])
        self.assertNotIn('Program', value)
        self.assertEqual({'PYTHONDONTWRITEBYTECODE': '1', 'PYTHONUNBUFFERED': '1'},
                         value['EnvironmentVariables'])

    def test_relative_or_control_character_path_rejected(self):
        for value in ('relative/file', '/absolute/bad\nfile', '/absolute/\x00file'):
            with self.subTest(value=value):
                self.paths['config'] = value
                with self.assertRaisesRegex(ValueError, 'ABSOLUTE_PATH_REQUIRED'):
                    self.api.build_plist(**self.paths)

    def test_shared_runtime_and_unsafe_existing_log_rejected(self):
        self.root.chmod(0o755)
        with self.assertRaisesRegex(ValueError, 'PRIVATE_RUNTIME_REQUIRED'):
            self.api.build_plist(**self.paths)
        self.root.chmod(0o700)
        log = self.root / 'stdout.log'
        log.write_text('keep')
        log.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'PRIVATE_LOG_REQUIRED'):
            self.api.build_plist(**self.paths)
        log.chmod(0o600)
        self.api.build_plist(**self.paths)
        log.unlink()
        log.symlink_to(self.root / 'missing')
        with self.assertRaisesRegex(ValueError, 'PRIVATE_LOG_REQUIRED'):
            self.api.build_plist(**self.paths)

    def test_private_file_creation_and_no_overwrite(self):
        dest = self.root / 'service.plist'
        self.api.write_plist(dest, self.api.build_plist(**self.paths))
        before = dest.read_bytes()
        self.assertEqual(0o600, dest.stat().st_mode & 0o777)
        with self.assertRaises(FileExistsError):
            self.api.write_plist(dest, {})
        self.assertEqual(before, dest.read_bytes())
        result = subprocess.run(['/usr/bin/plutil', '-lint', str(dest)], capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_cli_generates_only_disabled_plist(self):
        dest = self.root / 'result.plist'
        args = [sys.executable, str(MODULE)]
        for name, value in self.paths.items():
            args.extend(['--' + name.replace('_', '-'), value])
        args.extend(['--output', str(dest)])
        result = subprocess.run(args, capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(plistlib.loads(dest.read_bytes())['Disabled'])
        self.assertEqual(['result.plist'], os.listdir(self.root))
        again = subprocess.run(args, capture_output=True, timeout=5)
        self.assertEqual(2, again.returncode)


if __name__ == '__main__':
    unittest.main()
