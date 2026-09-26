"""Foreground ownership for a preconfigured Choice worker and local HTTP feed.

POSIX only. No vendor imports, login settings, daemon installation or relogin.
The caller must exit its dedicated process after any nonzero result.
"""
import fcntl
import math
import os
from pathlib import Path
import signal
import stat
from threading import Event, current_thread, main_thread

# Keep descriptors alive after uncertain cleanup. Only process exit releases them.
_quarantined_locks = []


class InstanceLock:
    """Advisory lock on a persistent file in a private, existing directory."""
    def __init__(self, path):
        self._path = Path(path)
        self._fd = None

    def acquire(self):
        if self._fd is not None:
            raise RuntimeError('ALREADY_RUNNING')
        parent_fd = fd = None
        try:
            parent_fd = os.open(self._path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            parent = os.fstat(parent_fd)
            if parent.st_uid != os.getuid() or stat.S_IMODE(parent.st_mode) != 0o700:
                raise ValueError()
            fd = os.open(self._path.name, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK,
                         0o600, dir_fd=parent_fd)
            info = os.fstat(fd)
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                    or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
                raise ValueError()
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError('ALREADY_RUNNING') from None
            current = os.stat(self._path.name, dir_fd=parent_fd, follow_symlinks=False)
            if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
                raise ValueError()
            self._fd, fd = fd, None
        except (OSError, ValueError):
            raise RuntimeError('LOCK_FILE_INVALID') from None
        finally:
            if fd is not None:
                os.close(fd)
            if parent_fd is not None:
                os.close(parent_fd)

    def close(self):
        if self._fd is not None:
            fd, self._fd = self._fd, None
            os.close(fd)
        # Never unlink: a waiter may already hold an open descriptor to this inode.


def run_service(factory, lock_path, *, authorized=False, stop_event=None, stop_timeout=5):
    """Own one foreground lifecycle; factory constructs (worker, HTTP server).

    Factory must not log in/start threads; worker.start owns SDK login. Configuration
    and vendor entitlement must be validated by the caller before opting in.
    This must run on the main thread of a dedicated, non-forking process.
    """
    if authorized is not True:
        return {'exitCode': 2, 'state': 'PENDING_AUTHORIZATION'}
    if current_thread() is not main_thread():
        raise RuntimeError('MAIN_THREAD_REQUIRED')
    if (type(stop_timeout) not in (int, float) or not math.isfinite(stop_timeout)
            or not 0 <= stop_timeout <= 30):
        raise ValueError('STOP_TIMEOUT_MUST_BE_0_TO_30_SECONDS')
    guard = InstanceLock(lock_path)
    try:
        guard.acquire()
    except RuntimeError as error:
        reason = 'ALREADY_RUNNING' if str(error) == 'ALREADY_RUNNING' else 'LOCK_FILE_INVALID'
        return {'exitCode': 4, 'state': reason}
    stop = stop_event if stop_event is not None else Event()
    handlers = {}
    worker = server = None
    uncertain = False
    result = {'exitCode': 5, 'state': 'START_FAILED_EXIT_REQUIRED'}
    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            handlers[signum] = signal.getsignal(signum)
            signal.signal(signum, lambda *_args: stop.set())
        if stop.is_set():
            result = {'exitCode': 0, 'state': 'CLOSED'}
        else:
            worker, server = factory()
            server.start()
            worker.start()
            while not stop.is_set():
                if worker.wait(.1):
                    break
            state = worker.status()['state']
            result = ({'exitCode': 0, 'state': 'CLOSED'} if stop.is_set()
                      else {'exitCode': 3, 'state': state if state in {
                          'CLOSED', 'SOURCE_ERROR', 'SESSION_LOST', 'ENTITLEMENT_ERROR',
                          'PENDING_AUTHORIZATION', 'CLEANUP_ERROR'} else 'SOURCE_ERROR'})
    except Exception:
        uncertain = True
        result = {'exitCode': 5, 'state': 'START_FAILED_EXIT_REQUIRED'}
    finally:
        if worker is not None:
            try:
                if not worker.stop(timeout=stop_timeout):
                    uncertain = True
                    result = {'exitCode': 5, 'state': 'STOP_TIMEOUT'}
                elif worker.status()['state'] == 'CLEANUP_ERROR':
                    uncertain = True
                    result = {'exitCode': 5, 'state': 'CLEANUP_ERROR'}
            except Exception:
                uncertain = True
                result = {'exitCode': 5, 'state': 'CLEANUP_ERROR'}
        if server is not None:
            try:
                server.close()
            except Exception:
                uncertain = True
                result = {'exitCode': 5, 'state': 'CLEANUP_ERROR'}
        for signum, handler in handlers.items():
            signal.signal(signum, handler)
        if uncertain:
            _quarantined_locks.append(guard)
        else:
            guard.close()
    return result
