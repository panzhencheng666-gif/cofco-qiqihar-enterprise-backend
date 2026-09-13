import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class ManagedIdentityConfigTest(unittest.TestCase):
    def run_config(self, content, mode=0o600, extra=None):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            config = folder / 'runtime.env'
            config.write_text(content)
            config.chmod(mode)
            probe = folder / 'probe.sh'
            probe.write_text('#!/bin/bash\nprintf "profile=%s\\n" "${COFCO_ENTERPRISE_AUTH_MODE:-local}"\n')
            probe.chmod(0o700)
            env = {k: v for k, v in os.environ.items() if not k.startswith(('QIQIHAR_', 'SPRING_', 'COFCO_ENTERPRISE_'))}
            env['COFCO_ENTERPRISE_LOCAL_ENV_FILE'] = str(config)
            env.update(extra or {})
            return subprocess.run(['bash', str(ROOT / 'scripts/run-local-launch-agent.sh'), str(probe)], env=env, text=True, capture_output=True)

    def test_missing_oidc_configuration_fails_before_start(self):
        result = self.run_config('COFCO_ENTERPRISE_AUTH_MODE=oidc\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('QIQIHAR_OIDC_ISSUER_URI', result.stderr)
        self.assertNotIn('profile=', result.stdout)

    def valid_config(self):
        import base64
        values = {
            'COFCO_ENTERPRISE_AUTH_MODE': 'oidc',
            'QIQIHAR_OIDC_ISSUER_URI': 'https://identity.example.test/realms/enterprise',
            'QIQIHAR_OIDC_CLIENT_ID': 'enterprise', 'QIQIHAR_OIDC_CLIENT_SECRET': 'test-only',
            'QIQIHAR_OIDC_REDIRECT_URI': 'https://app.example.test/login/oauth2/code/enterprise',
            'QIQIHAR_OIDC_POST_LOGOUT_REDIRECT_URI': 'https://app.example.test/',
            'QIQIHAR_OIDC_MFA_AMR_VALUES': 'otp',
            'QIQIHAR_IDENTITY_INVITATION_ENCRYPTION_KEY': base64.urlsafe_b64encode(bytes(32)).decode(),
            'QIQIHAR_IDENTITY_MANAGEMENT_URL': 'https://identity.example.test/account',
            'QIQIHAR_IDENTITY_DELIVERY_ENDPOINT': 'https://delivery.example.test/send',
            'QIQIHAR_IDENTITY_DELIVERY_BEARER_TOKEN': 'test-only',
            'QIQIHAR_IDENTITY_ACTIVATION_URL': 'https://app.example.test/activate',
            'QIQIHAR_IDENTITY_DELIVERY_WORKER_ENABLED': 'true',
            'QIQIHAR_DB_URL': 'jdbc:postgresql://127.0.0.1:55435/qiqihar_enterprise_test',
            'QIQIHAR_DB_USERNAME': 'app', 'QIQIHAR_FLYWAY_USERNAME': 'migration',
            'QIQIHAR_EVENT_CONSUMER_REGISTRAR_DB_USERNAME': 'registrar',
        }
        return values

    def test_complete_configuration_reaches_launcher(self):
        content = ''.join(f'{k}={v}\n' for k, v in self.valid_config().items())
        result = self.run_config(content)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, 'profile=oidc\n')

    def test_insecure_or_incomplete_configuration_never_starts(self):
        cases = [
            ('QIQIHAR_OIDC_ISSUER_URI', 'http://identity.example.test'),
            ('QIQIHAR_OIDC_MFA_AMR_VALUES', ''),
            ('QIQIHAR_IDENTITY_INVITATION_ENCRYPTION_KEY', 'invalid'),
            ('QIQIHAR_IDENTITY_DELIVERY_WORKER_ENABLED', 'false'),
            ('QIQIHAR_FLYWAY_USERNAME', 'app'),
            ('QIQIHAR_OIDC_REDIRECT_URI', 'https://app.example.test/wrong'),
        ]
        for key, value in cases:
            with self.subTest(key=key):
                values = self.valid_config()
                values[key] = value
                result = self.run_config(''.join(f'{k}={v}\n' for k, v in values.items()))
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('profile=', result.stdout)

    def test_inherited_profile_cannot_bypass_oidc(self):
        content = ''.join(f'{k}={v}\n' for k, v in self.valid_config().items())
        result = self.run_config(content, extra={'SPRING_PROFILES_INCLUDE': 'local'})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('profile=', result.stdout)

    def test_shell_text_is_not_executed(self):
        result = self.run_config('QIQIHAR_DB_PASSWORD=$(exit 99)\n')
        self.assertEqual(result.returncode, 0)

    def test_local_configuration_still_loads(self):
        self.assertEqual(self.run_config('QIQIHAR_DB_USERNAME=cofco_app\n').stdout, 'profile=local\n')

    def test_unknown_key_does_not_echo_its_value(self):
        result = self.run_config('UNKNOWN=secret-sentinel\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('secret-sentinel', result.stdout + result.stderr)

    def test_readable_secret_file_is_rejected(self):
        self.assertNotEqual(self.run_config('', 0o644).returncode, 0)

if __name__ == '__main__':
    unittest.main()
