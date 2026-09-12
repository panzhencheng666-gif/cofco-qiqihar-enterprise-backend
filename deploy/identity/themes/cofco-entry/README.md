# COFCO login entry theme

Scoped to the local client with `attributes.login_theme=cofco-entry`. Set its controlled `baseUrl` to the matching application HTTPS origin, without a trailing slash. The SMS tab uses this administrator-configured URL; no request-supplied return URL is accepted.

`login/login.ftl` is based on the Keycloak 26.7.1 `base` login template (Apache-2.0), preserving native credentials, error handling, passkeys, password visibility and registration links. The login page switches between native password authentication and an embedded SMS form. The registration template retains native credential validation and adds verified employee enrollment. Reference: https://github.com/keycloak/keycloak/blob/26.7.1/themes/src/main/resources/theme/base/login/login.ftl

Install this directory as `/opt/keycloak/themes/cofco-entry` using a persistent read-only container mount for managed operation. Use a content-versioned directory name and update only the dedicated client login_theme when replacing templates, because Keycloak caches resolved templates. The current preview uses a content-versioned copy; its launcher restores the source before preflight. Do not set this as the global realm theme or modify other clients.

Registration uses the OIDC `prompt=create` parameter through Spring's authorization request resolver, preserving state, nonce, PKCE and the exact configured callback. Reference: https://www.keycloak.org/docs/latest/server_admin/ (Registration or Reset credentials requested by client).

The application exposes `/api/v1/identity/registration-entry/*` to the configured issuer origin only, with credentials and normal CSRF protection. The draft never contains a password. A verified phone and fixed ordinary assignment remain in the browser session for ten minutes; native credential retries retain that original expiry. OIDC completion must match the username and phone claim. An already authenticated unbound identity can finish the same form using its existing credentials. Reauthentication rotates the session and clears unapproved authentication without discarding the draft. Existing bound administrators bypass enrollment.

`register.html` is now only a legacy redirect. `phone.html` redirects ordinary SMS login into the combined login screen; BIND/MERGE account services retain their existing authenticated flow.
