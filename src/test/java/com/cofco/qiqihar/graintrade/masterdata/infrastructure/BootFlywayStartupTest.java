package com.cofco.qiqihar.graintrade.masterdata.infrastructure;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class BootFlywayStartupTest {

    private static final String URL = environment(
            "QIQIHAR_TEST_DB_URL",
            "jdbc:postgresql://127.0.0.1:5432/qiqihar_enterprise_test");
    private static final String USERNAME = environment("QIQIHAR_TEST_DB_USERNAME", System.getenv("USER"));
    private static final String PASSWORD = environment("QIQIHAR_TEST_DB_PASSWORD", "");
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting", "workflow", "overview"
    };

    @BeforeAll
    static void resetDedicatedTestDatabase() throws SQLException {
        if (!URL.matches("jdbc:postgresql://[^/]+/qiqihar_enterprise_test(?:\\?.*)?")) {
            throw new IllegalStateException("Boot startup test may only reset qiqihar_enterprise_test");
        }
        try (Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD);
                Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    @Test
    void commandLineTestDatabaseWinsOverConflictingHigherPriorityDefaultsAcrossTwoStartups() throws SQLException {
        String previousUrl = System.getProperty("spring.datasource.url");
        System.setProperty(
                "spring.datasource.url",
                "jdbc:postgresql://127.0.0.1:5432/qiqihar_enterprise_forbidden");
        try {
            startAndCloseApplication();
            assertThat(installedMigrationCount()).isEqualTo(4);
            assertThat(productCount()).isEqualTo(3);

            startAndCloseApplication();
            assertThat(installedMigrationCount()).isEqualTo(4);
            assertThat(productCount()).isEqualTo(3);
        } finally {
            if (previousUrl == null) {
                System.clearProperty("spring.datasource.url");
            } else {
                System.setProperty("spring.datasource.url", previousUrl);
            }
        }
    }

    private void startAndCloseApplication() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(GrainTradeApplication.class)
                .properties("spring.main.web-application-type=none")
                .run(
                        "--spring.datasource.url=" + URL,
                        "--spring.datasource.username=" + USERNAME,
                        "--spring.datasource.password=" + PASSWORD)) {
            DataSource dataSource = context.getBean(DataSource.class);
            try (Connection connection = dataSource.getConnection()) {
                assertThat(connection.getMetaData().getURL()).isEqualTo(URL);
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not verify the application DataSource URL", exception);
            }
        }
    }

    private long installedMigrationCount() throws SQLException {
        return count("SELECT count(*) FROM public.flyway_schema_history WHERE success");
    }

    private long productCount() throws SQLException {
        return count("SELECT count(*) FROM platform.product");
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
