package com.cofco.qiqihar.graintrade.testsupport;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtectedTestDatabaseTest {

    private static final String TEST_URL =
            "jdbc:postgresql://127.0.0.1:5432/qiqihar_enterprise_test";
    private static final String FORBIDDEN_URL =
            "jdbc:postgresql://127.0.0.1:5432/qiqihar_enterprise_forbidden";

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
    void standardSpringEnvironmentAndSystemPropertiesCannotRedirectTheProtectedTarget() {
        Map<String, String> environment = new HashMap<>();
        environment.put("QIQIHAR_TEST_DB_URL", TEST_URL);
        environment.put("SPRING_DATASOURCE_URL", FORBIDDEN_URL);
        Properties systemProperties = new Properties();
        systemProperties.setProperty("spring.datasource.url", FORBIDDEN_URL);

        ProtectedTestDatabase database = ProtectedTestDatabase.resolve(environment, systemProperties);

        assertThat(database.url()).isEqualTo(TEST_URL);
        assertThat(database.springApplicationArguments())
                .contains("--spring.datasource.url=" + TEST_URL);
    }

    @Test
    void verifiesTheActualJdbcMetadataUrlBeforeReturningAConnection() throws Exception {
        ProtectedTestDatabase database = ProtectedTestDatabase.resolve(System.getenv(), System.getProperties());

        try (Connection connection = database.openConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(database.url());
            assertThat(connection.getCatalog()).isEqualTo("qiqihar_enterprise_test");
        }
    }
}
