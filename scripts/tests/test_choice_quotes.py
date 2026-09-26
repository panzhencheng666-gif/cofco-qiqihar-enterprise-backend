import importlib.util
import json
from pathlib import Path
from datetime import datetime, timedelta, timezone
import unittest

MODULE = Path(__file__).parents[1] / 'market_data/choice_quotes.py'
spec = importlib.util.spec_from_file_location('choice_quotes', MODULE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
QuoteNormalizer = module.QuoteNormalizer

NOW = datetime(2026, 9, 25, 2, 0, 0, tzinfo=timezone.utc)
# Synthetic provider identifiers; these are not real Choice futures mappings.
CATALOGUE = {'dce-corn': '元/吨', 'dce-soybean': '元/吨'}
BINDINGS = [
    {'code': 'TEST.CORN', 'id': 'dce-corn', 'unit': '元/吨', 'verified': True},
    {'code': 'TEST.SOY', 'id': 'dce-soybean', 'unit': '元/吨', 'verified': True},
]


def frame(rows=None, indicators=None):
    rows = rows if rows is not None else {'TEST.CORN': ['2026-09-25T10:00:00+08:00', 2000, 1990]}
    return {'Codes': list(rows), 'Indicators': indicators or ['TIME', 'NOW', 'PRECLOSE'],
            'Dates': ['2026-09-25'], 'Data': rows}


class ChoiceQuotesTest(unittest.TestCase):
    def make(self):
        self.now = NOW
        return QuoteNormalizer(CATALOGUE, BINDINGS, clock=lambda: self.now)

    def test_offset_timestamp_becomes_utc_and_wire_payload_is_json(self):
        adapter = self.make()
        self.assertEqual(1, adapter.ingest(frame())['accepted'])
        result = json.loads(json.dumps(adapter.snapshot(), allow_nan=False))
        quote = result['quotes'][0]
        self.assertEqual('dce-corn', quote['id'])
        self.assertEqual('2026-09-25T02:00:00Z', quote['sourceAt'])
        self.assertEqual(2000, quote['last'])
        self.assertEqual(1990, quote['previousClose'])
        self.assertEqual('东方财富 Choice', quote['provider'])

    def test_time_only_or_naive_dates_are_never_filled_from_clock_or_dates(self):
        for value in ['10:00:00', '100000', '2026-09-25', '2026-09-25T10:00:00', None]:
            adapter = self.make()
            result = adapter.ingest(frame({'TEST.CORN': [value, 2000, 1990]}))
            self.assertEqual({'SOURCE_TIME_UNVERIFIED': 1}, result['rejected'])
            self.assertEqual([], adapter.snapshot()['quotes'])

    def test_negative_null_bool_and_nonfinite_prices_are_rejected(self):
        for value in [0, -1, None, True, float('nan'), float('inf'), '2000']:
            adapter = self.make()
            self.assertEqual({'INVALID_PRICE': 1}, adapter.ingest(
                frame({'TEST.CORN': ['2026-09-25T02:00:00Z', value, 1990]}))['rejected'])

    def test_invalid_offset_and_utc_conversion_overflow_are_rejected(self):
        for value in ['2026-09-25T10:00:00+08:99', '0001-01-01T00:00:00+08:00',
                      '9999-12-31T23:59:59-08:00']:
            adapter = self.make()
            self.assertEqual({'SOURCE_TIME_UNVERIFIED': 1}, adapter.ingest(
                frame({'TEST.CORN': [value, 2000, 1990]}))['rejected'])

    def test_bad_row_does_not_discard_good_row(self):
        adapter = self.make()
        result = adapter.ingest(frame({'TEST.CORN': ['2026-09-25T02:00:00Z', 2000, None],
                                      'TEST.SOY': ['10:00:00', 4000, 3990],
                                      'UNKNOWN': ['2026-09-25T02:00:00Z', 7, 6]}))
        self.assertEqual(1, result['accepted'])
        self.assertEqual({'SOURCE_TIME_UNVERIFIED': 1, 'UNMAPPED_CODE': 1}, result['rejected'])
        self.assertIsNone(adapter.snapshot()['quotes'][0]['previousClose'])

    def test_old_and_same_timestamp_conflicting_ticks_never_rewind(self):
        adapter = self.make()
        adapter.ingest(frame())
        old = adapter.ingest(frame({'TEST.CORN': ['2026-09-25T01:59:59Z', 1999, 1990]}))
        self.assertEqual({'OUT_OF_ORDER': 1}, old['rejected'])
        conflict = adapter.ingest(frame({'TEST.CORN': ['2026-09-25T02:00:00Z', 1999, 1990]}))
        self.assertEqual({'TIMESTAMP_CONFLICT': 1}, conflict['rejected'])
        self.assertEqual({'DUPLICATE': 1}, adapter.ingest(frame())['rejected'])
        self.assertEqual(2000, adapter.snapshot()['quotes'][0]['last'])

    def test_cache_ages_without_refreshing_source_time(self):
        adapter = self.make()
        adapter.ingest(frame())
        self.now += timedelta(seconds=91)
        result = adapter.snapshot()['quotes'][0]
        self.assertEqual('STALE', result['state'])
        self.assertEqual('2026-09-25T02:00:00Z', result['sourceAt'])
        self.assertEqual({'DUPLICATE': 1}, adapter.ingest(frame())['rejected'])
        self.assertEqual('STALE', adapter.snapshot()['quotes'][0]['state'])

    def test_future_timestamp_rejected_and_night_session_date_preserved(self):
        adapter = self.make()
        result = adapter.ingest(frame({'TEST.CORN': ['2026-09-26T10:00:00+08:00', 1, 1]}))
        self.assertEqual({'FUTURE_SOURCE_TIME': 1}, result['rejected'])
        adapter.ingest(frame({'TEST.CORN': ['2026-09-25T00:01:00+08:00', 2, 1]}))
        self.assertEqual('2026-09-24T16:01:00Z', adapter.snapshot()['quotes'][0]['sourceAt'])

    def test_mapping_must_be_verified_known_unique_and_unit_consistent(self):
        for mapping in [[], [{'code': 'A', 'id': 'dce-corn', 'unit': '元/吨'}],
                        [{'code': 'A', 'id': 'missing', 'unit': '元/吨', 'verified': True}],
                        [{'code': 'A', 'id': 'dce-corn', 'unit': '美元/吨', 'verified': True}],
                        [BINDINGS[0], BINDINGS[0]]]:
            with self.assertRaises(ValueError):
                QuoteNormalizer(CATALOGUE, mapping, clock=lambda: NOW)

    def test_ambiguous_dimensions_rejected_without_touching_cache(self):
        adapter = self.make()
        adapter.ingest(frame())
        for malformed in [dict(frame(), Indicators=['TIME', 'NOW', 'NOW']),
                          dict(frame(), Codes=['TEST.CORN', 'TEST.CORN']),
                          dict(frame(), Dates=['2026-09-24', '2026-09-25'])]:
            self.assertEqual({'INVALID_FRAME': 1}, adapter.ingest(malformed)['rejected'])
        self.assertEqual({'INVALID_ROW': 1}, adapter.ingest(frame({'TEST.CORN': [1]}))['rejected'])
        self.assertEqual(2000, adapter.snapshot()['quotes'][0]['last'])

    def test_indicator_order_is_resolved_by_name_not_position(self):
        adapter = self.make()
        adapter.ingest(frame({'TEST.CORN': [2000, '2026-09-25T02:00:00Z']}, ['NOW', 'TIME']))
        quote = adapter.snapshot()['quotes'][0]
        self.assertEqual(2000, quote['last'])
        self.assertIsNone(quote['previousClose'])

    def test_snapshot_is_detached_and_partial_batches_preserve_other_instruments(self):
        adapter = self.make()
        adapter.ingest(frame())
        adapter.ingest(frame({'TEST.SOY': ['2026-09-25T02:00:00Z', 4000, 3900]}))
        self.assertEqual(2, len(adapter.snapshot()['quotes']))
        adapter.snapshot()['quotes'][0]['last'] = 999
        self.assertEqual(2000, adapter.snapshot()['quotes'][0]['last'])


if __name__ == '__main__':
    unittest.main()
