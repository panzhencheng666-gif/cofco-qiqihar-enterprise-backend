package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.SecuritySessionAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exercises the production authorization-code flow against a real HTTP OIDC provider seam. */
class RealOidcJwtIntegrationTest {
    private static final String CLIENT_ID = "browser-session-client";
    private static final String CLIENT_SECRET = "integration-only-secret";
    private static final MockOidcServer OIDC = MockOidcServer.start();
    private static final AccessControl BUSINESS_READ_ACCESS = mock(AccessControl.class);
    private static final SecuritySessionAuditRecorder SESSION_AUDIT = mock(SecuritySessionAuditRecorder.class);
    private static ConfigurableApplicationContext application;
    private static URI applicationBaseUri;

    @BeforeAll
    static void startProductionApplication() {
        SpringApplication springApplication = new SpringApplication(ProductionTestApplication.class);
        springApplication.setWebApplicationType(WebApplicationType.SERVLET);
        application = springApplication.run(
                "--spring.profiles.active=production",
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + OIDC.issuer(),
                "--qiqihar.security.oidc.client-id=" + CLIENT_ID,
                "--qiqihar.security.oidc.client-secret=" + CLIENT_SECRET,
                "--qiqihar.security.oidc.redirect-uri={baseUrl}/login/oauth2/code/enterprise",
                "--qiqihar.security.oidc.post-logout-redirect-uri={baseUrl}/logged-out",
                "--qiqihar.security.oidc.mfa-amr-values=mfa,otp",
                "--qiqihar.security.session-cookie-secure=false",
                "--server.servlet.session.cookie.name=COFCO_SESSION",
                "--server.servlet.session.cookie.secure=false",
                "--spring.main.banner-mode=off");
        int port = ((ServletWebServerApplicationContext) application).getWebServer().getPort();
        applicationBaseUri = URI.create("http://127.0.0.1:" + port);
    }

    @AfterAll
    static void stopTestServers() {
        if (application != null) {
            application.close();
        }
        OIDC.close();
    }

    @BeforeEach
    void clearAuditEvidence() {
        clearInvocations(SESSION_AUDIT);
    }

    @Test
    void realAuthorizationCodeCallbackCreatesMfaBackedServerSession() throws Exception {
        Browser browser = Browser.start();

        HttpResponse<String> callback = browser.login(MockOidcServer.TokenMode.VALID_MFA);
        HttpResponse<String> whoami = browser.get("/api/v1/whoami", Map.of());

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(whoami.statusCode()).isEqualTo(200);
        assertThat(whoami.body()).isEqualTo("oidc-subject");
        assertThat(java.util.stream.Stream.concat(
                        callback.headers().allValues("Set-Cookie").stream(),
                        whoami.headers().allValues("Set-Cookie").stream()).toList())
                .anyMatch(value -> value.startsWith("XSRF-TOKEN=") && value.contains("SameSite=Strict"));
        assertThat(OIDC.discoveryRequests()).isPositive();
        assertThat(OIDC.authorizationRequests()).isPositive();
        assertThat(OIDC.tokenRequests()).isPositive();
        assertThat(OIDC.jwkRequests()).isPositive();
    }

    @Test
    void sessionWithoutApprovedMfaEvidenceIsNotEstablished() throws Exception {
        Browser browser = Browser.start();
        browser.login(MockOidcServer.TokenMode.VALID_PASSWORD_ONLY);

        HttpResponse<String> response = browser.get("/api/v1/whoami", Map.of());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void invalidIssuerCannotCreateServerSession() throws Exception {
        Browser browser = Browser.start();
        browser.login(MockOidcServer.TokenMode.WRONG_ISSUER);

        assertThat(browser.get("/api/v1/whoami", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void invalidSignatureCannotCreateServerSession() throws Exception {
        Browser browser = Browser.start();
        browser.login(MockOidcServer.TokenMode.WRONG_SIGNATURE);

        assertThat(browser.get("/api/v1/whoami", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void expiredIdTokenCannotCreateServerSession() throws Exception {
        Browser browser = Browser.start();
        browser.login(MockOidcServer.TokenMode.EXPIRED);

        assertThat(browser.get("/api/v1/whoami", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void forgedActorAndBearerCannotReplaceTheBrowserSession() throws Exception {
        Browser anonymous = Browser.start();
        assertThat(anonymous.get("/api/v1/whoami", Map.of("X-Actor", "forged-actor")).statusCode())
                .isEqualTo(401);
        assertThat(anonymous.get("/api/v1/whoami", Map.of("Authorization", "Bearer forged")).statusCode())
                .isEqualTo(401);

        Browser authenticated = Browser.start();
        authenticated.login(MockOidcServer.TokenMode.VALID_MFA);
        HttpResponse<String> response = authenticated.get("/api/v1/whoami", Map.of(
                "X-Actor", "forged-actor", "Authorization", "Bearer forged"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("oidc-subject");
    }

    @Test
    void providerBackChannelLogoutRevokesOnlyTheTargetSessionAndIsIdempotent() throws Exception {
        Browser target = Browser.start();
        target.login(MockOidcServer.TokenMode.VALID_MFA);
        String targetProviderSession = OIDC.lastSessionId();
        Browser other = Browser.start();
        other.login(MockOidcServer.TokenMode.VALID_MFA);
        assertThat(target.get("/api/v1/whoami", Map.of()).statusCode()).isEqualTo(200);
        assertThat(other.get("/api/v1/whoami", Map.of()).statusCode()).isEqualTo(200);
        clearInvocations(SESSION_AUDIT);
        String logoutToken = OIDC.logoutToken(targetProviderSession, MockOidcServer.LogoutTokenMode.VALID);

        assertThat(target.backChannelLogout(logoutToken).statusCode()).isEqualTo(200);
        assertThat(target.get("/api/v1/whoami", Map.of()).statusCode()).isEqualTo(401);
        assertThat(other.get("/api/v1/whoami", Map.of()).statusCode()).isEqualTo(200);
        verify(SESSION_AUDIT, times(1)).record(
                eq("oidc-subject"), anyString(), eq("OIDC_BACK_CHANNEL_LOGOUT"), eq("{}"));

        assertThat(target.backChannelLogout(logoutToken).statusCode()).isEqualTo(200);
        assertThat(other.get("/api/v1/whoami", Map.of()).statusCode()).isEqualTo(200);
        verify(SESSION_AUDIT, times(1)).record(
                eq("oidc-subject"), anyString(), eq("OIDC_BACK_CHANNEL_LOGOUT"), eq("{}"));
    }

    @Test
    void invalidBackChannelLogoutTokensFailClosedWithoutRevokingAValidSession() throws Exception {
        Browser browser = Browser.start();
        browser.login(MockOidcServer.TokenMode.VALID_MFA);
        String providerSession = OIDC.lastSessionId();
        assertThat(browser.get("/api/v1/whoami", Map.of()).statusCode()).isEqualTo(200);
        clearInvocations(SESSION_AUDIT);

        for (MockOidcServer.LogoutTokenMode mode : List.of(
                MockOidcServer.LogoutTokenMode.WRONG_SIGNATURE,
                MockOidcServer.LogoutTokenMode.WRONG_ISSUER,
                MockOidcServer.LogoutTokenMode.WRONG_AUDIENCE,
                MockOidcServer.LogoutTokenMode.MISSING_EVENTS,
                MockOidcServer.LogoutTokenMode.WRONG_EVENTS)) {
            assertThat(browser.backChannelLogout(OIDC.logoutToken(providerSession, mode)).statusCode())
                    .as("invalid logout token mode %s", mode)
                    .isEqualTo(400);
            assertThat(browser.get("/api/v1/whoami", Map.of()).statusCode())
                    .as("session remains valid after %s", mode)
                    .isEqualTo(200);
        }
        verify(SESSION_AUDIT, never()).record(
                any(), any(), eq("OIDC_BACK_CHANNEL_LOGOUT"), any());
    }

    private static final class Browser {
        private final HttpClient http;
        private final CookieManager cookies;

        private Browser(HttpClient http,CookieManager cookies) {
            this.http = http;
            this.cookies=cookies;
        }

        static Browser start() {
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            return new Browser(HttpClient.newBuilder()
                    .cookieHandler(cookies)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build(),cookies);
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie->cookie.getName().equals("COFCO_SESSION"))
                    .map(java.net.HttpCookie::getValue).findFirst()
                    .orElseThrow(() -> new AssertionError("Missing COFCO_SESSION cookie; present cookie names: "
                            + cookies.getCookieStore().getCookies().stream()
                                    .map(java.net.HttpCookie::getName).sorted().toList()));
        }

        HttpResponse<String> login(MockOidcServer.TokenMode mode) throws Exception {
            OIDC.nextToken(mode);
            clearInvocations(BUSINESS_READ_ACCESS);
            HttpResponse<String> controlledEntry = get("/api/v1/session/login", Map.of());
            assertThat(controlledEntry.statusCode()).isEqualTo(302);
            verifyNoInteractions(BUSINESS_READ_ACCESS);
            HttpResponse<String> authorizationStart = send(location(controlledEntry), Map.of());
            assertThat(authorizationStart.statusCode()).isEqualTo(302);
            HttpResponse<String> providerAuthorization = send(location(authorizationStart), Map.of());
            assertThat(providerAuthorization.statusCode()).isEqualTo(302);
            return send(location(providerAuthorization), Map.of());
        }

        HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
            return send(applicationBaseUri.resolve(path), headers);
        }

        HttpResponse<String> backChannelLogout(String logoutToken) throws Exception {
            HttpRequest request=HttpRequest.newBuilder(
                            applicationBaseUri.resolve("/logout/connect/back-channel/enterprise"))
                    .header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "logout_token="+URLEncoder.encode(logoutToken,StandardCharsets.UTF_8)))
                    .build();
            return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> send(URI uri, Map<String, String> headers) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
            headers.forEach(request::header);
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        private static URI location(HttpResponse<?> response) {
            String value = response.headers().firstValue("Location").orElseThrow();
            return response.uri().resolve(value);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({ProductionSecurityConfiguration.class, SecurityStartupInvariant.class,
            BusinessReadAuthenticationInterceptor.class, OidcLoginController.class, ProbeController.class})
    static class ProductionTestApplication {
        @Bean
        SecurityPrincipalRepository enterpriseSubjects() {
            return new SecurityPrincipalRepository() {
                @Override
                public Optional<SecurityPrincipal> findEnabled(String subjectId) {
                    return Optional.of(principal(subjectId));
                }

                @Override
                public Optional<SecurityPrincipal> findEnabledByOidcIdentity(
                        String issuer,String providerSubject) {
                    return OIDC.issuer().equals(issuer)&&"oidc-subject".equals(providerSubject)
                            ? Optional.of(principal(providerSubject)) : Optional.empty();
                }

                private SecurityPrincipal principal(String subjectId) {
                    return new SecurityPrincipal(
                            subjectId,subjectId,"TEST_UNIT","测试单位","ACTIVE","ACTIVE",
                            Set.of("BUSINESS_OPERATOR"),List.of(),Set.of("BUSINESS_READ"),Set.of("230202"));
                }
            };
        }

        @Bean
        SecuritySessionAuditRecorder sessionAudit() {
            return SESSION_AUDIT;
        }

        @Bean
        AccessControl businessReadAccessControl() {
            return BUSINESS_READ_ACCESS;
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/whoami")
        String whoami(java.security.Principal principal) {
            return principal.getName();
        }
    }

    private static final class MockOidcServer implements AutoCloseable {
        private static final String KEY_ID = "integration-key";
        private final HttpServer server;
        private final KeyPair signingKey;
        private final KeyPair otherKey;
        private final AtomicInteger discoveryRequests = new AtomicInteger();
        private final AtomicInteger authorizationRequests = new AtomicInteger();
        private final AtomicInteger tokenRequests = new AtomicInteger();
        private final AtomicInteger jwkRequests = new AtomicInteger();
        private final AtomicReference<TokenMode> nextToken = new AtomicReference<>(TokenMode.VALID_MFA);
        private final AtomicReference<String> nonce = new AtomicReference<>();
        private final AtomicReference<String> lastSessionId = new AtomicReference<>();

        private MockOidcServer(HttpServer server, KeyPair signingKey, KeyPair otherKey) {
            this.server = server;
            this.signingKey = signingKey;
            this.otherKey = otherKey;
        }

        static MockOidcServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                MockOidcServer oidc = new MockOidcServer(server, generateKeyPair(), generateKeyPair());
                server.createContext("/.well-known/openid-configuration", oidc::discovery);
                server.createContext("/authorize", oidc::authorize);
                server.createContext("/token", oidc::token);
                server.createContext("/jwks", oidc::jwks);
                server.createContext("/logout", exchange -> redirect(exchange, "/logged-out"));
                server.start();
                return oidc;
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to start test OIDC server", exception);
            }
        }

        void nextToken(TokenMode mode) {
            nextToken.set(mode);
        }

        String issuer() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int discoveryRequests() {
            return discoveryRequests.get();
        }

        int authorizationRequests() {
            return authorizationRequests.get();
        }

        int tokenRequests() {
            return tokenRequests.get();
        }

        int jwkRequests() {
            return jwkRequests.get();
        }

        String lastSessionId() {
            return lastSessionId.get();
        }

        private void discovery(HttpExchange exchange) throws IOException {
            discoveryRequests.incrementAndGet();
            writeJson(exchange, """
                    {"issuer":"%s","jwks_uri":"%s/jwks",
                     "authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",
                     "end_session_endpoint":"%s/logout",
                     "subject_types_supported":["public"],
                     "response_types_supported":["code"],
                     "grant_types_supported":["authorization_code"],
                     "token_endpoint_auth_methods_supported":["client_secret_basic"],
                     "id_token_signing_alg_values_supported":["RS256"]}
                    """.formatted(issuer(), issuer(), issuer(), issuer(), issuer()));
        }

        private void authorize(HttpExchange exchange) throws IOException {
            authorizationRequests.incrementAndGet();
            Map<String, String> parameters = query(exchange.getRequestURI());
            nonce.set(parameters.get("nonce"));
            String location = parameters.get("redirect_uri") + "?code=integration-code&state="
                    + encode(parameters.get("state"));
            redirect(exchange, location);
        }

        private void token(HttpExchange exchange) throws IOException {
            tokenRequests.incrementAndGet();
            TokenMode mode = nextToken.getAndSet(TokenMode.VALID_MFA);
            String sessionId = UUID.randomUUID().toString();
            lastSessionId.set(sessionId);
            writeJson(exchange, """
                    {"access_token":"integration-access-token","token_type":"Bearer","expires_in":300,
                     "scope":"openid profile","id_token":"%s"}
                    """.formatted(idToken(mode, sessionId)));
        }

        private String idToken(TokenMode mode, String sessionId) {
            try {
                Instant now = Instant.now();
                Instant expiresAt = mode == TokenMode.EXPIRED ? now.minusSeconds(300) : now.plusSeconds(300);
                String issuer = mode == TokenMode.WRONG_ISSUER ? "https://other.example.test" : issuer();
                KeyPair key = mode == TokenMode.WRONG_SIGNATURE ? otherKey : signingKey;
                List<String> amr = mode == TokenMode.VALID_PASSWORD_ONLY ? List.of("pwd") : List.of("pwd", "mfa");
                JWTClaimsSet claims = new JWTClaimsSet.Builder()
                        .subject("oidc-subject")
                        .issuer(issuer)
                        .audience(CLIENT_ID)
                        .issueTime(Date.from(now.minusSeconds(5)))
                        .expirationTime(Date.from(expiresAt))
                        .claim("nonce", nonce.get())
                        .claim("amr", amr)
                        .claim("sid", sessionId)
                        .build();
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
                jwt.sign(new RSASSASigner(key.getPrivate()));
                return jwt.serialize();
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to sign test ID token", exception);
            }
        }

        String logoutToken(String sessionId, LogoutTokenMode mode) {
            try {
                Instant now=Instant.now();
                JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                        .subject("oidc-subject")
                        .issuer(mode == LogoutTokenMode.WRONG_ISSUER
                                ? "https://other.example.test" : issuer())
                        .audience(mode == LogoutTokenMode.WRONG_AUDIENCE
                                ? "other-client" : CLIENT_ID)
                        .issueTime(Date.from(now))
                        .jwtID(UUID.randomUUID().toString())
                        .claim("sid", sessionId);
                if (mode != LogoutTokenMode.MISSING_EVENTS) {
                    claims.claim("events", mode == LogoutTokenMode.WRONG_EVENTS
                            ? Map.of("https://example.test/wrong-event", Map.of())
                            : Map.of("http://schemas.openid.net/event/backchannel-logout", Map.of()));
                }
                SignedJWT jwt=new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims.build());
                KeyPair key = mode == LogoutTokenMode.WRONG_SIGNATURE ? otherKey : signingKey;
                jwt.sign(new RSASSASigner(key.getPrivate()));
                return jwt.serialize();
            } catch(Exception exception) {
                throw new IllegalStateException("Unable to sign test logout token",exception);
            }
        }

        private void jwks(HttpExchange exchange) throws IOException {
            jwkRequests.incrementAndGet();
            RSAKey publicJwk = new RSAKey.Builder((RSAPublicKey) signingKey.getPublic())
                    .keyID(KEY_ID)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            writeJson(exchange, new JWKSet(publicJwk).toString());
        }

        private static KeyPair generateKeyPair() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return generator.generateKeyPair();
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to create test RSA key", exception);
            }
        }

        private static Map<String, String> query(URI uri) {
            Map<String, String> parameters = new LinkedHashMap<>();
            if (uri.getRawQuery() == null) {
                return parameters;
            }
            Arrays.stream(uri.getRawQuery().split("&"))
                    .map(part -> part.split("=", 2))
                    .forEach(pair -> parameters.put(decode(pair[0]), pair.length == 2 ? decode(pair[1]) : ""));
            return parameters;
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }

        private static void redirect(HttpExchange exchange, String location) throws IOException {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }

        private static void writeJson(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        enum TokenMode {
            VALID_MFA,
            VALID_PASSWORD_ONLY,
            WRONG_ISSUER,
            WRONG_SIGNATURE,
            EXPIRED
        }

        enum LogoutTokenMode {
            VALID,
            WRONG_SIGNATURE,
            WRONG_ISSUER,
            WRONG_AUDIENCE,
            MISSING_EVENTS,
            WRONG_EVENTS
        }
    }
}
