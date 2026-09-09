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

## Phone on the first registration form

Create `phone-registration-scope.json` first, then merge the single attribute from `phone-registration-attribute.json` into the realm user profile without replacing other attributes. Keycloak validates that a selector's scope already exists. Assign this scope as a default only to the intended phone-registration client. Existing clients keep their form unchanged.

The optional phone field uses HTML tel input and server-side mainland-mobile format validation. Keycloak stores it as `phone_number`; the scope emits the draft in the ID token, never a verified-phone claim. The authenticated `/api/v1/identity/registration/phone` endpoint returns only a valid draft for prefilling the employee form. The existing REGISTER SMS challenge is still mandatory before creating the business phone binding. Typing a phone number does not establish ownership.

## Enrollment baseline and administrator routing repair

The local 29444 preview must keep its reference work units, unit scopes and complete region catalog after test cleanup. These are persistent reference data, not phone-test fixtures. The missing catalog previously produced empty work-unit/region controls. Reference metadata was read from the existing 5432 business database in a read-only transaction and only missing rows were inserted into isolated 55435. No business sample data or user region assignments were copied with the catalog.

The preview's admin binding was also missing. Its existing approved source binding was matched against the actual IdP admin subject before copying the account, SYSTEM_ADMIN role and approved scopes into 55435. No administrator rights are inferred from a submitted username. Admin does not require phone registration; an unbound reserved admin receives an explicit configuration error rather than employee enrollment. A bound administrator with valid password proof goes directly to the system.

Run `scripts/verify-registration-baseline.sql` on the preview database before starting the preview backend. The local launcher now performs this check. Never delete the shared QIQIHAR_BUSINESS unit during disposable-fixture cleanup.

The registration bootstrap returns whether the authenticated OIDC identity already has a business binding; established accounts skip the employee form. Do not probe the protected session/me API for an unbound identity: that endpoint intentionally invalidates unauthorized business sessions. New employees continue to step 2, titled 完善员工资料. Empty catalogs disable SMS/submission, explain the problem and offer a retry that preserves entered data.

Bounded validation: 9 backend routing/security tests passed; a real disposable OIDC login loaded and selected all six units (232/15/15/7/11/12 available regions); browser UI regression covers empty unit/region results, retry and existing-account redirect. The user's actual in-app browser displayed admin with platform-management permissions after password login. The disposable identity/audits were removed; user-owned accounts and optional phone bindings were preserved. Production runtime remains unchanged.
