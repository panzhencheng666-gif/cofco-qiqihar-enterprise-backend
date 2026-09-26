"""SDK discovery checks using inert Python fixtures and non-loadable dummy files."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / 'market_data'))
from choice_launcher import load_sdk, launch


class SdkPathTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.source = self.root / 'EmQuantAPI.py'
        self.native = self.root / 'libs/mac/libEMQuantAPIx64.dylib'
        self.native.parent.mkdir(parents=True)
        self.native.write_bytes(b'INERT TEST FILE - NOT A NATIVE LIBRARY')
        self.marker = self.root / 'login-called'

    def sdk(self, selected, *, resolver=True):
        code = 'from pathlib import Path\nclass c:\n'
        for name in ['start', 'stop', 'csq', 'csqcancel', 'csqsnapshot']:
            code += ('    @staticmethod\n    def ' + name + '(*args, **kwargs):\n'
                     '        Path(' + repr(str(self.marker)) + ').touch()\n')
        if resolver:
            code += 'class UtilAccess:\n    @staticmethod\n    def GetLibraryPath():\n        return ' + repr(selected) + '\n'
        self.source.write_text(code)

    def test_matching_bundle_accepted_without_native_or_login_call(self):
        self.sdk(str(self.native))
        self.assertTrue(callable(load_sdk(self.source).start))
        self.assertFalse(self.marker.exists())

    def test_empty_missing_or_relative_discovery_rejected(self):
        for selected, resolver in [('', True), (None, True), ('libs/mac/libEMQuantAPIx64.dylib', True), ('', False)]:
            with self.subTest(selected=selected, resolver=resolver):
                self.sdk(selected, resolver=resolver)
                with self.assertRaisesRegex(RuntimeError, 'SDK_NATIVE_PATH_UNAVAILABLE'):
                    load_sdk(self.source)
                self.assertFalse(self.marker.exists())

    def test_other_installation_rejected(self):
        other = self.root / 'other-lib.dylib'
        other.write_bytes(b'OTHER')
        self.sdk(str(other))
        with self.assertRaisesRegex(RuntimeError, 'SDK_NATIVE_PATH_MISMATCH'):
            load_sdk(self.source)
        self.assertFalse(self.marker.exists())

    def test_missing_symlink_or_directory_library_rejected(self):
        self.sdk(str(self.native))
        self.native.unlink()
        with self.assertRaisesRegex(RuntimeError, 'SDK_NATIVE_LIBRARY_INVALID'):
            load_sdk(self.source)
        other = self.root / 'other'
        other.write_bytes(b'OTHER')
        self.native.symlink_to(other)
        with self.assertRaisesRegex(RuntimeError, 'SDK_NATIVE_LIBRARY_INVALID'):
            load_sdk(self.source)
        self.native.unlink()
        self.native.mkdir()
        with self.assertRaisesRegex(RuntimeError, 'SDK_NATIVE_LIBRARY_INVALID'):
            load_sdk(self.source)

    def test_unverified_platform_refused_before_python_module_execution(self):
        self.source.write_text('from pathlib import Path\nPath(' + repr(str(self.marker)) + ').touch()\n')
        with patch('platform.system', return_value='Linux'):
            with self.assertRaisesRegex(RuntimeError, 'SDK_PLATFORM_NOT_VERIFIED'):
                load_sdk(self.source)
        self.assertFalse(self.marker.exists())

    def test_launch_surfaces_safe_reason_and_does_not_login(self):
        self.sdk('/private-other-installation/lib.dylib')
        (self.root / 'token').write_text('synthetic-token-' + 'x' * 40)
        (self.root / 'token').chmod(0o600)
        files = {'catalogue.json': {'test': 'unit'},
                 'bindings.json': [{'code': 'TEST.CODE', 'id': 'test', 'unit': 'unit', 'verified': True}],
                 'config.json': dict(schemaVersion=1, distributionAuthorized=True, host='127.0.0.1',
                    port=19091, intervalSeconds=1, catalogueFile='catalogue.json',
                    bindingsFile='bindings.json', bearerTokenFile='token')}
        for name, value in files.items():
            (self.root / name).write_text(json.dumps(value))
        result = launch(self.root / 'config.json', self.root / 'service.lock',
                        lambda: load_sdk(self.source), run=True)
        try:
            self.assertEqual({'exitCode': 5, 'state': 'SDK_NATIVE_PATH_MISMATCH'}, result)
            self.assertFalse(self.marker.exists())
            self.assertNotIn('private-other-installation', json.dumps(result))
        finally:
            import choice_process
            for guard in choice_process._quarantined_locks:
                guard.close()


if __name__ == '__main__':
    unittest.main()
