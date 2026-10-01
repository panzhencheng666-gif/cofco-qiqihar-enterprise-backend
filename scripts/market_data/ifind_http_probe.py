"""Read-only iFinD HTTP quote probe. Never prints credentials or quote values.

The account's right to view a response does not establish permission to
redistribute it. This tool does not write to the application's quote feed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import urllib.error
import urllib.request


ENDPOINT = 'https://quantapi.51ifind.com/api/v1/real_time_quotation'
_CODE = re.compile(r'[A-Za-z0-9_.-]{3,40}\Z')
_TOKEN = re.compile(r'[A-Za-z0-9_.~-]{16,4096}\Z')
_KEY = re.compile(r'[A-Za-z_][A-Za-z0-9_]{0,40}\Z')
_MAX_RESPONSE = 262_144


class ProbeError(Exception):
    """A diagnostic code that never includes a supplier response or secret."""


def _load_token(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > 4097):
            raise ProbeError('TOKEN_FILE_INVALID')
        token = os.read(fd, 4098).decode('ascii').removesuffix('\n')
        if not _TOKEN.fullmatch(token):
            raise ProbeError('TOKEN_FILE_INVALID')
        return token
    except (UnicodeError, OSError) as error:
        raise ProbeError('TOKEN_FILE_INVALID') from error
    finally:
        os.close(fd)


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProbeError('DUPLICATE_RESPONSE_KEY')
        result[key] = value
    return result


def _invalid_constant(_value):
    raise ProbeError('NON_FINITE_RESPONSE')


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise ProbeError('REDIRECT_REFUSED')


def _shape(body):
    """Return only structural diagnostics; price values never leave this boundary."""
    def safe_keys(value):
        return sorted(key if _KEY.fullmatch(key) else 'REDACTED_KEY' for key in value)

    if not isinstance(body, dict):
        raise ProbeError('RESPONSE_NOT_OBJECT')
    errorcode = body.get('errorcode')
    if type(errorcode) is not int:
        raise ProbeError('ERRORCODE_MISSING')
    tables = body.get('tables')
    summary = {
        'supplier': 'iFinD',
        'endpoint': ENDPOINT,
        'apiAccepted': errorcode == 0,
        'errorCode': errorcode,
        'responseKeys': safe_keys(body),
        'tablesType': type(tables).__name__,
    }
    if isinstance(tables, list):
        summary['tableCount'] = len(tables)
        summary['firstTableKeys'] = safe_keys(tables[0]) if tables and isinstance(tables[0], dict) else []
    elif isinstance(tables, dict):
        summary['tableCount'] = len(tables)
        summary['firstTableKeys'] = safe_keys(tables)
    return summary


def probe(token_file, code, *, opener=None):
    if not isinstance(code, str) or not _CODE.fullmatch(code):
        raise ProbeError('CODE_INVALID')
    try:
        token = _load_token(token_file)
    except OSError as error:
        raise ProbeError('TOKEN_FILE_INVALID') from error
    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps({'codes': code, 'indicators': 'latest'}, separators=(',', ':')).encode('ascii'),
        headers={'Content-Type': 'application/json', 'access_token': token, 'ifindlang': 'cn'},
        method='POST',
    )
    opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
    try:
        with opener.open(request, timeout=8) as response:
            if response.status != 200:
                raise ProbeError('HTTP_STATUS_' + str(response.status))
            if 'application/json' not in response.headers.get('Content-Type', '').lower():
                raise ProbeError('CONTENT_TYPE_INVALID')
            raw = response.read(_MAX_RESPONSE + 1)
    except ProbeError:
        raise
    except urllib.error.HTTPError as error:
        raise ProbeError('HTTP_STATUS_' + str(error.code)) from error
    except (OSError, urllib.error.URLError) as error:
        raise ProbeError('NETWORK_UNAVAILABLE') from error
    if len(raw) > _MAX_RESPONSE:
        raise ProbeError('RESPONSE_TOO_LARGE')
    try:
        body = json.loads(raw, object_pairs_hook=_pairs, parse_constant=_invalid_constant)
    except (UnicodeError, ValueError) as error:
        raise ProbeError('RESPONSE_JSON_INVALID') from error
    return _shape(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--token-file', required=True, type=Path)
    parser.add_argument('--code', required=True, help='Supplier-confirmed concrete contract code')
    parser.add_argument('--probe', action='store_true', help='Make one live supplier request')
    args = parser.parse_args()
    if not args.probe:
        print(json.dumps({'state': 'READY_NO_REQUEST', 'endpoint': ENDPOINT}))
        return 0
    try:
        print(json.dumps(probe(args.token_file, args.code), ensure_ascii=False))
        return 0
    except ProbeError as error:
        print(json.dumps({'state': 'PROBE_FAILED', 'reason': str(error)}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
