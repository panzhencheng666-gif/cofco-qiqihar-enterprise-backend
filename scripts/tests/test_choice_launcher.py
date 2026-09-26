"""Launcher composition tests; only synthetic SDK objects/files are loaded."""
import importlib
import json
from datetime import datetime, timezone
from pathlib import Path
import socket
import signal
import subprocess
import sys
import tempfile
from threading import Event, Thread
from time import monotonic
from types import SimpleNamespace
import unittest
from http.client import HTTPConnection

sys.path.insert(0, str(Path(__file__).parents[1] / 'market_data'))
from choice_process import InstanceLock
from test_choice_subscription import FakeSdk

MODULE = Path(__file__).parents[1] / 'market_data/choice_launcher.py'


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(), 'launcher implementation missing')
        self.api = importlib.import_module('choice_launcher')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            self.port = sock.getsockname()[1]
        self.token = 'synthetic-local-token-' + 'x' * 40
        (self.root / 'bearer.txt').write_text(self.token)
        (self.root / 'bearer.txt').chmod(0o600)
        self.write('catalogue.json', {'test-corn': '元/吨'})
        self.write('bindings.json', [{'code': 'TEST.CORN', 'id': 'test-corn',
                                    'unit': '元/吨', 'verified': True}])
        self.config = dict(schemaVersion=1, distributionAuthorized=True, host='127.0.0.1',
                           port=self.port, intervalSeconds=.05, catalogueFile='catalogue.json',
                           bindingsFile='bindings.json', bearerTokenFile='bearer.txt')
        self.write('config.json', self.config)
        self.path = self.root / 'config.json'
        self.lock = self.root / 'collector.lock'

    def write(self, path, value):
        (self.root / path).write_text(json.dumps(value))

    def test_check_only_never_loads_sdk_or_creates_lock(self):
        result = self.api.launch(self.path, self.lock, lambda: self.fail('SDK loaded'))
        self.assertEqual('CONFIG_VALID_VENDOR_CHECK_REQUIRED', result['state'])
        self.assertFalse(result['readyForLive'])
        self.assertFalse(self.lock.exists())

    def test_invalid_config_never_loads_sdk(self):
        self.config['distributionAuthorized'] = False
        self.write('config.json', self.config)
        result = self.api.launch(self.path, self.lock, lambda: self.fail('SDK loaded'), run=True)
        self.assertEqual(2, result['exitCode'])
        self.assertIn('AUTHORIZATION_DECLARATION_REQUIRED', result['errors'])
        self.assertFalse(self.lock.exists())

    def test_competing_instance_does_not_import_sdk(self):
        guard = InstanceLock(self.lock)
        guard.acquire()
        try:
            result = self.api.launch(self.path, self.lock, lambda: self.fail('SDK loaded'), run=True)
            self.assertEqual('ALREADY_RUNNING', result['state'])
        finally:
            guard.close()

    def test_sdk_file_loader_requires_explicit_regular_file_and_c_api(self):
        sdk_file = self.root / 'EmQuantAPI.py'
        sdk_file.write_text('c = object()\n')
        with self.assertRaisesRegex(RuntimeError, 'SDK_LOAD_FAILED'):
            self.api.load_sdk(sdk_file)
        sdk_file.write_text('class c:\n' + ''.join(
            '    def ' + method + '(*args, **kwargs): pass\n'
            for method in ['start', 'stop', 'csq', 'csqcancel', 'csqsnapshot']))
        self.add_native_fixture(sdk_file)
        self.assertTrue(callable(self.api.load_sdk(sdk_file).start))
        link = self.root / 'linked.py'
        link.symlink_to(sdk_file)
        with self.assertRaisesRegex(RuntimeError, 'SDK_LOAD_FAILED'):
            self.api.load_sdk(link)

    def add_native_fixture(self, sdk_file):
        native = self.root / 'libs/mac/libEMQuantAPIx64.dylib'
        native.parent.mkdir(parents=True, exist_ok=True)
        native.write_bytes(b'INERT TEST LIBRARY - NEVER LOADED')
        with sdk_file.open('a') as stream:
            stream.write('\nclass UtilAccess:\n    @staticmethod\n    def GetLibraryPath():\n        return '
                         + repr(str(native)) + '\n')

    def test_loader_failure_sanitized_and_requires_process_exit(self):
        def fail():
            raise RuntimeError('private-secret-error')
        result = self.api.launch(self.path, self.lock, fail, run=True)
        try:
            self.assertEqual('START_FAILED_EXIT_REQUIRED', result['state'])
            self.assertNotIn('private-secret', json.dumps(result))
        finally:
            # Isolated test only: production must exit, not close quarantine locks.
            import choice_process
            for guard in choice_process._quarantined_locks:
                guard.close()

    def test_real_assembly_serves_snapshot_and_uses_checked_config_once(self):
        stop = Event()
        sdk = FakeSdk()
        sdk.csqsnapshot = lambda *args: SimpleNamespace(
            ErrorCode=0, Codes=['TEST.CORN'], Indicators=['TIME', 'NOW'], Dates=[],
            Data={'TEST.CORN': [datetime.now(timezone.utc).isoformat(), 2000]})
        def load():
            # No subsequent read may replace the validated token/mapping.
            (self.root / 'bearer.txt').write_text('changed-invalid-token')
            self.write('bindings.json', [])
            return sdk
        observed, failures = [], []
        def read_feed():
            until = monotonic() + 3
            while monotonic() < until:
                try:
                    connection = HTTPConnection('127.0.0.1', self.port, timeout=.2)
                    try:
                        connection.request('GET', '/quotes',
                                           headers={'Authorization': 'Bearer ' + self.token})
                        response = connection.getresponse()
                        payload = json.loads(response.read())
                        if response.status != 200:
                            failures.append('HTTP ' + str(response.status))
                            break
                    finally:
                        connection.close()
                    if payload['state'] == 'RECONCILED':
                        observed.append(payload)
                        break
                except OSError:
                    pass
                stop.wait(.03)
            if not observed:
                failures.append('no reconciled snapshot')
            stop.set()
        reader = Thread(target=read_feed)
        reader.start()
        try:
            result = self.api.launch(self.path, self.lock, load, run=True, stop_event=stop)
        finally:
            stop.set()
            reader.join(4)
        self.assertFalse(reader.is_alive())
        self.assertEqual([], failures)
        self.assertEqual({'exitCode': 0, 'state': 'CLOSED'}, result)
        self.assertEqual(2000, observed[0]['quotes'][0]['last'])
        self.assertEqual('test-corn', observed[0]['quotes'][0]['id'])
        self.assertEqual(1, sum(call[0] == 'start' for call in sdk.calls))
        self.assertEqual('stop', sdk.calls[-1][0])
        self.assertNotIn(self.token, json.dumps(result))

    def test_cli_run_with_synthetic_sdk_file_and_sigterm(self):
        sdk_file = self.root / 'EmQuantAPI.py'
        sdk_file.write_text("""# Synthetic CLI fixture. Not a vendor SDK or real market quote.
from types import SimpleNamespace as R
from datetime import datetime, timezone
class c:
    @staticmethod
    def start(*args, **kwargs): return R(ErrorCode=0)
    @staticmethod
    def csq(*args, **kwargs): return R(ErrorCode=0, SerialID=1)
    @staticmethod
    def csqcancel(*args, **kwargs): return R(ErrorCode=0)
    @staticmethod
    def stop(*args, **kwargs): return R(ErrorCode=0)
    @staticmethod
    def csqsnapshot(*args, **kwargs):
        return R(ErrorCode=0, Codes=['TEST.CORN'], Indicators=['TIME','NOW'],
                 Dates=[], Data={'TEST.CORN':[datetime.now(timezone.utc).isoformat(),2000]})
""")
        self.add_native_fixture(sdk_file)
        child = subprocess.Popen([sys.executable, str(MODULE), '--config', str(self.path),
                                  '--run', '--lock-file', str(self.lock), '--sdk-file', str(sdk_file)],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        payload = None
        try:
            until = monotonic() + 5
            while monotonic() < until and child.poll() is None:
                connection = HTTPConnection('127.0.0.1', self.port, timeout=.2)
                try:
                    connection.request('GET', '/quotes',
                                       headers={'Authorization': 'Bearer ' + self.token})
                    response = connection.getresponse()
                    value = json.loads(response.read())
                    if value['state'] == 'RECONCILED':
                        payload = value
                        break
                except OSError:
                    pass
                finally:
                    connection.close()
                Event().wait(.03)
            self.assertIsNotNone(payload)
            child.send_signal(signal.SIGTERM)
            out, err = child.communicate(timeout=5)
            self.assertEqual(0, child.returncode, err)
            self.assertEqual({'exitCode': 0, 'state': 'CLOSED'}, json.loads(out))
            self.assertEqual('', err)
            guard = InstanceLock(self.lock)
            guard.acquire()
            guard.close()
        finally:
            if child.poll() is None:
                child.kill()
                child.communicate(timeout=5)

    def test_cli_defaults_to_check_and_requires_run_arguments(self):
        result = subprocess.run([sys.executable, str(MODULE), '--config', str(self.path)],
                                capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(json.loads(result.stdout)['readyForLive'])
        result = subprocess.run([sys.executable, str(MODULE), '--config', str(self.path), '--run'],
                                capture_output=True, timeout=5)
        self.assertEqual(2, result.returncode)
        self.assertEqual('RUN_ARGUMENTS_REQUIRED', json.loads(result.stdout)['state'])


if __name__ == '__main__':
    unittest.main()
