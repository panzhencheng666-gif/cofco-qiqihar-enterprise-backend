package com.cofco.qiqihar.riskintelligence.security;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpRiskBusinessSessionClientTest {
    private HttpServer server;
    private HttpRiskBusinessSessionClient client;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> cookie = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session/me", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
        client = new HttpRiskBusinessSessionClient(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/session/me");
    }
    @AfterEach void stop() { server.stop(0); }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"regionCodes\":[]", ",\"regionCodes\":null"})
    void emptyScopeCannotBeExpandedByClientHeaders(String regions) throws Exception {
        body.set("{\"data\":{\"subjectId\":\"employee\",\"permissions\":[\"BUSINESS_READ\"],\"rootAdministrator\":false" + regions + "}}");
        mvc(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"*\"]", "[\"230221\",\"*\"]", "\"230221\"", "[230221]", "[null]", "[\" 230221\"]", "[\"230221%\"]"})
    void rejectsMalformedScope(String regions) {
        body.set("{\"data\":{\"subjectId\":\"employee\",\"permissions\":[\"BUSINESS_READ\"],\"regionCodes\":" + regions + "}}");
        assertThatThrownBy(() -> client.authenticate("session=fixture"))
                .isInstanceOf(RiskSessionValidationUnavailableException.class);
    }

    @Test void stringTrueCannotGrantRoot() {
        body.set("{\"data\":{\"subjectId\":\"employee\",\"rootAdministrator\":\"true\",\"regionCodes\":[]}}");
        assertThatThrownBy(() -> client.authenticate("session=fixture"))
                .isInstanceOf(RiskSessionValidationUnavailableException.class);
    }

    @Test void explicitRootRetainsAccess() throws Exception {
        body.set("{\"data\":{\"subjectId\":\"employee\",\"rootAdministrator\":true,\"regionCodes\":[]}}");
        mvc(200);
    }

    @Test void validScopedSessionUsesAuthoritativeActor() throws Exception {
        body.set("{\"data\":{\"subjectId\":\"employee\",\"permissions\":[\"BUSINESS_READ\"],\"regionCodes\":[\"230221\"]}}");
        mvc(200);
        assertThat(cookie.get()).isEqualTo("session=fixture");
    }

    private void mvc(int expected) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .addFilters(new RiskBusinessSessionFilter(client)).build();
        var result = mvc.perform(get("/api/v1/risk/probe").header("Cookie", "session=fixture")
                .header("X-Actor", "root").header("X-Region-Codes", "*")
                .header("X-Root-Administrator", "true")).andExpect(status().is(expected));
        if (expected == 200) result.andExpect(content().string("employee"));
    }

    @RestController static class Probe {
        @GetMapping("/api/v1/risk/probe") String read(HttpServletRequest request) {
            return RiskBusinessSession.require(request).subjectId();
        }
    }
}
