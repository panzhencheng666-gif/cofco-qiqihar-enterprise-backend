"""Process ownership tests, synthetic session and loopback HTTP only."""
import importlib.util
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import tempfile
from threading import Event
import unittest

sys.path.insert(0, str(Path(__file__).parents[1] / 'market_data'))
from choice_worker import ChoiceWorker
from choice_feed import QuotePublisher, LocalQuoteServer
from test_choice_feed import FakeRecovery, NOW
from test_choice_worker import Session

MODULE = Path(__file__).parents[1] / 'market_data/choice_process.py'


class ProcessTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(), 'process ownership implementation missing')
        spec = importlib.util.spec_from_file_location('choice_process', MODULE)
        self.api = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.api)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.lock = Path(self.temp.name) / 'collector.lock'

    def test_denied_start_creates_nothing(self):
        result = self.api.run_service(lambda: self.fail('factory called'), self.lock)
        self.assertEqual('PENDING_AUTHORIZATION', result['state'])
        self.assertEqual([], list(self.lock.parent.iterdir()))

    def test_lock_conflict_persists_file_and_releases_after_close(self):
        first = self.api.InstanceLock(self.lock)
        first.acquire()
        try:
            with self.assertRaisesRegex(RuntimeError, 'ALREADY_RUNNING'):
                self.api.InstanceLock(self.lock).acquire()
        finally:
            first.close()
        self.assertTrue(self.lock.exists())
        second = self.api.InstanceLock(self.lock)
        second.acquire()
        second.close()

    def test_rejects_insecure_directory_and_symlink_without_touching_target(self):
        self.lock.parent.chmod(0o755)
        with self.assertRaisesRegex(RuntimeError, 'LOCK_FILE_INVALID'):
            self.api.InstanceLock(self.lock).acquire()
        self.lock.parent.chmod(0o700)
        target = self.lock.parent / 'target'
        target.write_text('keep')
        self.lock.symlink_to(target)
        with self.assertRaisesRegex(RuntimeError, 'LOCK_FILE_INVALID'):
            self.api.InstanceLock(self.lock).acquire()
        self.assertEqual('keep', target.read_text())

    def components(self, *, block=None, cleanup_error=None):
        self.session = Session()
        self.session.cleanup_error = cleanup_error
        self.recovery = FakeRecovery()
        if block:
            self.recovery.action = block
        self.publisher = QuotePublisher(self.recovery, authorized=True, clock=lambda: NOW)
        self.worker = ChoiceWorker(self.session, self.publisher, authorized=True, interval=.05)
        self.server = LocalQuoteServer(self.publisher, 'synthetic-token-' + 'x' * 40)
        return self.worker, self.server

    def test_stop_request_closes_worker_server_and_restores_handlers(self):
        stop = Event()
        old = signal.getsignal(signal.SIGTERM)
        def build():
            components = self.components(block=stop.set)
            return components
        result = self.api.run_service(build, self.lock, authorized=True, stop_event=stop)
        self.assertEqual({'exitCode': 0, 'state': 'CLOSED'}, result)
        self.assertEqual(['start', 'close'], self.session.calls)
        self.assertEqual(old, signal.getsignal(signal.SIGTERM))
        self.assertEqual(-1, self.server._server.fileno())
        guard = self.api.InstanceLock(self.lock)
        guard.acquire()
        guard.close()

    def test_blocked_sdk_retains_lock_and_hides_publication(self):
        stop, release = Event(), Event()
        def build():
            return self.components(block=lambda: (stop.set(), release.wait(3)))
        try:
            result = self.api.run_service(build, self.lock, authorized=True,
                                          stop_event=stop, stop_timeout=.01)
            self.assertEqual('STOP_TIMEOUT', result['state'])
            self.assertEqual('CLOSED', json.loads(self.publisher.read())['state'])
            self.assertEqual(-1, self.server._server.fileno())
            with self.assertRaisesRegex(RuntimeError, 'ALREADY_RUNNING'):
                self.api.InstanceLock(self.lock).acquire()
        finally:
            release.set()
            self.assertTrue(self.worker.wait(1))
            # Test teardown only. Production retains this lock until process exit.
            for guard in self.api._quarantined_locks:
                guard.close()

    def test_cleanup_error_retains_lock(self):
        stop = Event()
        result = self.api.run_service(
            lambda: self.components(block=stop.set, cleanup_error='private-detail'),
            self.lock, authorized=True, stop_event=stop)
        try:
            self.assertEqual('CLEANUP_ERROR', result['state'])
            self.assertNotIn('private-detail', json.dumps(result))
            with self.assertRaisesRegex(RuntimeError, 'ALREADY_RUNNING'):
                self.api.InstanceLock(self.lock).acquire()
        finally:
            for guard in self.api._quarantined_locks:
                guard.close()

    def test_factory_failure_sanitized_and_lock_quarantined(self):
        def fail():
            raise RuntimeError('supplier-password-detail')
        result = self.api.run_service(fail, self.lock, authorized=True)
        try:
            self.assertEqual({'exitCode': 5, 'state': 'START_FAILED_EXIT_REQUIRED'}, result)
            with self.assertRaisesRegex(RuntimeError, 'ALREADY_RUNNING'):
                self.api.InstanceLock(self.lock).acquire()
        finally:
            for guard in self.api._quarantined_locks:
                guard.close()

    def test_already_requested_stop_skips_factory(self):
        stop = Event()
        stop.set()
        result = self.api.run_service(lambda: self.fail('factory called'), self.lock,
                                      authorized=True, stop_event=stop)
        self.assertEqual({'exitCode': 0, 'state': 'CLOSED'}, result)

    def test_fatal_entitlement_exits_without_retry(self):
        def build():
            components = self.components()
            self.session.on_start = lambda: None
            self.recovery.data = {'state': 'ENTITLEMENT_ERROR', 'quotes': []}
            return components
        result = self.api.run_service(build, self.lock, authorized=True)
        self.assertEqual({'exitCode': 3, 'state': 'ENTITLEMENT_ERROR'}, result)
        self.assertEqual(['start', 'close'], self.session.calls)

    def test_two_processes_signal_exit_and_restart(self):
        fixture = Path(__file__).with_name('choice_process_fixture.py')
        def launch():
            return subprocess.Popen([sys.executable, str(fixture), str(self.lock)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        child = launch()
        try:
            self.assertTrue(select.select([child.stdout], [], [], 5)[0])
            self.assertEqual('STARTED', child.stdout.readline().strip())
            second = subprocess.run([sys.executable, str(fixture), str(self.lock)],
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(4, second.returncode, second.stderr)
            self.assertEqual('ALREADY_RUNNING', json.loads(second.stdout)['state'])
            child.send_signal(signal.SIGTERM)
            out, err = child.communicate(timeout=5)
            self.assertEqual(0, child.returncode, err)
            self.assertEqual('CLOSED', json.loads(out)['state'])
        finally:
            if child.poll() is None:
                child.kill()
                child.communicate(timeout=5)
        third = launch()
        try:
            self.assertTrue(select.select([third.stdout], [], [], 5)[0])
            self.assertEqual('STARTED', third.stdout.readline().strip())
            third.send_signal(signal.SIGINT)
            out, err = third.communicate(timeout=5)
            self.assertEqual(0, third.returncode, err)
            self.assertEqual('CLOSED', json.loads(out)['state'])
        finally:
            if third.poll() is None:
                third.kill()
                third.communicate(timeout=5)


if __name__ == '__main__':
    unittest.main()
