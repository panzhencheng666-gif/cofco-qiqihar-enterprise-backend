"""Generate a disabled macOS LaunchAgent; never register, enable or start it."""
import argparse
import json
import os
from pathlib import Path
import plistlib
import stat

LABEL = 'com.cofco.qiqihar.market-choice'


def _absolute(value):
    value = os.fspath(value)
    if not isinstance(value, str) or not Path(value).is_absolute() or any(ord(c) < 32 for c in value):
        raise ValueError('ABSOLUTE_PATH_REQUIRED')
    # Preserve the virtualenv interpreter path; resolving its symlink loses venv selection.
    return value


def _private_runtime(path):
    try:
        info = path.lstat()
        if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o700):
            raise ValueError()
    except (OSError, ValueError):
        raise ValueError('PRIVATE_RUNTIME_REQUIRED') from None
    for name in ('stdout.log', 'stderr.log'):
        try:
            info = (path / name).lstat()
        except FileNotFoundError:
            continue
        except OSError:
            raise ValueError('PRIVATE_LOG_REQUIRED') from None
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
            raise ValueError('PRIVATE_LOG_REQUIRED')


def build_plist(*, python, launcher, config, sdk_file, runtime_dir):
    python, launcher, config, sdk_file, runtime_dir = map(
        _absolute, (python, launcher, config, sdk_file, runtime_dir))
    runtime = Path(runtime_dir)
    _private_runtime(runtime)
    return {
        'Label': LABEL,
        'Disabled': True, 'RunAtLoad': False, 'KeepAlive': False,
        'ProgramArguments': [python, '-B', launcher, '--config', config, '--run',
                             '--lock-file', str(runtime / 'choice.lock'), '--sdk-file', sdk_file],
        'WorkingDirectory': runtime_dir,
        'EnvironmentVariables': {'PYTHONDONTWRITEBYTECODE': '1', 'PYTHONUNBUFFERED': '1'},
        'StandardOutPath': str(runtime / 'stdout.log'),
        'StandardErrorPath': str(runtime / 'stderr.log'),
        'Umask': 0o077, 'ExitTimeOut': 15, 'ProcessType': 'Background',
    }


def write_plist(path, value):
    data = plistlib.dumps(value, fmt=plistlib.FMT_XML, sort_keys=True)
    fd = os.open(_absolute(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as output:
        output.write(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('python', 'launcher', 'config', 'sdk-file', 'runtime-dir', 'output'):
        parser.add_argument('--' + name, required=True)
    args = vars(parser.parse_args())
    output = args.pop('output')
    try:
        write_plist(output, build_plist(**args))
    except FileExistsError:
        print(json.dumps({'state': 'OUTPUT_ALREADY_EXISTS'}))
        return 2
    except (OSError, ValueError, TypeError) as error:
        code = str(error)
        if code not in {'ABSOLUTE_PATH_REQUIRED', 'PRIVATE_RUNTIME_REQUIRED', 'PRIVATE_LOG_REQUIRED'}:
            code = 'CONFIG_GENERATION_FAILED'
        print(json.dumps({'state': code}))
        return 2
    print(json.dumps({'state': 'CONFIG_GENERATED_DISABLED', 'label': LABEL, 'installed': False}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
