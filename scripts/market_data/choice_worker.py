"""Single-use, single-owner SDK worker. No forced kill, relogin or OS daemon.

HTTP lifetime is owned by the caller. STOP_TIMEOUT means the worker is still
alive: do not start another SDK session in this process. An external process
supervisor must handle a permanently blocked native SDK.
"""
import math
from threading import Event, Lock, Thread, current_thread


class ChoiceWorker:
    _ACTIVE = {'WAITING_DATA', 'RECEIVING', 'RECONNECTING'}
    _TERMINAL = {'CLOSED', 'SOURCE_ERROR', 'SESSION_LOST',
                 'ENTITLEMENT_ERROR', 'PENDING_AUTHORIZATION'}

    def __init__(self, subscription, publisher, *, authorized=False, interval=1):
        if type(interval) not in (int, float) or not math.isfinite(interval) or not 0.05 <= interval <= 10:
            raise ValueError('WORKER_INTERVAL_MUST_BE_0_05_TO_10_SECONDS')
        self._session = subscription
        self._publisher = publisher
        self._authorized = authorized is True
        self._interval = interval
        self._stop = Event()
        self._done = Event()
        self._lock = Lock()
        self._thread = None
        self._state = 'NEW'
        self._cycles = 0

    def start(self):
        with self._lock:
            if self._state != 'NEW':
                raise RuntimeError('WORKER_SINGLE_USE')
            if not self._authorized:
                self._state = 'PENDING_AUTHORIZATION'
                self._publisher.close('PENDING_AUTHORIZATION')
                self._done.set()
                return
            self._state = 'STARTING'
            self._thread = Thread(target=self._run, name='choice-quote-worker', daemon=True)
            try:
                self._thread.start()
            except Exception:
                self._state = 'SOURCE_ERROR'
                self._publisher.close('SOURCE_ERROR')
                self._done.set()
                raise RuntimeError('WORKER_START_FAILED') from None

    def _run(self):
        terminal = 'CLOSED'
        cleanup_failed = False
        try:
            self._session.start()
            while not self._stop.is_set():
                state = self._session.status()['state']
                if state not in self._ACTIVE:
                    terminal = state if state in self._TERMINAL else 'SOURCE_ERROR'
                    break
                with self._lock:
                    if not self._stop.is_set():
                        self._state = 'RUNNING'
                published = self._publisher.step()
                with self._lock:
                    self._cycles += 1
                if published in self._TERMINAL:
                    terminal = published
                    break
                # Fixed delay after work finishes: never accumulate overlapping jobs.
                if self._stop.wait(self._interval):
                    break
        except Exception:
            terminal = 'SOURCE_ERROR'
        finally:
            try:
                self._publisher.close(terminal)
            except Exception:
                terminal = 'SOURCE_ERROR'
            try:
                self._session.close()
                cleanup_failed = bool(self._session.status().get('cleanupError'))
            except Exception:
                cleanup_failed = True
            if cleanup_failed:
                try:
                    self._publisher.close('SOURCE_ERROR')
                except Exception:
                    pass
            with self._lock:
                self._state = 'CLEANUP_ERROR' if cleanup_failed else terminal
                self._done.set()

    def stop(self, *, timeout=5):
        if type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0 <= timeout <= 30:
            raise ValueError('STOP_TIMEOUT_MUST_BE_0_TO_30_SECONDS')
        with self._lock:
            if self._done.is_set():
                return True
            self._stop.set()
            self._publisher.close()
            thread = self._thread
            if thread is None:
                self._state = 'CLOSED'
                self._done.set()
                return True
            self._state = 'STOPPING'
        if thread is not current_thread():
            thread.join(timeout)
        with self._lock:
            if not self._done.is_set():
                self._state = 'STOP_TIMEOUT'
                return False
            return True

    def wait(self, timeout=5):
        if type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0 <= timeout <= 30:
            raise ValueError('WAIT_TIMEOUT_MUST_BE_0_TO_30_SECONDS')
        return self._done.wait(timeout)

    def status(self):
        with self._lock:
            return {'state': self._state, 'cycles': self._cycles,
                    'finished': self._done.is_set()}
