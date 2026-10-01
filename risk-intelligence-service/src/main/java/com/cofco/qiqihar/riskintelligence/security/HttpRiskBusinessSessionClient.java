package com.cofco.qiqihar.riskintelligence.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class HttpRiskBusinessSessionClient implements RiskBusinessSessionClient {
    private final URI sessionUri;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    HttpRiskBusinessSessionClient(
            ObjectMapper objectMapper,
            @Value("${qiqihar.risk.business-session-url:}") String sessionUrl) {
        this.objectMapper = objectMapper;
        this.sessionUri = sessionUrl == null || sessionUrl.isBlank() ? null : URI.create(sessionUrl.strip());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Optional<RiskBusinessSession> authenticate(String cookieHeader) {
        if (sessionUri == null) {
            throw new RiskSessionValidationUnavailableException("business session endpoint is not configured");
        }
        if (cookieHeader == null || cookieHeader.isBlank()) return Optional.empty();
        HttpRequest request = HttpRequest.newBuilder(sessionUri)
                .timeout(Duration.ofSeconds(3))
                .header("Accept", "application/json")
                .header("Cookie", cookieHeader)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) return Optional.empty();
            if (response.statusCode() != 200) {
                throw new RiskSessionValidationUnavailableException(
                        "business session endpoint returned " + response.statusCode());
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            String subjectId = data.path("subjectId").asText("");
            if (subjectId.isBlank()) return Optional.empty();
            Set<String> permissions = new HashSet<>();
            JsonNode permissionValues = data.path("permissions");
            if (permissionValues.isArray()) {
                permissionValues.forEach(value -> {
                    if (value.isTextual() && !value.asText().isBlank()) permissions.add(value.asText());
                });
            }
            JsonNode root = data.path("rootAdministrator");
            if (!root.isMissingNode() && !root.isBoolean()) {
                throw new IllegalArgumentException("Invalid rootAdministrator claim");
            }
            Set<String> regions = new HashSet<>();
            JsonNode regionValues = data.path("regionCodes");
            if (!regionValues.isMissingNode() && !regionValues.isNull()) {
                if (!regionValues.isArray()) throw new IllegalArgumentException("Invalid regionCodes claim");
                regionValues.forEach(value -> {
                    if (!value.isTextual()) throw new IllegalArgumentException("Invalid regionCode claim");
                    regions.add(RiskRegionScope.requireRegionCode(value.asText()));
                });
            }
            return Optional.of(new RiskBusinessSession(
                    subjectId, permissions, root.isBoolean() && root.asBoolean(), regions));
        } catch (RiskSessionValidationUnavailableException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RiskSessionValidationUnavailableException(
                    "business session validation was interrupted", exception);
        } catch (Exception exception) {
            throw new RiskSessionValidationUnavailableException(
                    "business session validation failed", exception);
        }
    }
}
