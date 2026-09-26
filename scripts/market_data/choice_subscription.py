"""Transport-only Choice SDK session for a dedicated market-data process.

No SDK import, credentials, default symbols, price conversion or HTTP publishing.
Inject the official SDK's `c` after activation; use exactly one session per process
because the vendor SDK stores callbacks/login globally. A session is single-use.
"""
from collections import deque
from copy import deepcopy
import re
from threading import Lock


class ChoiceSubscription:
    _ACCEPTING = {'STARTING', 'WAITING_DATA', 'RECEIVING', 'RECONNECTING'}
    _ERROR_STATES = {
        10001009: 'SESSION_LOST', 10001011: 'SESSION_LOST',
        10001021: 'ENTITLEMENT_ERROR', 10001022: 'ENTITLEMENT_ERROR',
        10002009: 'RECONNECTING', 10002012: 'RECONNECTING',
    }

    def __init__(self, sdk, codes, *, authorized=False, indicators=None, capacity=256):
        self._codes = self._tokens(codes, r'[A-Za-z0-9_.-]+')
        self._indicators = self._tokens(
            ['TIME', 'NOW'] if indicators is None else indicators,
            r'[A-Za-z][A-Za-z0-9_]*')
        if len(self._indicators) > 64:
            raise ValueError('CHOICE_MAX_64_INDICATORS')
        if type(capacity) is not int or not 1 <= capacity <= 4096:
            raise ValueError('CHOICE_INVALID_CAPACITY')
        self._sdk = sdk
        self._authorized = authorized is True
        self._capacity = capacity
        self._frames = deque()
        self._lock = Lock()
        self._operation = Lock()
        self._state = 'NEW'
        self._error = None
        self._cleanup_error = None
        self._serial = None
        self._logged_in = False
        self._dropped = 0
        self._loss_generation = 0

    @staticmethod
    def _tokens(values, pattern):
        if not isinstance(values, (list, tuple)) or not values:
            raise ValueError('CHOICE_EXPLICIT_CODES_AND_FIELDS_REQUIRED')
        if any(not isinstance(v, str) or not re.fullmatch(pattern, v) for v in values):
            raise ValueError('CHOICE_INVALID_CODE_OR_FIELD')
        normalized = [v.upper() for v in values]
        if len(set(normalized)) != len(normalized):
            raise ValueError('CHOICE_DUPLICATE_CODE_OR_FIELD')
        return tuple(normalized)

    @staticmethod
    def _code(result):
        code = getattr(result, 'ErrorCode', None)
        if type(code) is not int:
            raise ValueError('CHOICE_MALFORMED_RESULT')
        return code

    def _fail(self, code=None, *, subscription_exists=True):
        with self._lock:
            if self._state in self._ACCEPTING:
                self._loss_generation += 1
                self._state = self._ERROR_STATES.get(code, 'SOURCE_ERROR')
                if self._state == 'RECONNECTING' and not subscription_exists:
                    self._state = 'SOURCE_ERROR'
                self._error = code
                self._frames.clear()

    def _event(self, result):
        # SDK callback threads must not perform stop/login/cancel calls.
        try:
            code = self._code(result)
            if code:
                self._fail(code)
        except Exception:
            self._fail()

    def _tick(self, result):
        try:
            code = self._code(result)
            if code:
                self._fail(code)
                return
            frame = {key: deepcopy(getattr(result, key))
                     for key in ('Codes', 'Indicators', 'Dates', 'Data')}
            if not isinstance(frame['Data'], dict) or not frame['Data']:
                return
            with self._lock:
                if self._state not in self._ACCEPTING:
                    return
                if len(self._frames) == self._capacity:
                    self._frames.popleft()
                    self._dropped += 1
                    self._loss_generation += 1
                self._frames.append(frame)
                # Receiving a batch is not proof of fresh/authorized price content.
                self._state = 'RECEIVING'
                self._error = None
        except Exception:
            self._fail()

    def start(self):
        with self._operation:
            with self._lock:
                if self._state != 'NEW':
                    return
                if not self._authorized:
                    self._state = 'PENDING_AUTHORIZATION'
                    return
                self._state = 'STARTING'
            try:
                result = self._sdk.start(
                    'ForceLogin=0,RecordLoginInfo=0,HTTPTimeout=15',
                    logcallback=lambda _message: 1, mainCallBack=self._event)
                code = self._code(result)
                if code:
                    self._fail(code, subscription_exists=False)
                    return
                self._logged_in = True
                with self._lock:
                    accepting = self._state in self._ACCEPTING
                if accepting:
                    result = self._sdk.csq(','.join(self._codes),
                                           ','.join(self._indicators),
                                           'Pushtype=2', fncallback=self._tick)
                    code = self._code(result)
                    if code:
                        self._fail(code, subscription_exists=False)
                    else:
                        serial = getattr(result, 'SerialID', None)
                        if type(serial) is not int or serial <= 0:
                            raise ValueError('CHOICE_INVALID_SUBSCRIPTION_ID')
                        self._serial = serial
                        with self._lock:
                            if self._state == 'STARTING':
                                self._state = 'WAITING_DATA'
            except Exception:
                # Never expose SDK ErrorMsg, URLs, token paths or exception text.
                self._fail()
            finally:
                with self._lock:
                    failed = self._state not in self._ACCEPTING
                if failed:
                    self._release()

    def _release(self):
        # Called under the operation lock, never the callback lock.
        errors = []
        if self._serial is not None:
            try:
                if self._code(self._sdk.csqcancel(self._serial)):
                    errors.append('CANCEL_FAILED')
            except Exception:
                errors.append('CANCEL_FAILED')
            finally:
                self._serial = None
        if self._logged_in:
            try:
                if self._code(self._sdk.stop()):
                    errors.append('STOP_FAILED')
            except Exception:
                errors.append('STOP_FAILED')
            finally:
                self._logged_in = False
        if errors:
            with self._lock:
                self._cleanup_error = ','.join(errors)

    def close(self):
        with self._operation:
            with self._lock:
                if self._state == 'CLOSED':
                    return
                self._state = 'CLOSED'
                self._frames.clear()
            self._release()

    def take(self):
        """Get one unvalidated raw batch without blocking an SDK thread."""
        with self._lock:
            return self._frames.popleft() if self._frames else None

    def request_snapshot(self):
        """Worker-only bounded SDK request; never call from an SDK callback.

        Callback loss generation fences reconnect/overflow during the request.
        SDK HTTPTimeout is configured by start(); this method adds no retries.
        """
        with self._operation:
            with self._lock:
                if self._state not in {'WAITING_DATA', 'RECEIVING'} or self._serial is None:
                    return {'error': 'SNAPSHOT_UNAVAILABLE'}
                generation = self._loss_generation
            try:
                result = self._sdk.csqsnapshot(','.join(self._codes),
                    ','.join(self._indicators), '')
                code = self._code(result)
                if code:
                    if code in self._ERROR_STATES:
                        self._fail(code)
                    return {'error': 'SNAPSHOT_FAILED'}
                frame = {key: deepcopy(getattr(result, key))
                         for key in ('Codes', 'Indicators', 'Dates', 'Data')}
            except Exception:
                return {'error': 'SNAPSHOT_FAILED'}
            with self._lock:
                if (self._state not in {'WAITING_DATA', 'RECEIVING'}
                        or generation != self._loss_generation):
                    return {'error': 'SNAPSHOT_SUPERSEDED'}
            return {'frame': frame, 'generation': generation}

    def status(self):
        with self._lock:
            return {'state': self._state, 'errorCode': self._error,
                    'queuedFrames': len(self._frames), 'droppedFrames': self._dropped,
                    'lossGeneration': self._loss_generation,
                    'cleanupError': self._cleanup_error}
