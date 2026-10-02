import contextlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile


class LegacyCollectorDeploymentTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('retire', Path(__file__).with_name('disable-legacy-news-collectors.py'))
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module.JAR = self.root / 'app.jar'
        self.module.ROOT = self.root / 'release'

    def test_private_staging_permissions_do_not_make_runtime_jar_unreadable(self):
        self.module.JAR.write_bytes(b'old')
        self.module.JAR.chmod(0o644)
        source = self.root / 'candidate.jar'
        source.write_bytes(b'new')
        source.chmod(0o600)
        self.module.replace(source)
        self.assertEqual(self.module.JAR.read_bytes(), b'new')
        self.assertEqual(stat.S_IMODE(self.module.JAR.stat().st_mode), 0o644)

    def test_yaml_deployment_preserves_all_existing_entries(self):
        yaml = 'BOOT-INF/classes/application.yml'
        with zipfile.ZipFile(self.module.JAR, 'w') as jar:
            jar.writestr(yaml, b'other:\n  setting: unchanged\n')
            jar.writestr('BOOT-INF/classes/example.class', b'unchanged')
        self.module.BEFORE = self.module.sha(self.module.JAR)
        original_open = open

        def local_open(name, *args, **kwargs):
            return original_open(self.root / 'lock' if name == '/run/lock/cofco-market-news-release.lock' else name, *args, **kwargs)

        with patch.object(self.module, 'healthy', return_value=True), \
                patch.object(self.module, 'call', return_value=json.dumps([{'Config': {'Env': [], 'Cmd': []}}]).encode()), \
                patch.object(self.module, 'open', local_open, create=True), \
                patch('sys.argv', ['retire', 'prepare']), contextlib.redirect_stdout(io.StringIO()):
            self.module.main()
        with zipfile.ZipFile(self.module.ROOT / 'after.jar') as jar:
            self.assertEqual(jar.read(yaml), b'other:\n  setting: unchanged\n')
            self.assertEqual(jar.read('BOOT-INF/classes/example.class'), b'unchanged')
            for feed in self.module.FEEDS:
                self.assertIn(('qiqihar.market-intelligence.' + feed + '.enabled=false').encode(), jar.read(self.module.RESOURCE))
        self.assertEqual(self.module.sha(self.module.JAR), self.module.BEFORE)


if __name__ == '__main__':
    unittest.main()
