package com.cofco.qiqihar.graintrade.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DatabaseConnectionResilienceConfigurationTest {

    @Test
    void productionDefaultsBoundPoolWaitsAndRetireStaleDatabaseSockets() {
        try (InputStream input = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            Map<String, Object> root = new Yaml().load(input);
            Map<String, Object> hikari = nested(root, "spring", "datasource", "hikari");
            Map<String, Object> driver = nested(hikari, "data-source-properties");

            assertThat(hikari)
                    .containsEntry("connection-timeout", "${QIQIHAR_DB_POOL_CONNECTION_TIMEOUT:5000}")
                    .containsEntry("validation-timeout", "${QIQIHAR_DB_POOL_VALIDATION_TIMEOUT:3000}")
                    .containsEntry("maximum-pool-size", "${QIQIHAR_DB_POOL_MAXIMUM_SIZE:50}")
                    .containsEntry("max-lifetime", "${QIQIHAR_DB_POOL_MAX_LIFETIME:900000}")
                    .containsEntry("keepalive-time", "${QIQIHAR_DB_POOL_KEEPALIVE_TIME:120000}")
                    .containsEntry("leak-detection-threshold", "${QIQIHAR_DB_POOL_LEAK_DETECTION_THRESHOLD:60000}");
            assertThat(driver)
                    .containsEntry("connectTimeout", "${QIQIHAR_DB_CONNECT_TIMEOUT:10}")
                    .containsEntry("socketTimeout", "${QIQIHAR_DB_SOCKET_TIMEOUT:30}")
                    .containsEntry("tcpKeepAlive", "${QIQIHAR_DB_TCP_KEEPALIVE:true}")
                    .containsEntry(
                            "ApplicationName",
                            "${QIQIHAR_DB_APPLICATION_NAME:${spring.application.name}}");
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> root, String... path) {
        Map<String, Object> current = root;
        for (String segment : path) {
            Object next = current.get(segment);
            assertThat(next).as("configuration section %s", segment).isInstanceOf(Map.class);
            current = (Map<String, Object>) next;
        }
        return current;
    }
}
