#!/usr/bin/env python3
"""Fail before process startup; print key names only, never supplied values."""
import base64
import os
from urllib.parse import urlsplit


def validate(env):
    mode = env.get('COFCO_ENTERPRISE_AUTH_MODE', 'local')
    if mode not in ('local', 'oidc'):
        raise ValueError('COFCO_ENTERPRISE_AUTH_MODE must be local or oidc')
    if mode == 'local':
        return
    required = (
        'QIQIHAR_OIDC_ISSUER_URI', 'QIQIHAR_OIDC_CLIENT_ID', 'QIQIHAR_OIDC_CLIENT_SECRET',
        'QIQIHAR_OIDC_REDIRECT_URI', 'QIQIHAR_OIDC_POST_LOGOUT_REDIRECT_URI',
        'QIQIHAR_IDENTITY_INVITATION_ENCRYPTION_KEY', 'QIQIHAR_IDENTITY_MANAGEMENT_URL',
        'QIQIHAR_IDENTITY_DELIVERY_ENDPOINT', 'QIQIHAR_IDENTITY_DELIVERY_BEARER_TOKEN',
        'QIQIHAR_IDENTITY_ACTIVATION_URL', 'QIQIHAR_DB_URL', 'QIQIHAR_DB_USERNAME',
        'QIQIHAR_FLYWAY_USERNAME', 'QIQIHAR_EVENT_CONSUMER_REGISTRAR_DB_USERNAME',
    )
    for key in required:
        if not env.get(key, '').strip():
            raise ValueError(f'{key} is required for managed OIDC')
    for key in required + (
        'QIQIHAR_OIDC_AUTHORIZATION_URI', 'QIQIHAR_OIDC_TOKEN_URI',
        'QIQIHAR_OIDC_JWK_SET_URI', 'QIQIHAR_OIDC_USER_INFO_URI', 'QIQIHAR_OIDC_END_SESSION_URI',
    ):
        if key.endswith(('_URI', '_URL', '_ENDPOINT')) and key != 'QIQIHAR_DB_URL' and env.get(key):
            try:
                url = urlsplit(env[key])
                valid = url.scheme == 'https' and url.hostname and not url.username and not url.password and not url.fragment
            except ValueError:
                valid = False
            if not valid:
                raise ValueError(f'{key} must be controlled HTTPS without userinfo or fragment')
    if not env['QIQIHAR_OIDC_REDIRECT_URI'].endswith('/login/oauth2/code/enterprise'):
        raise ValueError('QIQIHAR_OIDC_REDIRECT_URI has an invalid callback path')
    if not any(env.get(k, '').strip() for k in ('QIQIHAR_OIDC_MFA_AMR_VALUES', 'QIQIHAR_OIDC_MFA_ACR_VALUES')):
        raise ValueError('QIQIHAR_OIDC_MFA_AMR_VALUES or QIQIHAR_OIDC_MFA_ACR_VALUES is required')
    try:
        key = env['QIQIHAR_IDENTITY_INVITATION_ENCRYPTION_KEY']
        valid_key = len(base64.b64decode(key + '=' * (-len(key) % 4), altchars=b'-_', validate=True)) == 32
    except ValueError:
        valid_key = False
    if not valid_key:
        raise ValueError('QIQIHAR_IDENTITY_INVITATION_ENCRYPTION_KEY must encode 32 bytes')
    if env.get('QIQIHAR_IDENTITY_DELIVERY_WORKER_ENABLED') != 'true':
        raise ValueError('QIQIHAR_IDENTITY_DELIVERY_WORKER_ENABLED must be true')
    for key, safe in (('QIQIHAR_SESSION_COOKIE_SECURE', 'true'), ('QIQIHAR_IDENTITY_PUBLIC_SELF_REGISTRATION_ENABLED', 'false')):
        if env.get(key, safe) != safe:
            raise ValueError(f'{key} has an unsafe value')
    for key in env:
        if key.startswith('SPRING_') and env[key]:
            raise ValueError(f'{key} is not allowed in managed OIDC')
    for key in ('SPRING_PROFILES_ACTIVE', 'SPRING_PROFILES_INCLUDE', 'SPRING_APPLICATION_JSON',
                'SPRING_CONFIG_LOCATION', 'SPRING_CONFIG_ADDITIONAL_LOCATION',
                'QIQIHAR_SECURITY_TRUSTED_SUBJECT_HEADER'):
        if env.get(key):
            raise ValueError(f'{key} is not allowed in managed OIDC')
    users = [env[k] for k in ('QIQIHAR_DB_USERNAME', 'QIQIHAR_FLYWAY_USERNAME', 'QIQIHAR_EVENT_CONSUMER_REGISTRAR_DB_USERNAME')]
    if len(set(users)) != 3:
        raise ValueError('Runtime, Flyway and consumer registrar database accounts must be distinct')


if __name__ == '__main__':
    try:
        validate(os.environ)
    except ValueError as error:
        raise SystemExit(str(error))
