package com.cofco.qiqihar.graintrade.marketintelligence;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

@RestController
public class NewsDiscoveryController {
    private static final String PREFIX = "qiqihar.market-intelligence.discovery.";
    private final NewsDiscoveryRead read;
    private final Environment environment;
    public NewsDiscoveryController(JdbcClient jdbc, Environment environment) {
        this.read = new NewsDiscoveryRead(jdbc);
        this.environment = environment;
    }
    @GetMapping("/api/v1/market-intelligence/news/discovery")
    public ResponseEntity<ApiResponse<NewsDiscoveryRead.Snapshot>> discovery() {
        var now = java.time.Instant.now();
        boolean enabled = "true".equalsIgnoreCase(environment.getProperty(PREFIX + "enabled", "false"));
        java.time.Instant deadline = null;
        try {
            String configured = environment.getProperty(PREFIX + "deadline");
            if (configured != null) deadline = java.time.Instant.parse(configured);
        } catch (java.time.DateTimeException invalid) {
            // Read path fails closed without exposing configuration values.
        }
        var snapshot = read.snapshot(enabled, deadline, now);
        return ResponseEntity.status("UNAVAILABLE".equals(snapshot.state()) ? 503 : 200)
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(new ApiResponse<>(snapshot));
    }
}
