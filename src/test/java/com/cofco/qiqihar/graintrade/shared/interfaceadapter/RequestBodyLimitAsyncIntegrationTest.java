package com.cofco.qiqihar.graintrade.shared.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = {GrainTradeApplication.class, RequestBodyLimitAsyncIntegrationTest.ProbeConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.servlet.context-path=/graintrade",
            "qiqihar.security.test-default-subject=production-tester"
        })
@UsesProtectedTestDatabase
class RequestBodyLimitAsyncIntegrationTest {
    private static final int JSON_BODY_LIMIT = 1024 * 1024;
    private static final String VENDOR_JSON = "application/merge-patch+json";

    @LocalServerPort int port;
    @Autowired DataSource dataSource;

    @Test
    void chunkedAsyncReadsRespectTheExactBoundaryAndReturnStable413() throws Exception {
        HttpResponse<String> exact = send(
                "/graintrade/api/v1/task5-async-input-probe", jsonStringBody(JSON_BODY_LIMIT), VENDOR_JSON);
        assertThat(exact.statusCode()).isEqualTo(HttpServletResponse.SC_OK);

        HttpResponse<String> oversized = send(
                "/graintrade/api/v1/task5-async-input-probe", jsonStringBody(JSON_BODY_LIMIT + 1), VENDOR_JSON);
        assertThat(oversized.statusCode()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        assertThat(oversized.body())
                .contains("\"code\":\"REQUEST_BODY_TOO_LARGE\"")
                .contains("\"traceId\":");
    }

    @Test
    void oversizedVendorJsonAtTheRealProductionEntryDoesNotCreateARecord() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        long before = jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single();
        byte[] oversized = ("""
                {"productCode":"%s","objectTypeCode":"FARMER","regionCode":"230200",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"1","yieldPerMuKilograms":"1",
                 "quality":{},"costs":{},"insurance":{},"subsidies":{}}
                """).formatted("a".repeat(JSON_BODY_LIMIT)).getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = send(
                "/graintrade/api/v1/production-records", oversized, VENDOR_JSON);

        assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        assertThat(response.body()).contains("\"code\":\"REQUEST_BODY_TOO_LARGE\"");
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
                .query(Long.class).single()).isEqualTo(before);
    }

    private HttpResponse<String> send(String path, byte[] body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] jsonStringBody(int byteLength) {
        return ("\"" + "a".repeat(byteLength - 2) + "\"").getBytes(StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        AsyncInputProbeController asyncInputProbeController() {
            return new AsyncInputProbeController();
        }
    }

    @RestController
    static class AsyncInputProbeController {
        @PostMapping("/api/v1/task5-async-input-probe")
        void accept(HttpServletRequest request, HttpServletResponse response) {
            AsyncContext asyncContext = request.startAsync();
            asyncContext.start(() -> {
                try {
                    int bytes = asyncContext.getRequest().getInputStream().readAllBytes().length;
                    if (!response.isCommitted()) {
                        response.setStatus(HttpServletResponse.SC_OK);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.getWriter().write("{\"data\":" + bytes + "}");
                    }
                } catch (Exception ignored) {
                    // The request limit owns the stable error response.
                } finally {
                    try {
                        asyncContext.complete();
                    } catch (IllegalStateException ignored) {
                        // The request limit may already have completed the async cycle.
                    }
                }
            });
        }
    }
}
