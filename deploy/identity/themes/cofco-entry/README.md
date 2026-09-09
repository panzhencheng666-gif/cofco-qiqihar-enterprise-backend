# COFCO login entry theme

Scoped to the local client with `attributes.login_theme=cofco-entry`. Set its controlled `baseUrl` to the matching application HTTPS origin, without a trailing slash. The SMS tab uses this administrator-configured URL; no request-supplied return URL is accepted.

`login/login.ftl` is based on the Keycloak 26.7.1 `base` login template (Apache-2.0), preserving native credentials, error handling, passkeys, password visibility and registration links. Only the login-method navigation is added. Reference: https://github.com/keycloak/keycloak/blob/26.7.1/themes/src/main/resources/theme/base/login/login.ftl

Install this directory as `/opt/keycloak/themes/cofco-entry` using a persistent read-only container mount for managed operation. For the current running local preview it has been copied into the container; the preview launcher restores it before its preflight when started. Do not set this as the global realm theme or modify other clients.

Registration uses the OIDC `prompt=create` parameter through Spring's authorization request resolver, preserving state, nonce, PKCE and the exact configured callback. Reference: https://www.keycloak.org/docs/latest/server_admin/ (Registration or Reset credentials requested by client).
