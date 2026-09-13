#!/usr/bin/env python3
"""Verify the configured Keycloak callback before exposing a local login entry.

Uses a fresh, unauthenticated authorization request; never submits credentials or
creates users. Requires the same exact client/callback pair as the application.
"""
import argparse
import base64
import hashlib
import secrets
import ssl
import urllib.error
import urllib.parse
import urllib.request


def verify(issuer, client_id, redirect_uri, ca_file=None):
    for value in (issuer, redirect_uri):
        uri = urllib.parse.urlsplit(value)
        if uri.scheme != 'https' or not uri.hostname or uri.username or uri.password or uri.fragment:
            raise ValueError('Issuer and callback must be controlled HTTPS URLs')
    if not redirect_uri.endswith('/login/oauth2/code/enterprise'):
        raise ValueError('Unexpected enterprise callback path')
    context = ssl.create_default_context(cafile=ca_file)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(secrets.token_bytes(32)).digest()).decode().rstrip('=')
    for prompt in ('login', 'create'):
        params = dict(client_id=client_id, redirect_uri=redirect_uri, response_type='code',
                      scope='openid', state=secrets.token_urlsafe(24), nonce=secrets.token_urlsafe(24),
                      code_challenge=challenge, code_challenge_method='S256', prompt=prompt)
        url = issuer.rstrip('/') + '/protocol/openid-connect/auth?' + urllib.parse.urlencode(params)
        try:
            with urllib.request.urlopen(url, context=context, timeout=20) as response:
                html = response.read().decode('utf-8')
        except urllib.error.HTTPError as error:
            raise ValueError(f'OIDC {prompt} rejected (HTTP {error.code}); check client ID, exact redirect URI and registration enablement') from None
        required = 'kc-form-login' if prompt == 'login' else 'kc-register-form'
        if required not in html:
            raise ValueError(f'OIDC {prompt} did not render the expected form; entry must remain unavailable')
    return True


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--issuer', required=True)
    parser.add_argument('--client-id', required=True)
    parser.add_argument('--redirect-uri', required=True)
    parser.add_argument('--ca-file')
    args = parser.parse_args()
    try:
        verify(args.issuer, args.client_id, args.redirect_uri, args.ca_file)
    except (ValueError, urllib.error.URLError) as error:
        raise SystemExit(str(error))
    print('OIDC password and direct registration forms verified with exact callback')
