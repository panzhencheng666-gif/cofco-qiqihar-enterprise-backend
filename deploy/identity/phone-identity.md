# Phone identity activation

This change adds Aliyun PNVS phone verification to ordinary OIDC registration, SMS session login, binding and atomic account merge. Password login remains available. SMS sessions use an explicit PhoneAuthenticationToken and are not advertised as password/MFA or fabricated OIDC tokens.

## Configuration

- Apply Flyway V186 with the existing migration role before enabling the endpoints.
- Set QIQIHAR_SMS_ENABLED=true and QIQIHAR_SMS_CREDENTIALS_FILE to the protected JSON credential file (accessKeyId/accessKeySecret; mode 0600). Never place the credentials in source or browser assets.
- QIQIHAR_SMS_SIGN_NAME defaults to the approved PNVS signature. Only SendSmsVerifyCode and CheckSmsVerifyCode are needed. Code validity is five minutes, at most five attempts; sending is limited by phone and requesting address. Provider auto-retry is disabled.
- If the JVM uses a local OIDC trust store, retain the JDK public CA roots as well as the local CA. A local-CA-only trust store fails Aliyun TLS. Never disable verification.
- The OIDC client must emit the real auth_time claim in ID tokens. On Keycloak 26, assign the standard basic client scope, including its AUTH_TIME session-note mapper, as a default client scope. Fresh login requests also send prompt=login and max_age=0. Missing/stale auth_time fails closed for bind/merge.
- Serve public/register.html and public/phone.html from the built frontend. Remove any older proxy override that serves a private register.html instead of the built file.
- Use the configured existing HTTPS callback for the normal client; temporary acceptance client callbacks are not production configuration.

## Transaction and identity boundaries

Registration requires an authenticated OIDC identity plus a REGISTER challenge tied to the same session. The server grants only BUSINESS_OPERATOR. Each phone and business account has at most one binding.

Bind and merge require a fresh original OIDC login (ten-minute window) plus the purpose-bound phone challenge. Merge targets the authenticated original account, never an input username or display-name match. The preview expires after five minutes and is tied to its session and account/region snapshot. Commit locks allocations, checks third-party conflicts, retains only the selected region set, preserves original roles, revokes the phone account, transfers its phone binding, synchronizes affected sample maintainers, records history and invalidates both identities' sessions. Access scopes do not transfer unrelated third-party sample ownership. Disabled grants remain historical and do not reserve regions; reactivation rechecks exclusivity. The literal admin allocation exception remains.

## Bounded verification

PhoneIdentityIntegrationTest is opt-in with -Dqiqihar.phone.acceptance=true and connects only to 127.0.0.1:55435/qiqihar_enterprise_test. Run it through a JUnit launcher with launcher-session and test-execution auto-listeners disabled, as required by the local acceptance environment; do not invoke the default full Maven test lifecycle. Catalog fixture setup uses a transaction-local mode only on the isolated database; service calls use ordinary connections and allocation triggers. The test cleans its exact prefixed rows.

2026-09-09 evidence: real Aliyun send/check/login completed in a trusted browser with the user's authorized number; registration and merge were additionally exercised through real OIDC with a test-only gateway outside the packaged application. Mobile login was checked at 390x844. Test clients/users and database fixtures are disposable; no cloud deployment or existing managed runtime replacement is part of this acceptance.

## Login entry regression (2026-09-09)

The 29444 preview previously reused a client whose only allowed callback was on 29443; Keycloak correctly rejected it with HTTP 400 `redirect_uri`. The preview now uses its own `cofco-phone-local` client and exact `https://localhost:29444/login/oauth2/code/enterprise` callback. Never fix this by allowing wildcard callback URLs.

Before exposing this Keycloak-based entry, run `python3 scripts/verify-oidc-entry.py --issuer <issuer> --client-id <client> --redirect-uri <exact-callback> --ca-file <trusted-CA-file>`. It opens fresh password and registration authorization requests without logging in, sending SMS or creating accounts, and fails on callback rejection or missing forms. The local preview launcher runs this check before starting the backend. Keep the frontend unavailable when it fails.

Run the bounded browser regression from the frontend with `node scripts/verify-identity-entry.mjs https://localhost:29444`. It checks password/SMS selection, direct registration (including the old register.html bookmark), mobile overflow and repeated login/refresh. Optional `IDENTITY_ENTRY_EVIDENCE_DIR` writes screenshots and a JSON result outside the repository. No credentials are submitted and no SMS is sent.

The preview uses isolated database 55435. These entry checks do not replace acceptance against the managed business runtime. Existing 29443 and production client configuration remain unchanged.
