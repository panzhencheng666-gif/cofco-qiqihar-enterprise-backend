import io
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'market_data'))
from ifind_http_probe import ProbeError, _load_token, _shape, probe


class _Response:
    status = 200
    headers = {'Content-Type': 'application/json; charset=utf-8'}

    def __init__(self, payload):
        self._body = io.BytesIO(payload)

    def read(self, size):
        return self._body.read(size)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None


class _Opener:
    def __init__(self, payload):
        self.payload = payload
        self.request = None

    def open(self, request, timeout):
        self.request = request
        assert timeout == 8
        return _Response(self.payload)


class IfindHttpProbeTest(unittest.TestCase):
    def test_token_file_must_be_private(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / 'token'
            path.write_text('a' * 32)
            path.chmod(0o644)
            with self.assertRaisesRegex(ProbeError, 'TOKEN_FILE_INVALID'):
                _load_token(path)
            path.chmod(0o600)
            self.assertEqual(_load_token(path), 'a' * 32)

    def test_request_uses_fixed_supplier_endpoint_and_never_returns_price(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / 'token'
            path.write_text('a' * 32)
            path.chmod(0o600)
            opener = _Opener(json.dumps({'errorcode': 0, 'tables': [
                {'thscode': 'C2601.DCE', 'table': {'latest': [1234.5]}}]}).encode())
            result = probe(path, 'C2601.DCE', opener=opener)
            self.assertTrue(result['apiAccepted'])
            self.assertEqual(result['firstTableKeys'], ['table', 'thscode'])
            self.assertNotIn('1234.5', json.dumps(result))
            self.assertEqual(opener.request.full_url,
                             'https://quantapi.51ifind.com/api/v1/real_time_quotation')
            self.assertEqual(json.loads(opener.request.data),
                             {'codes': 'C2601.DCE', 'indicators': 'latest'})

    def test_rejects_untrusted_code_and_missing_errorcode(self):
        with self.assertRaisesRegex(ProbeError, 'CODE_INVALID'):
            probe('/missing', 'https://evil.example/')
        with self.assertRaisesRegex(ProbeError, 'ERRORCODE_MISSING'):
            _shape({'tables': []})

    def test_response_key_values_are_redacted(self):
        result = _shape({'errorcode': 0, 'tables': [{'secret:12345': 2}]})
        self.assertEqual(result['firstTableKeys'], ['REDACTED_KEY'])


if __name__ == '__main__':
    unittest.main()
