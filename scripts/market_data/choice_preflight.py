"""Read-only local configuration checks. Never imports or starts the vendor SDK."""
import argparse
import json
import math
import os
from pathlib import Path
import re
import stat

from choice_quotes import QuoteNormalizer

_KEYS = {'schemaVersion', 'distributionAuthorized', 'host', 'port',
         'intervalSeconds', 'catalogueFile', 'bindingsFile', 'bearerTokenFile'}
_MAPPING_ERRORS = {'VERIFIED_MAPPING_REQUIRED', 'INVALID_PROVIDER_CODE',
                   'UNKNOWN_INSTRUMENT', 'UNIT_MISMATCH', 'DUPLICATE_MAPPING'}


def _read(path, limit, *, secret=False):
    # O_NONBLOCK prevents a FIFO/device path from hanging before fstat rejects it.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise ValueError('FILE_INVALID')
        if secret and (info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600):
            raise ValueError('FILE_INVALID')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            raw = stream.read(limit + 1)
        if len(raw) > limit:
            raise ValueError('FILE_INVALID')
        return raw.decode('utf-8')
    finally:
        os.close(fd)


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def _constant(_value):
    raise ValueError('NON_FINITE_JSON')


def _json(path):
    return json.loads(_read(path, 1024 * 1024), object_pairs_hook=_pairs,
                      parse_constant=_constant)


def _path(base, value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError('PATH_INVALID')
    return base / value


class RuntimeConfig:
    """In-memory checked values. Default object repr never displays the token."""
    def __init__(self, port, interval, token, catalogue, bindings):
        self.port, self.interval, self.token = port, interval, token
        self._catalogue = catalogue
        self._bindings = bindings

    @property
    def codes(self):
        return [binding['code'] for binding in self._bindings]

    def normalizer(self):
        return QuoteNormalizer(self._catalogue, self._bindings)


def read_config(path):
    """Read each file once. Return (public diagnostics, private checked values)."""
    result = {'schemaVersion': 1, 'state': 'CONFIG_INVALID', 'readyForLive': False,
              'supplierPermission': 'NOT_CHECKED', 'mappingCount': 0, 'errors': []}
    errors = result['errors']
    try:
        path = Path(path).absolute()
        config = _json(path)
    except (OSError, ValueError, TypeError, RecursionError):
        errors.append('CONFIG_FILE_INVALID')
        return result, None
    if (not isinstance(config, dict) or set(config) != _KEYS
            or type(config.get('schemaVersion')) is not int or config['schemaVersion'] != 1):
        errors.append('CONFIG_SCHEMA_INVALID')
        return result, None
    if config['distributionAuthorized'] is not True:
        errors.append('AUTHORIZATION_DECLARATION_REQUIRED')
    if config['host'] != '127.0.0.1':
        errors.append('LOOPBACK_HOST_REQUIRED')
    port = config['port']
    if type(port) is not int or not 1 <= port <= 65535:
        errors.append('PORT_INVALID')
    interval = config['intervalSeconds']
    try:
        interval_valid = type(interval) in (int, float) and math.isfinite(interval) and 0.05 <= interval <= 10
    except OverflowError:
        interval_valid = False
    if not interval_valid:
        errors.append('INTERVAL_INVALID')
    try:
        token = _read(_path(path.parent, config['bearerTokenFile']), 514, secret=True)
        # Permit one conventional final newline, no other whitespace or trimming.
        token = token.removesuffix('\n')
        if not re.fullmatch(r'[A-Za-z0-9_.~-]{32,512}', token):
            raise ValueError('TOKEN_INVALID')
    except (OSError, ValueError, TypeError):
        errors.append('BEARER_TOKEN_FILE_INVALID')
    try:
        catalogue = _json(_path(path.parent, config['catalogueFile']))
        bindings = _json(_path(path.parent, config['bindingsFile']))
        if not isinstance(catalogue, dict) or not catalogue or any(
                not isinstance(k, str) or not k or not isinstance(v, str) or not v
                for k, v in catalogue.items()):
            raise ValueError('CATALOGUE_INVALID')
        validator = QuoteNormalizer(catalogue, bindings)
        result['mappingCount'] = len(validator.instrument_ids)
    except (OSError, ValueError, TypeError, RecursionError) as error:
        code = str(error)
        errors.append(code if code in _MAPPING_ERRORS else 'MAPPING_FILES_INVALID')
    if not errors:
        result['state'] = 'CONFIG_VALID_VENDOR_CHECK_REQUIRED'
        return result, RuntimeConfig(port, interval, token, catalogue, bindings)
    return result, None


def check_config(path):
    """Returns sanitized diagnostics only; no config values or secrets escape."""
    return read_config(path)[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, help='Local JSON configuration file')
    args = parser.parse_args()
    result = check_config(args.config)
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 2 if result['errors'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
