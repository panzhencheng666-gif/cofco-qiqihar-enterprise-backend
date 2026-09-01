package com.cofco.qiqihar.graintrade.testsupport;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtectedTestDatabaseTest {

    private static final String TEST_URL =
            "jdbc:postgresql://127.0.0.1:55498/qiqihar_enterprise_test";
    private static final String SHARED_DEFAULT_PORT_URL =
            "jdbc:postgresql://127.0.0.1:5432/qiqihar_enterprise_test";
    private static final String FORBIDDEN_URL =
            "jdbc:postgresql://127.0.0.1:5432/qiqihar_enterprise_forbidden";

    @Test
    void rejectsMissingExplicitIsolationConfigurationBeforeOpeningAConnection() {
        assertThatThrownBy(() -> ProtectedTestDatabase.resolve(Map.of(), new Properties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QIQIHAR_TEST_DB_URL")
                .hasMessageContaining("explicit");
    }

    @Test
    void rejectsTheSharedPostgresDefaultPortBeforeOpeningAConnection() {
        Map<String, String> environment = Map.of(
                "QIQIHAR_TEST_DB_URL", SHARED_DEFAULT_PORT_URL);

        assertThatThrownBy(() -> ProtectedTestDatabase.resolve(environment, new Properties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5432")
                .hasMessageContaining("dedicated non-default port");
    }

    @Test
    void acceptsAnExplicitDedicatedNonDefaultPort() {
        Map<String, String> environment = Map.of("QIQIHAR_TEST_DB_URL", TEST_URL);

        ProtectedTestDatabase database =
                ProtectedTestDatabase.resolve(environment, new Properties());

        assertThat(database.url()).isEqualTo(TEST_URL);
    }

    @Test
    void rejectsHostileDedicatedTestEnvironmentUrlBeforeOpeningAConnection() {
        Map<String, String> environment = new HashMap<>();
        environment.put("QIQIHAR_TEST_DB_URL", FORBIDDEN_URL);

        assertThatThrownBy(() -> ProtectedTestDatabase.resolve(environment, new Properties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qiqihar_enterprise_test")
                .hasMessageContaining("qiqihar_enterprise_forbidden");
    }

    @Test
    void rejectsAConflictingStandardSpringDatasourceUrl() {
        Map<String, String> environment = new HashMap<>();
        environment.put("QIQIHAR_TEST_DB_URL", TEST_URL);
        environment.put("SPRING_DATASOURCE_URL", FORBIDDEN_URL);

        assertThatThrownBy(() -> ProtectedTestDatabase.resolve(environment, new Properties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATASOURCE_URL")
                .hasMessageContaining("must match QIQIHAR_TEST_DB_URL");
    }

    @Test
    void rejectsAConflictingStandardSpringFlywayUrl() {
        Map<String, String> environment = new HashMap<>();
        environment.put("QIQIHAR_TEST_DB_URL", TEST_URL);
        Properties systemProperties = new Properties();
        systemProperties.setProperty("spring.flyway.url", FORBIDDEN_URL);

        assertThatThrownBy(() -> ProtectedTestDatabase.resolve(environment, systemProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.url")
                .hasMessageContaining("must match QIQIHAR_TEST_DB_URL");
    }

    @Test
    void springDatasourceAndFlywayArgumentsReuseTheProtectedTarget() {
        Map<String, String> environment = Map.of("QIQIHAR_TEST_DB_URL", TEST_URL);

        ProtectedTestDatabase database =
                ProtectedTestDatabase.resolve(environment, new Properties());

        assertThat(database.springApplicationArguments())
                .contains(
                        "--spring.datasource.url=" + TEST_URL,
                        "--spring.flyway.url=" + TEST_URL);
    }

    @Test
    void testApplicationConfigurationUsesTheSameExplicitProtectedUrlWithoutFallback()
            throws Exception {
        String applicationYaml = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(applicationYaml)
                .contains("url: ${QIQIHAR_TEST_DB_URL}")
                .doesNotContain("jdbc:postgresql://127.0.0.1:5432")
                .doesNotContain("SPRING_DATASOURCE_URL");
    }

    @Test
    void verifiesTheActualJdbcMetadataUrlWithoutDestroyingMigratedState() throws Exception {
        ProtectedTestDatabase database = ProtectedTestDatabase.resolve(System.getenv(), System.getProperties());

        database.flyway().migrate();
        try (Connection connection = database.openConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(database.url());
            assertThat(connection.getCatalog()).isEqualTo("qiqihar_enterprise_test");
            try (var result = connection.createStatement()
                    .executeQuery("SELECT to_regclass('platform.work_unit') IS NOT NULL")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
            }
        }
        assertThat(database.springApplicationArguments())
                .contains("--spring.datasource.url=" + database.url());
    }
}
