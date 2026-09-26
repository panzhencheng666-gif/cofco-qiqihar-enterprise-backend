"""Offline fault tests: real transport + normalizer, synthetic SDK only."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace as Result
from datetime import datetime, timezone
import unittest

from test_choice_subscription import ChoiceSubscription, FakeSdk
from test_choice_quotes import QuoteNormalizer, CATALOGUE, BINDINGS, frame, NOW

spec = importlib.util.spec_from_file_location('choice_recovery',
    Path(__file__).parents[1] / 'market_data/choice_recovery.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
ChoiceRecovery = module.ChoiceRecovery


class SnapshotSdk(FakeSdk):
    def __init__(self):
        super().__init__()
        self.payload = frame({'TEST.CORN': ['2026-09-25T02:00:00Z', 2000, 1990],
                              'TEST.SOY': ['2026-09-25T02:00:00Z', 4000, 3900]})
        self.on_snapshot = None
        self.snapshot_error = 0

    def csqsnapshot(self, codes, indicators, options=''):
        self.calls.append(('snapshot', codes, indicators, options))
        if self.on_snapshot:
            self.on_snapshot()
        return Result(ErrorCode=self.snapshot_error, **self.payload)


class ChoiceRecoveryTest(unittest.TestCase):
    def make(self, authorized=True, capacity=2):
        self.sdk = SnapshotSdk()
        self.session = ChoiceSubscription(self.sdk, ['TEST.CORN', 'TEST.SOY'],
            authorized=authorized, capacity=capacity, indicators=['TIME', 'NOW', 'PRECLOSE'])
        self.time = 0
        self.now = NOW
        self.session.start()
        return ChoiceRecovery(self.session,
            lambda: QuoteNormalizer(CATALOGUE, BINDINGS, clock=lambda: self.now),
            clock=lambda: self.time)

    def snapshots(self):
        return sum(call[0] == 'snapshot' for call in self.sdk.calls)

    def push(self, price=2100):
        self.sdk.tick(Result(ErrorCode=0, **frame(
            {'TEST.CORN': ['2026-09-25T02:00:01Z', price, 1990]})))

    def test_initial_complete_snapshot_enables_reconciled_view(self):
        recovery = self.make()
        recovery.step()
        view = recovery.view()
        self.assertEqual('RECONCILED', view['state'])
        self.assertEqual(2, len(view['quotes']))
        self.assertEqual(1, self.snapshots())
        recovery.step()
        self.assertEqual(1, self.snapshots())
        self.assertEqual(('snapshot', 'TEST.CORN,TEST.SOY', 'TIME,NOW,PRECLOSE', ''), self.sdk.calls[-1])

    def test_no_snapshot_when_unapproved_or_closed(self):
        recovery = self.make(False)
        recovery.step()
        self.assertEqual('PENDING_AUTHORIZATION', recovery.view()['state'])
        self.session.close()
        recovery.step()
        self.assertEqual(0, self.snapshots())
        self.assertEqual([], recovery.view()['quotes'])

    def test_disconnect_not_lost_when_tick_arrives_before_worker_observes_it(self):
        recovery = self.make()
        recovery.step()
        self.sdk.main(Result(ErrorCode=10002012))
        self.push()
        self.assertEqual('RECOVERY_REQUIRED', recovery.view()['state'])
        self.time = 5
        recovery.step()
        self.assertEqual('RECONCILED', recovery.view()['state'])
        self.assertEqual(2, self.snapshots())
        self.assertEqual(2100, recovery.view()['quotes'][0]['last'])

    def test_overflow_requests_snapshot_and_keeps_newer_ticks(self):
        recovery = self.make(capacity=1)
        recovery.step()
        self.push()
        self.push()
        self.time = 5
        recovery.step()
        self.assertEqual(2, self.snapshots())
        self.assertEqual('RECONCILED', recovery.view()['state'])
        self.assertEqual(2100, recovery.view()['quotes'][0]['last'])

    def test_partial_or_stale_snapshot_does_not_clear_recovery_and_retries_are_bounded(self):
        recovery = self.make()
        self.sdk.payload = frame()
        recovery.step()
        self.assertEqual('RECOVERY_REQUIRED', recovery.view()['state'])
        recovery.step()
        self.assertEqual(1, self.snapshots())
        self.time = 5
        self.sdk.payload['Data']['TEST.CORN'][0] = '2026-09-24T02:00:00Z'
        recovery.step()
        self.assertEqual(2, self.snapshots())
        self.assertEqual('RECOVERY_REQUIRED', recovery.view()['state'])
        self.assertEqual([], recovery.view()['quotes'])

    def test_fault_during_snapshot_never_promotes_recovery(self):
        for code in [10002012, 10001021]:
            recovery = self.make()
            self.sdk.on_snapshot = lambda: self.sdk.main(Result(ErrorCode=code))
            recovery.step()
            self.assertNotEqual('RECONCILED', recovery.view()['state'])
            self.assertEqual([], recovery.view()['quotes'])

    def test_overflow_during_snapshot_discards_snapshot_then_recovers(self):
        recovery = self.make(capacity=1)
        self.sdk.on_snapshot = lambda: (self.push(), self.push())
        recovery.step()
        self.assertEqual('SNAPSHOT_SUPERSEDED', recovery.view()['recoveryError'])
        self.assertEqual('RECOVERY_REQUIRED', recovery.view()['state'])
        self.sdk.on_snapshot = None
        self.time = 5
        recovery.step()
        self.assertEqual('RECONCILED', recovery.view()['state'])
        self.assertEqual(2100, recovery.view()['quotes'][0]['last'])

    def test_full_snapshot_with_stale_row_is_rejected_until_new_snapshot(self):
        recovery = self.make()
        self.sdk.payload['Data']['TEST.SOY'][0] = '2026-09-25T01:58:00Z'
        recovery.step()
        self.assertEqual('SNAPSHOT_INCOMPLETE_OR_INVALID', recovery.view()['recoveryError'])
        self.assertEqual([], recovery.view()['quotes'])
        self.time = 5
        self.sdk.payload['Data']['TEST.SOY'][0] = '2026-09-25T02:00:00Z'
        recovery.step()
        self.assertEqual('RECONCILED', recovery.view()['state'])

    def test_snapshot_entitlement_error_stops_further_snapshot_requests(self):
        recovery = self.make()
        self.sdk.snapshot_error = 10001021
        recovery.step()
        self.assertEqual('ENTITLEMENT_ERROR', recovery.view()['state'])
        self.time = 30
        recovery.step()
        self.assertEqual(1, self.snapshots())

    def test_snapshot_exception_is_sanitized_and_does_not_relogin(self):
        recovery = self.make()
        def fail():
            raise RuntimeError('secret token must not escape')
        self.sdk.on_snapshot = fail
        recovery.step()
        self.assertEqual('SNAPSHOT_FAILED', recovery.view()['recoveryError'])
        self.assertNotIn('secret', str(recovery.view()))
        recovery.step()
        self.assertEqual(1, self.snapshots())
        self.assertEqual(1, sum(c[0] == 'start' for c in self.sdk.calls))

    def test_quotes_age_and_fatal_entitlement_hides_cached_prices(self):
        recovery = self.make()
        recovery.step()
        self.now = datetime(2026, 9, 25, 2, 2, tzinfo=timezone.utc)
        self.assertEqual('STALE_DATA', recovery.view()['state'])
        self.sdk.main(Result(ErrorCode=10001021))
        self.assertEqual('ENTITLEMENT_ERROR', recovery.view()['state'])
        self.assertEqual([], recovery.view()['quotes'])

    def test_same_time_conflicting_snapshot_needs_recovery(self):
        recovery = self.make()
        recovery.step()
        self.sdk.main(Result(ErrorCode=10002012))
        self.push()
        self.sdk.payload['Data']['TEST.SOY'][1] = 4100
        self.time = 5
        recovery.step()
        self.assertEqual('RECOVERY_REQUIRED', recovery.view()['state'])
        self.assertEqual('SNAPSHOT_CONFLICT', recovery.view()['recoveryError'])


if __name__ == '__main__':
    unittest.main()
