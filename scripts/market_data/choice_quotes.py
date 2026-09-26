"""Conservative conversion boundary for Choice callback batches.

Only explicitly verified, same-unit mappings are accepted. This module currently
accepts full ISO source timestamps with offsets; time-only SDK fields need a
separately verified date/timezone resolver before they can enter this boundary.
It never treats SDK Dates (documented as local dates) as a quote source date.
"""
from collections import Counter
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import math
import re
from threading import Lock


class QuoteNormalizer:
    def __init__(self, catalogue, bindings, *, clock=None):
        if not isinstance(catalogue, dict) or not isinstance(bindings, (list, tuple)) or not bindings:
            raise ValueError('VERIFIED_MAPPING_REQUIRED')
        self._bindings = {}
        ids = set()
        for binding in bindings:
            if not isinstance(binding, dict) or binding.get('verified') is not True:
                raise ValueError('VERIFIED_MAPPING_REQUIRED')
            code, identifier, unit = (binding.get(key) for key in ('code', 'id', 'unit'))
            if not isinstance(code, str) or not re.fullmatch(r'[A-Z0-9_.-]+', code):
                raise ValueError('INVALID_PROVIDER_CODE')
            if not isinstance(identifier, str) or identifier not in catalogue:
                raise ValueError('UNKNOWN_INSTRUMENT')
            if not isinstance(unit, str) or not unit or unit != catalogue[identifier]:
                raise ValueError('UNIT_MISMATCH')
            if code in self._bindings or identifier in ids:
                raise ValueError('DUPLICATE_MAPPING')
            self._bindings[code] = identifier
            ids.add(identifier)
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._latest = {}
        self._lock = Lock()

    def _now(self):
        value = self._clock()
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValueError('UTC_AWARE_CLOCK_REQUIRED')
        return value.astimezone(timezone.utc)

    @property
    def instrument_ids(self):
        return frozenset(self._bindings.values())

    @staticmethod
    def _price(value):
        # SDK numeric values only; no boolean, formatted string or NaN coercion.
        if type(value) not in (int, float):
            raise ValueError('INVALID_PRICE')
        try:
            result = float(value)
        except (ValueError, OverflowError):
            raise ValueError('INVALID_PRICE') from None
        if not math.isfinite(result) or result <= 0:
            raise ValueError('INVALID_PRICE')
        return result

    @staticmethod
    def _time(value):
        # Require a date and explicit offset; never use receipt time as sourceAt.
        if not isinstance(value, str) or not re.fullmatch(
                r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)', value):
            raise ValueError('SOURCE_TIME_UNVERIFIED')
        try:
            return datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(timezone.utc)
        except (ValueError, OverflowError):
            raise ValueError('SOURCE_TIME_UNVERIFIED') from None

    @staticmethod
    def _shape(frame):
        if not isinstance(frame, dict):
            raise ValueError('INVALID_FRAME')
        codes, indicators, dates, data = (frame.get(key) for key in ('Codes', 'Indicators', 'Dates', 'Data'))
        if not all(isinstance(value, list) for value in (codes, indicators, dates)) or not isinstance(data, dict):
            raise ValueError('INVALID_FRAME')
        if not codes or not indicators or len(indicators) > 64 or len(dates) > 1:
            raise ValueError('INVALID_FRAME')
        if any(not isinstance(value, str) for value in codes + indicators):
            raise ValueError('INVALID_FRAME')
        if len(set(codes)) != len(codes) or set(codes) != set(data):
            raise ValueError('INVALID_FRAME')
        fields = [value.upper() for value in indicators]
        if len(set(fields)) != len(fields) or not {'TIME', 'NOW'} <= set(fields):
            raise ValueError('INVALID_FRAME')
        return codes, fields, data

    def ingest(self, frame):
        rejected = Counter()
        try:
            codes, fields, data = self._shape(frame)
        except ValueError:
            return {'accepted': 0, 'rejected': {'INVALID_FRAME': 1}}
        now = self._now()
        accepted = 0
        with self._lock:
            for code in codes:
                if code not in self._bindings:
                    rejected['UNMAPPED_CODE'] += 1
                    continue
                values = data[code]
                if not isinstance(values, list) or len(values) != len(fields):
                    rejected['INVALID_ROW'] += 1
                    continue
                row = dict(zip(fields, values))
                try:
                    source_at = self._time(row['TIME'])
                    if source_at > now + timedelta(seconds=60):
                        raise ValueError('FUTURE_SOURCE_TIME')
                    last = self._price(row['NOW'])
                    previous = row.get('PRECLOSE')
                    if previous is not None:
                        previous = self._price(previous)
                except ValueError as error:
                    rejected[str(error)] += 1
                    continue
                identifier = self._bindings[code]
                quote = {'id': identifier, 'last': last, 'previousClose': previous,
                         'sourceAt': source_at.isoformat().replace('+00:00', 'Z'),
                         'provider': '东方财富 Choice'}
                cached = self._latest.get(identifier)
                if cached:
                    old_time, old_quote = cached
                    if source_at < old_time:
                        rejected['OUT_OF_ORDER'] += 1
                        continue
                    if source_at == old_time:
                        rejected['DUPLICATE' if quote == old_quote else 'TIMESTAMP_CONFLICT'] += 1
                        continue
                self._latest[identifier] = (source_at, quote)
                accepted += 1
        return {'accepted': accepted, 'rejected': dict(rejected)}

    def snapshot(self):
        now = self._now()
        with self._lock:
            return {'quotes': [dict(deepcopy(quote), state=(
                'STALE' if source_at < now - timedelta(seconds=90) else 'CURRENT'))
                for _, (source_at, quote) in sorted(self._latest.items())]}
