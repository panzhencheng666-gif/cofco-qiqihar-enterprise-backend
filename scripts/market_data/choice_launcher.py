"""Explicit foreground launcher. Defaults to check-only; never installs a service."""
import argparse
import json
import platform
import stat
from pathlib import Path
from types import ModuleType

from choice_preflight import read_config, _read
from choice_process import run_service
from choice_subscription import ChoiceSubscription
from choice_recovery import ChoiceRecovery
from choice_feed import QuotePublisher, LocalQuoteServer
from choice_worker import ChoiceWorker


_SDK_ERRORS = frozenset({'SDK_LOAD_FAILED', 'SDK_PLATFORM_NOT_VERIFIED',
    'SDK_NATIVE_PATH_UNAVAILABLE', 'SDK_NATIVE_PATH_MISMATCH', 'SDK_NATIVE_LIBRARY_INVALID'})


class SdkLoadError(RuntimeError):
    def __init__(self, code):
        self.code = code if code in _SDK_ERRORS else 'SDK_LOAD_FAILED'
        super().__init__(self.code)


def _verify_native_path(module, source):
    expected = source.resolve().parent / 'libs/mac/libEMQuantAPIx64.dylib'
    try:
        selected = module.UtilAccess.GetLibraryPath()
        if not isinstance(selected, str) or not selected or not Path(selected).is_absolute():
            raise ValueError()
    except Exception:
        raise SdkLoadError('SDK_NATIVE_PATH_UNAVAILABLE') from None
    try:
        # Refuse redirected library directories as well as final symlinks.
        if expected.resolve(strict=True) != expected or not stat.S_ISREG(expected.lstat().st_mode):
            raise ValueError()
        if Path(selected).resolve() != expected:
            raise SdkLoadError('SDK_NATIVE_PATH_MISMATCH')
    except SdkLoadError:
        raise
    except (OSError, ValueError, RuntimeError):
        raise SdkLoadError('SDK_NATIVE_LIBRARY_INVALID') from None


def load_sdk(path):
    """Execute the explicitly chosen, trusted SDK file; never search sys.path.

    This loads executable code, not untrusted data. Use the official reviewed SDK
    with its original adjacent native libraries. It is not an authenticity check.
    """
    if platform.system() != 'Darwin':
        # Other platforms need their own validated discovery and initialization.
        # In particular, the vendor Linux ARM import can exec a new process.
        raise SdkLoadError('SDK_PLATFORM_NOT_VERIFIED')
    try:
        path = Path(path).absolute()
        source = _read(path, 8 * 1024 * 1024)
        module = ModuleType('EmQuantAPI')
        module.__file__ = str(path)
        module.__package__ = ''
        exec(compile(source, str(path), 'exec'), module.__dict__)
        sdk = module.c
        if not all(callable(getattr(sdk, name, None)) for name in (
                'start', 'stop', 'csq', 'csqcancel', 'csqsnapshot')):
            raise ValueError()
        _verify_native_path(module, path)
        return sdk
    except SdkLoadError:
        raise
    except Exception:
        raise SdkLoadError('SDK_LOAD_FAILED') from None


def launch(config_path, lock_path=None, sdk_loader=None, *, run=False, stop_event=None):
    diagnostics, config = read_config(config_path)
    if diagnostics['errors'] or run is not True:
        return {**diagnostics, 'exitCode': 2 if diagnostics['errors'] else 0}
    if lock_path is None or not callable(sdk_loader):
        return {'exitCode': 2, 'state': 'RUN_ARGUMENTS_REQUIRED'}

    sdk_error = None

    def build():
        nonlocal sdk_error
        # run_service invokes this only while owning the persistent instance lock.
        # No configuration file is re-read after validation.
        try:
            sdk = sdk_loader()
        except SdkLoadError as error:
            sdk_error = error.code
            raise
        session = ChoiceSubscription(sdk, config.codes, authorized=True)
        recovery = ChoiceRecovery(session, config.normalizer)
        publisher = QuotePublisher(recovery, authorized=True)
        server = LocalQuoteServer(publisher, config.token, port=config.port)
        try:
            worker = ChoiceWorker(session, publisher, authorized=True, interval=config.interval)
            return worker, server
        except Exception:
            server.close()
            raise

    result = run_service(build, lock_path, authorized=True, stop_event=stop_event)
    if sdk_error and result['state'] == 'START_FAILED_EXIT_REQUIRED':
        return {'exitCode': 5, 'state': sdk_error}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--run', action='store_true', help='Explicitly enable foreground SDK startup')
    parser.add_argument('--lock-file', help='Persistent lock in a private mode-0700 directory')
    parser.add_argument('--sdk-file', help='Trusted official EmQuantAPI.py with original native libraries')
    args = parser.parse_args()
    if args.run and (not args.lock_file or not args.sdk_file):
        result = {'exitCode': 2, 'state': 'RUN_ARGUMENTS_REQUIRED'}
    else:
        result = launch(args.config, args.lock_file,
                        (lambda: load_sdk(args.sdk_file)) if args.sdk_file else None, run=args.run)
    print(json.dumps(result, ensure_ascii=False, allow_nan=False), flush=True)
    return result['exitCode']


if __name__ == '__main__':
    raise SystemExit(main())
