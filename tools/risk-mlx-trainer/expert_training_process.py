"""Owned offline worker lifecycle. No MLX imports or public command line."""
import os
import selectors
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

OUTPUT_LIMIT = 1024 * 1024


class WorkerFailure(RuntimeError):
    pass


def child_environment():
    environment = {key: value for key, value in os.environ.items()
                   if not any(word in key.upper() for word in
                              ('TOKEN', 'SECRET', 'PASSWORD', 'API_KEY'))}
    environment.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1',
                       HF_HUB_DISABLE_TELEMETRY='1', WANDB_MODE='disabled',
                       RISK_EXPERT_PARENT_PID=str(os.getpid()))
    return environment


def _stop(child):
    # The session belongs solely to this child; never signal the HTTP group.
    try:
        os.killpg(child.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        child.wait(timeout=2)
    except subprocess.TimeoutExpired:
        pass
    finally:
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        child.wait(timeout=2)


def run_bounded(command, directory: Path, deadline: float):
    """Drain pipes into private capped spools; never retain arbitrary output in RAM."""
    child = None
    try:
        if time.monotonic() >= deadline:
            raise WorkerFailure('EXPERT_TRAINING_TIMEOUT')
        with tempfile.TemporaryFile(dir=directory) as out, tempfile.TemporaryFile(dir=directory) as err:
            os.fchmod(out.fileno(), 0o600)
            os.fchmod(err.fileno(), 0o600)
            child = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     env=child_environment(), start_new_session=True)
            try:
                with selectors.DefaultSelector() as selector:
                    for pipe, spool in ((child.stdout, out), (child.stderr, err)):
                        os.set_blocking(pipe.fileno(), False)
                        selector.register(pipe, selectors.EVENT_READ, [spool, 0])
                    while selector.get_map() or child.poll() is None:
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            raise WorkerFailure('EXPERT_TRAINING_TIMEOUT')
                        for key, _ in selector.select(min(0.1, remaining)):
                            data = os.read(key.fileobj.fileno(), 65536)
                            if not data:
                                selector.unregister(key.fileobj)
                                continue
                            spool, size = key.data
                            if size + len(data) > OUTPUT_LIMIT:
                                raise WorkerFailure('EXPERT_TRAINING_OUTPUT_LIMIT')
                            spool.write(data)
                            key.data[1] += len(data)
                    if child.wait(timeout=max(.01, deadline - time.monotonic())) != 0:
                        raise WorkerFailure('EXPERT_TRAINING_WORKER_FAILED')
            finally:
                _stop(child)
                child.stdout.close()
                child.stderr.close()
    except WorkerFailure:
        raise
    except Exception:
        raise WorkerFailure('EXPERT_TRAINING_WORKER_FAILED') from None


def run_worker(request_path: Path, deadline: float):
    run_bounded([sys.executable, str(Path(__file__).with_name('expert_training_worker.py').resolve()),
                 '--request', str(request_path)], request_path.parent, deadline)
