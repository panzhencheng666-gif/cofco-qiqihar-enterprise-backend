"""Worker-driven recovery with no SDK imports, credentials, threads or HTTP server.

Call step() from one supervisor worker, never from vendor callback threads.
Read view() for a quote snapshot AND its recovery/transport state together.
RECONCILED is an offline-checkable data condition, not a live entitlement claim.
"""
from threading import RLock
from time import monotonic


class ChoiceRecovery:
    _READY = {'WAITING_DATA', 'RECEIVING'}
    _HIDE = {'NEW', 'STARTING', 'PENDING_AUTHORIZATION', 'ENTITLEMENT_ERROR',
             'SESSION_LOST', 'SOURCE_ERROR', 'CLOSED'}

    def __init__(self, subscription, normalizer_factory, *, clock=None):
        self._subscription = subscription
        self._factory = normalizer_factory
        self._normalizer = normalizer_factory()
        self._clock = clock or monotonic
        self._reconciled_generation = None
        self._retry_at = 0
        self._error = None
        self._lock = RLock()

    def step(self):
        # Serialize worker steps and view reads. SDK callbacks use their own lock.
        with self._lock:
            transport = self._subscription.status()
            if transport['state'] not in self._READY:
                return
            for _ in range(256):
                frame = self._subscription.take()
                if frame is None:
                    break
                self._normalizer.ingest(frame)
            transport = self._subscription.status()
            if transport['state'] not in self._READY:
                return
            if self._reconciled_generation == transport['lossGeneration']:
                return
            if self._clock() < self._retry_at:
                return
            result = self._subscription.request_snapshot()
            # Backoff begins after the request ends; slow failures cannot spin.
            self._retry_at = self._clock() + 5
            if 'error' in result:
                self._error = result['error']
                return
            candidate = self._factory()
            validation = candidate.ingest(result['frame'])
            quotes = candidate.snapshot()['quotes']
            if (validation['rejected'] or
                    {q['id'] for q in quotes} != self._normalizer.instrument_ids or
                    any(q['state'] != 'CURRENT' for q in quotes)):
                self._error = 'SNAPSHOT_INCOMPLETE_OR_INVALID'
                return
            transport = self._subscription.status()
            if (transport['state'] not in self._READY or
                    transport['lossGeneration'] != result['generation']):
                self._error = 'SNAPSHOT_SUPERSEDED'
                return
            merged = self._normalizer.ingest(result['frame'])
            # A newer buffered tick wins over an older recovery snapshot.
            if set(merged['rejected']) - {'DUPLICATE', 'OUT_OF_ORDER'}:
                self._error = 'SNAPSHOT_CONFLICT'
                return
            self._reconciled_generation = result['generation']
            self._error = None

    def view(self):
        with self._lock:
            quotes = self._normalizer.snapshot()['quotes']
            transport = self._subscription.status()
            state = transport['state']
            if state in self._HIDE:
                quotes = []
            elif state in self._READY:
                if self._reconciled_generation != transport['lossGeneration']:
                    state = 'RECOVERY_REQUIRED'
                elif any(q['state'] == 'STALE' for q in quotes):
                    state = 'STALE_DATA'
                else:
                    state = 'RECONCILED'
            return {'state': state, 'transport': transport, 'quotes': quotes,
                    'recoveryError': self._error}
