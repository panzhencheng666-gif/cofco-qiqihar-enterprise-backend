"""Read-only preflight with temporary, synthetic configuration only."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1] / 'market_data'))
from choice_preflight import check_config


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write('catalogue.json', {'test-corn': '元/吨'})
        self.write('bindings.json', [{'code': 'TEST.CORN', 'id': 'test-corn',
                                    'unit': '元/吨', 'verified': True}])
        self.token = self.root / 'bearer.txt'
        self.token.write_text('synthetic-private-token-' + 'x' * 32 + '\n')
        self.token.chmod(0o600)
        self.config = dict(schemaVersion=1, distributionAuthorized=True,
                           host='127.0.0.1', port=19091, intervalSeconds=1,
                           catalogueFile='catalogue.json', bindingsFile='bindings.json',
                           bearerTokenFile='bearer.txt')

    def write(self, name, value):
        (self.root / name).write_text(json.dumps(value))

    def check(self):
        self.write('config.json', self.config)
        return check_config(self.root / 'config.json')

    def test_valid_does_not_claim_supplier_permission(self):
        result = self.check()
        self.assertEqual('CONFIG_VALID_VENDOR_CHECK_REQUIRED', result['state'])
        self.assertFalse(result['readyForLive'])
        self.assertEqual('NOT_CHECKED', result['supplierPermission'])
        self.assertEqual(1, result['mappingCount'])

    def test_authorization_must_be_explicit_boolean(self):
        for value in (False, 'true', 1, None):
            with self.subTest(value=value):
                self.config['distributionAuthorized'] = value
                self.assertIn('AUTHORIZATION_DECLARATION_REQUIRED', self.check()['errors'])

    def test_network_and_interval_fail_closed(self):
        for key, value in [('host', '0.0.0.0'), ('host', 'localhost'),
                           ('port', True), ('port', 0), ('port', 65536),
                           ('intervalSeconds', True), ('intervalSeconds', 0),
                           ('intervalSeconds', 11)]:
            with self.subTest(key=key, value=value):
                old = self.config[key]
                self.config[key] = value
                self.assertEqual('CONFIG_INVALID', self.check()['state'])
                self.config[key] = old

    def test_schema_and_unknown_keys_rejected(self):
        self.config['password'] = 'must-not-be-printed'
        result = self.check()
        self.assertIn('CONFIG_SCHEMA_INVALID', result['errors'])
        self.assertNotIn('must-not-be-printed', json.dumps(result))

    def test_mapping_mismatch_and_duplicates(self):
        row = {'code': 'TEST.CORN', 'id': 'test-corn', 'unit': '美元', 'verified': True}
        self.write('bindings.json', [row])
        self.assertIn('UNIT_MISMATCH', self.check()['errors'])
        row['unit'] = '元/吨'
        self.write('bindings.json', [row, row])
        self.assertIn('DUPLICATE_MAPPING', self.check()['errors'])

    def test_token_permissions_symlink_and_format(self):
        self.token.chmod(0o644)
        self.assertIn('BEARER_TOKEN_FILE_INVALID', self.check()['errors'])
        self.token.chmod(0o600)
        self.token.write_text('bad token')
        self.assertIn('BEARER_TOKEN_FILE_INVALID', self.check()['errors'])
        self.token.rename(self.root / 'actual-token')
        self.token.symlink_to(self.root / 'actual-token')
        self.assertIn('BEARER_TOKEN_FILE_INVALID', self.check()['errors'])

    def test_invalid_json_duplicate_keys_nan_and_oversize(self):
        for raw in ('{"schemaVersion":1,"schemaVersion":1}', '{"port":NaN}',
                    '[' * 2000, ' ' * (1024 * 1024 + 1)):
            (self.root / 'config.json').write_text(raw)
            self.assertEqual('CONFIG_INVALID', check_config(self.root / 'config.json')['state'])

    def test_fifo_is_rejected_without_blocking(self):
        self.token.unlink()
        os.mkfifo(self.token, 0o600)
        script = Path(__file__).parents[1] / 'market_data/choice_preflight.py'
        self.write('config.json', self.config)
        result = subprocess.run([sys.executable, str(script), '--config',
                                 str(self.root / 'config.json')], capture_output=True, timeout=3)
        self.assertEqual(2, result.returncode)
        self.assertIn('BEARER_TOKEN_FILE_INVALID', json.loads(result.stdout)['errors'])

    def test_unverified_and_unknown_instrument_mapping(self):
        for row, expected in [
                ({'code': 'TEST.CORN', 'id': 'test-corn', 'unit': '元/吨', 'verified': False},
                 'VERIFIED_MAPPING_REQUIRED'),
                ({'code': 'TEST.CORN', 'id': 'unknown', 'unit': '元/吨', 'verified': True},
                 'UNKNOWN_INSTRUMENT')]:
            self.write('bindings.json', [row])
            self.assertIn(expected, self.check()['errors'])

    def test_missing_file_and_secret_redaction(self):
        self.config['bearerTokenFile'] = 'sensitive-missing-path'
        result = self.check()
        self.assertNotIn('sensitive-missing-path', json.dumps(result))
        self.assertNotIn('synthetic-private-token', json.dumps(result))
        self.assertFalse(result['readyForLive'])

    def test_cli_exit_codes_and_read_only(self):
        self.check()
        before = {p.name: p.read_bytes() for p in self.root.iterdir()}
        script = Path(__file__).parents[1] / 'market_data/choice_preflight.py'
        result = subprocess.run([sys.executable, str(script), '--config',
                                 str(self.root / 'config.json')], capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(json.loads(result.stdout)['readyForLive'])
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.root.iterdir()})
        result = subprocess.run([sys.executable, str(script), '--config',
                                 str(self.root / 'missing.json')], capture_output=True, timeout=5)
        self.assertEqual(2, result.returncode)
        self.assertEqual(b'', result.stderr)


if __name__ == '__main__':
    unittest.main()
