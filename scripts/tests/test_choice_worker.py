"""Bounded lifecycle tests; fake SDK session only, no credentials or login."""
import importlib.util
import json
from pathlib import Path
from threading import Event
import unittest

from test_choice_feed import QuotePublisher, FakeRecovery, NOW

spec = importlib.util.spec_from_file_location('choice_worker',
    Path(__file__).parents[1] / 'market_data/choice_worker.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
ChoiceWorker = module.ChoiceWorker


class Session:
    def __init__(self):
        self.calls = []
        self.state = 'NEW'
        self.cleanup_error = None
        self.on_start = lambda: None

    def start(self):
        self.calls.append('start')
        self.on_start()
        self.state = 'WAITING_DATA'

    def close(self):
        self.calls.append('close')
        self.state = 'CLOSED'

    def status(self):
        return {'state': self.state, 'cleanupError': self.cleanup_error}


class ChoiceWorkerTest(unittest.TestCase):
    def make(self, **kwargs):
        self.session = Session()
        self.recovery = FakeRecovery()
        self.publisher = QuotePublisher(self.recovery, authorized=True, clock=lambda: NOW)
        return ChoiceWorker(self.session, self.publisher, interval=0.05, **kwargs)

    def test_default_denies_start_without_touching_sdk_or_recovery(self):
        worker = self.make()
        worker.start()
        self.assertEqual('PENDING_AUTHORIZATION', worker.status()['state'])
        self.assertEqual([], self.session.calls)
        self.assertEqual(0, self.recovery.steps)
        self.assertEqual('PENDING_AUTHORIZATION', json.loads(self.publisher.read())['state'])
        self.assertTrue(worker.wait(0))

    def test_fixed_delay_steps_once_at_a_time_and_closes_once(self):
        worker = self.make(authorized=True)
        second = Event()
        self.recovery.action = lambda: second.set() if self.recovery.steps >= 2 else None
        worker.start()
        try:
            self.assertTrue(second.wait(1))
        finally:
            self.assertTrue(worker.stop(timeout=1))
        self.assertEqual(['start', 'close'], self.session.calls)
        self.assertGreaterEqual(worker.status()['cycles'], 2)
        self.assertEqual('CLOSED', worker.status()['state'])
        self.assertTrue(worker.stop(timeout=0))
        self.assertEqual(['start', 'close'], self.session.calls)
        with self.assertRaises(RuntimeError):
            worker.start()

    def test_blocked_step_reports_timeout_without_concurrent_sdk_cleanup(self):
        worker = self.make(authorized=True)
        entered, release = Event(), Event()
        self.recovery.action = lambda: (entered.set(), release.wait(3))
        worker.start()
        try:
            self.assertTrue(entered.wait(1))
            self.assertFalse(worker.stop(timeout=0.01))
            self.assertEqual('STOP_TIMEOUT', worker.status()['state'])
            self.assertEqual(['start'], self.session.calls)
            self.assertEqual(1, self.recovery.steps)
            self.assertEqual(0, worker.status()['cycles'])
            self.assertEqual('CLOSED', json.loads(self.publisher.read())['state'])
        finally:
            release.set()
            self.assertTrue(worker.wait(1))
        self.assertEqual(['start', 'close'], self.session.calls)
        self.assertEqual('CLOSED', worker.status()['state'])

    def test_stop_during_login_does_not_start_quote_loop(self):
        worker = self.make(authorized=True)
        entered, release = Event(), Event()
        self.session.on_start = lambda: (entered.set(), release.wait(3))
        worker.start()
        try:
            self.assertTrue(entered.wait(1))
            self.assertFalse(worker.stop(timeout=0.01))
        finally:
            release.set()
            self.assertTrue(worker.wait(1))
        self.assertEqual(0, self.recovery.steps)
        self.assertEqual(['start', 'close'], self.session.calls)

    def test_fatal_entitlement_stops_and_preserves_reason(self):
        worker = self.make(authorized=True)
        def fatal():
            self.session.state = 'ENTITLEMENT_ERROR'
            self.recovery.data = {'state': 'ENTITLEMENT_ERROR', 'quotes': []}
        self.recovery.action = fatal
        worker.start()
        self.assertTrue(worker.wait(1))
        self.assertEqual('ENTITLEMENT_ERROR', worker.status()['state'])
        self.assertEqual('ENTITLEMENT_ERROR', json.loads(self.publisher.read())['state'])
        self.assertEqual(['start', 'close'], self.session.calls)

    def test_publisher_failure_is_sanitized_and_session_is_released(self):
        worker = self.make(authorized=True)
        def fail():
            raise RuntimeError('private-login-details')
        self.recovery.action = fail
        worker.start()
        self.assertTrue(worker.wait(1))
        self.assertEqual('SOURCE_ERROR', worker.status()['state'])
        self.assertNotIn('private', str(worker.status()))
        self.assertEqual(['start', 'close'], self.session.calls)

    def test_login_exception_still_releases_session_without_starting_loop(self):
        worker = self.make(authorized=True)
        def fail():
            raise RuntimeError('private-login-details')
        self.session.on_start = fail
        worker.start()
        self.assertTrue(worker.wait(1))
        self.assertEqual('SOURCE_ERROR', worker.status()['state'])
        self.assertEqual(0, self.recovery.steps)
        self.assertEqual(['start', 'close'], self.session.calls)
        self.assertNotIn('private', str(worker.status()))

    def test_cleanup_error_is_visible_and_requires_new_process(self):
        worker = self.make(authorized=True)
        self.session.cleanup_error = 'STOP_FAILED'
        self.recovery.data = {'state': 'SOURCE_ERROR', 'quotes': []}
        worker.start()
        self.assertTrue(worker.wait(1))
        self.assertEqual('CLEANUP_ERROR', worker.status()['state'])
        self.assertEqual('SOURCE_ERROR', json.loads(self.publisher.read())['state'])
        with self.assertRaises(RuntimeError):
            worker.start()

    def test_invalid_interval_and_stop_before_start_are_safe(self):
        worker = self.make(authorized=True)
        for interval in [0, -1, True, float('nan'), float('inf'), 11]:
            with self.assertRaises(ValueError):
                ChoiceWorker(self.session, self.publisher, interval=interval)
        self.assertTrue(worker.stop(timeout=0))
        self.assertEqual([], self.session.calls)
        with self.assertRaises(RuntimeError):
            worker.start()


if __name__ == '__main__':
    unittest.main()
