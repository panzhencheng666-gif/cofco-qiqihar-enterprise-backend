package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RealOidcJwtIntegrationTest {
    private static final MockOidcServer OIDC = MockOidcServer.start();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static ConfigurableApplicationContext application;
    private static int applicationPort;

    @BeforeAll
    static void startProductionApplication() {
        SpringApplication springApplication = new SpringApplication(ProductionTestApplication.class);
        springApplication.setWebApplicationType(WebApplicationType.SERVLET);
        application = springApplication.run(
                "--spring.profiles.active=production",
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + OIDC.issuer(),
                "--spring.main.banner-mode=off");
        applicationPort = ((ServletWebServerApplicationContext) application).getWebServer().getPort();
    }

    @AfterAll
    static void stopTestServers() {
        if (application != null) {
            application.close();
        }
        OIDC.close();
    }

    @Test
    void validSignedTokenUsesSubjectAfterRealDiscoveryAndJwkRetrieval() throws Exception {
        HttpResponse<String> response = get(OIDC.token("oidc-subject", OIDC.issuer(),
                Instant.now().plusSeconds(300), OIDC.signingKey()), null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("oidc-subject");
        assertThat(OIDC.discoveryRequests()).isPositive();
        assertThat(OIDC.jwkRequests()).isPositive();
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        HttpResponse<String> response = get(OIDC.token("wrong-issuer", "https://other.example.test",
                Instant.now().plusSeconds(300), OIDC.signingKey()), null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void wrongSignatureIsRejected() throws Exception {
        KeyPair otherKey = MockOidcServer.generateKeyPair();
        HttpResponse<String> response = get(OIDC.token("wrong-signature", OIDC.issuer(),
                Instant.now().plusSeconds(300), otherKey), null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        HttpResponse<String> response = get(OIDC.token("expired", OIDC.issuer(),
                Instant.now().minusSeconds(300), OIDC.signingKey()), null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void actorHeaderCannotReplaceBearerToken() throws Exception {
        HttpResponse<String> response = get(null, "forged-actor");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void actorHeaderCannotOverrideAuthenticatedJwtSubject() throws Exception {
        HttpResponse<String> response = get(OIDC.token("signed-subject", OIDC.issuer(),
                Instant.now().plusSeconds(300), OIDC.signingKey()), "forged-actor");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("signed-subject");
    }

    private static HttpResponse<String> get(String bearerToken, String actor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + applicationPort + "/api/v1/whoami"));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        if (actor != null) {
            request.header("X-Actor", actor);
        }
        return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({ProductionSecurityConfiguration.class, SecurityStartupInvariant.class, ProbeController.class})
    static class ProductionTestApplication {
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
        private final AtomicInteger discoveryRequests = new AtomicInteger();
        private final AtomicInteger jwkRequests = new AtomicInteger();

        private MockOidcServer(HttpServer server, KeyPair signingKey) {
            this.server = server;
            this.signingKey = signingKey;
        }

        static MockOidcServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                MockOidcServer oidc = new MockOidcServer(server, generateKeyPair());
                server.createContext("/.well-known/openid-configuration", oidc::discovery);
                server.createContext("/jwks", oidc::jwks);
                server.start();
                return oidc;
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to start test OIDC server", exception);
            }
        }

        static KeyPair generateKeyPair() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return generator.generateKeyPair();
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to create test RSA key", exception);
            }
        }

        String issuer() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        KeyPair signingKey() {
            return signingKey;
        }

        int discoveryRequests() {
            return discoveryRequests.get();
        }

        int jwkRequests() {
            return jwkRequests.get();
        }

        String token(String subject, String issuer, Instant expiresAt, KeyPair keyPair) {
            try {
                JWTClaimsSet claims = new JWTClaimsSet.Builder()
                        .subject(subject)
                        .issuer(issuer)
                        .issueTime(Date.from(Instant.now().minusSeconds(5)))
                        .expirationTime(Date.from(expiresAt))
                        .build();
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
                jwt.sign(new RSASSASigner(keyPair.getPrivate()));
                return jwt.serialize();
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to sign test JWT", exception);
            }
        }

        private void discovery(HttpExchange exchange) throws IOException {
            discoveryRequests.incrementAndGet();
            writeJson(exchange, """
                    {"issuer":"%s","jwks_uri":"%s/jwks",
                     "authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",
                     "subject_types_supported":["public"],
                     "id_token_signing_alg_values_supported":["RS256"]}
                    """.formatted(issuer(), issuer(), issuer(), issuer()));
        }

        private void jwks(HttpExchange exchange) throws IOException {
            jwkRequests.incrementAndGet();
            RSAKey publicJwk = new RSAKey.Builder((RSAPublicKey) signingKey.getPublic())
                    .keyID(KEY_ID)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            writeJson(exchange, new JWKSet(publicJwk).toString());
        }

        private static void writeJson(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().put("Content-Type", java.util.List.of("application/json"));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
