import importlib.util
from pathlib import Path
from types import SimpleNamespace as Result
import unittest
from threading import Thread

MODULE = Path(__file__).parents[1] / 'market_data/choice_subscription.py'
spec = importlib.util.spec_from_file_location('choice_subscription', MODULE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
ChoiceSubscription = module.ChoiceSubscription


class FakeSdk:
    """Offline implementation of the signatures in vendor SDK 2.7.7.0."""
    def __init__(self):
        self.calls = []
        self.login_error = 0
        self.subscribe_error = 0
        self.on_subscribe = None
        self.cancel_raises = False

    def start(self, options='', logcallback=None, mainCallBack=None):
        self.calls.append(('start', options))
        self.main = mainCallBack
        self.log = logcallback
        return Result(ErrorCode=self.login_error)

    def csq(self, codes, indicators, options='', fncallback=None):
        self.calls.append(('csq', codes, indicators, options))
        self.tick = fncallback
        if self.on_subscribe:
            self.on_subscribe()
        return Result(ErrorCode=self.subscribe_error, SerialID=41)

    def csqcancel(self, serialID):
        self.calls.append(('cancel', serialID))
        if self.cancel_raises:
            raise RuntimeError('secret must not escape')
        return Result(ErrorCode=0)

    def stop(self):
        self.calls.append(('stop',))
        return Result(ErrorCode=0)


def tick(price=100):
    # Synthetic fixture. No supplier code or quote is claimed valid.
    return Result(ErrorCode=0, Codes=['TEST.CODE'], Indicators=['TIME', 'NOW'],
                  Dates=['2026-09-25'], Data={'TEST.CODE': ['10:00:00', price]})


class ChoiceSubscriptionTest(unittest.TestCase):
    def make(self, authorized=True, **kwargs):
        self.sdk = FakeSdk()
        return ChoiceSubscription(self.sdk, ['TEST.CODE'], authorized=authorized, **kwargs)

    def test_unapproved_subscription_never_calls_sdk(self):
        session = self.make(False)
        session.start()
        self.assertEqual('PENDING_AUTHORIZATION', session.status()['state'])
        self.assertEqual([], self.sdk.calls)

    def test_subscribes_once_without_forcing_other_sessions_offline(self):
        session = self.make()
        session.start()
        session.start()
        self.assertEqual(2, len(self.sdk.calls))
        self.assertIn('ForceLogin=0', self.sdk.calls[0][1])
        self.assertEqual(('csq', 'TEST.CODE', 'TIME,NOW', 'Pushtype=2'), self.sdk.calls[1])
        self.assertEqual('WAITING_DATA', session.status()['state'])
        self.assertEqual(1, self.sdk.log(b'private SDK log'))

    def test_frames_are_copied_and_never_promoted_to_verified_quotes(self):
        session = self.make()
        session.start()
        frame = tick()
        self.sdk.tick(frame)
        frame.Data['TEST.CODE'][1] = 999
        received = session.take()
        self.assertEqual(100, received['Data']['TEST.CODE'][1])
        self.assertNotIn('sourceAt', received)
        self.assertEqual('RECEIVING', session.status()['state'])

    def test_reconnect_is_sdk_owned_and_fatal_permissions_drop_buffer(self):
        session = self.make()
        session.start()
        self.sdk.main(Result(ErrorCode=10002012))
        self.assertEqual('RECONNECTING', session.status()['state'])
        self.sdk.tick(tick())
        self.assertEqual('RECEIVING', session.status()['state'])
        self.sdk.main(Result(ErrorCode=10001022, ErrorMsg='private detail'))
        self.sdk.tick(tick(200))
        self.assertEqual('ENTITLEMENT_ERROR', session.status()['state'])
        self.assertIsNone(session.take())
        self.assertEqual(2, len(self.sdk.calls))
        self.assertNotIn('private detail', str(session.status()))

    def test_fatal_event_during_subscribe_is_not_overwritten(self):
        session = self.make()
        self.sdk.on_subscribe = lambda: self.sdk.main(Result(ErrorCode=10001021))
        session.start()
        self.assertEqual('ENTITLEMENT_ERROR', session.status()['state'])
        self.assertEqual(('stop',), self.sdk.calls[-1])

    def test_close_always_logs_out_and_ignores_late_callbacks(self):
        session = self.make()
        session.start()
        self.sdk.cancel_raises = True
        session.close()
        self.sdk.tick(tick())
        session.close()
        self.assertEqual(('stop',), self.sdk.calls[-1])
        self.assertEqual(1, self.sdk.calls.count(('stop',)))
        self.assertIsNone(session.take())
        self.assertEqual('CLOSED', session.status()['state'])
        self.assertEqual('CANCEL_FAILED', session.status()['cleanupError'])

    def test_subscription_failure_releases_login(self):
        session = self.make()
        self.sdk.subscribe_error = 10001021
        session.start()
        self.assertEqual('ENTITLEMENT_ERROR', session.status()['state'])
        self.assertEqual(('stop',), self.sdk.calls[-1])

    def test_buffer_is_bounded_and_reports_loss(self):
        session = self.make(capacity=2)
        session.start()
        for price in [1, 2, 3]:
            self.sdk.tick(tick(price))
        self.assertEqual(1, session.status()['droppedFrames'])
        self.assertEqual(2, session.take()['Data']['TEST.CODE'][1])
        self.assertEqual(3, session.take()['Data']['TEST.CODE'][1])
        self.assertIsNone(session.take())

    def test_login_failure_does_not_subscribe(self):
        session = self.make()
        self.sdk.login_error = 10001009
        session.start()
        self.assertEqual('SESSION_LOST', session.status()['state'])
        self.assertFalse(any(call[0] == 'csq' for call in self.sdk.calls))

    def test_failed_subscription_cannot_reconnect_without_a_subscription(self):
        session = self.make()
        self.sdk.subscribe_error = 10002012
        session.start()
        self.assertEqual('SOURCE_ERROR', session.status()['state'])
        self.assertEqual(10002012, session.status()['errorCode'])
        self.assertEqual(('stop',), self.sdk.calls[-1])

    def test_sdk_thread_can_callback_before_subscribe_returns(self):
        session = self.make()
        def callback():
            thread = Thread(target=lambda: self.sdk.tick(tick()), daemon=True)
            thread.start()
            thread.join(timeout=1)
            self.assertFalse(thread.is_alive(), 'SDK callback deadlock')
        self.sdk.on_subscribe = callback
        session.start()
        self.assertEqual('RECEIVING', session.status()['state'])
        self.assertIsNotNone(session.take())
        session.close()

    def test_subscription_exception_is_sanitized_and_login_released(self):
        session = self.make()
        def fail():
            raise RuntimeError('private token and URL')
        self.sdk.on_subscribe = fail
        session.start()
        self.assertEqual('SOURCE_ERROR', session.status()['state'])
        self.assertNotIn('private', str(session.status()))
        self.assertEqual(('stop',), self.sdk.calls[-1])

    def test_unknown_error_and_malformed_frame_stop_delivery(self):
        session = self.make()
        session.start()
        self.sdk.main(Result(ErrorCode=99999))
        self.sdk.tick(tick())
        self.assertEqual('SOURCE_ERROR', session.status()['state'])
        self.assertIsNone(session.take())
        session.close()
        session = self.make()
        session.start()
        self.sdk.tick(Result(ErrorCode=0))
        self.assertEqual('SOURCE_ERROR', session.status()['state'])
        session.close()

    def test_invalid_codes_and_empty_fields_fail_before_login(self):
        for codes in [[], ['A,B'], ['A', 'A']]:
            with self.assertRaises(ValueError):
                ChoiceSubscription(FakeSdk(), codes, authorized=True)
        with self.assertRaises(ValueError):
            ChoiceSubscription(FakeSdk(), ['A'], authorized=True, indicators=[])


if __name__ == '__main__':
    unittest.main()
