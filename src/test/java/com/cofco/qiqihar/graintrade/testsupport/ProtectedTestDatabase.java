package com.cofco.qiqihar.graintrade.testsupport;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class ProtectedTestDatabase {

    public static final String DATABASE_NAME = "qiqihar_enterprise_test";
    private static final String[] APPLICATION_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting",
        "workflow", "overview", "evidence", "registry"
    };
    private static final String DEFAULT_URL =
            "jdbc:postgresql://127.0.0.1:5432/" + DATABASE_NAME;

    private final String url;
    private final String username;
    private final String password;

    private static final class SharedHolder {
        private static final ProtectedTestDatabase INSTANCE =
                resolve(System.getenv(), System.getProperties());
    }

    private ProtectedTestDatabase(String url, String username, String password) {
        requireDedicatedDatabaseUrl(url);
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public static ProtectedTestDatabase resolve(
            Map<String, String> environment, Properties systemProperties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(systemProperties, "systemProperties");
        String url = valueOrDefault(environment.get("QIQIHAR_TEST_DB_URL"), DEFAULT_URL);
        String username = valueOrDefault(
                environment.get("QIQIHAR_TEST_DB_USERNAME"),
                systemProperties.getProperty("user.name", ""));
        String password = valueOrDefault(environment.get("QIQIHAR_TEST_DB_PASSWORD"), "");
        return new ProtectedTestDatabase(url, username, password);
    }

    public static ProtectedTestDatabase shared() {
        return SharedHolder.INSTANCE;
    }

    public String url() {
        return url;
    }

    public Connection openConnection() throws SQLException {
        requireDedicatedDatabaseUrl(url);
        return protect(DriverManager.getConnection(url, username, password));
    }

    public DataSource dataSource() {
        DriverManagerDataSource delegate = new DriverManagerDataSource(url, username, password);
        return new DelegatingDataSource(delegate) {
            @Override
            public Connection getConnection() throws SQLException {
                return protect(super.getConnection());
            }

            @Override
            public Connection getConnection(String requestedUsername, String requestedPassword)
                    throws SQLException {
                return protect(super.getConnection(requestedUsername, requestedPassword));
            }
        };
    }

    public Flyway flyway() {
        dropRegistrySchemaWithoutV93History();
        return Flyway.configure().dataSource(dataSource()).load();
    }

    public Flyway flywayToVersion(String targetVersion) {
        dropRegistrySchemaWithoutV93History();
        return Flyway.configure()
                .dataSource(dataSource())
                .target(MigrationVersion.fromVersion(targetVersion))
                .load();
    }

    void resetForTestSession() {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            for (String schema : APPLICATION_SCHEMAS) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to reset the dedicated test database before the test session",
                    exception);
        }
    }

    private void dropRegistrySchemaWithoutV93History() {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            boolean historyExists;
            try (ResultSet result = statement.executeQuery(
                    "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")) {
                result.next();
                historyExists = result.getBoolean(1);
            }
            boolean v93Applied = false;
            if (historyExists) {
                try (ResultSet result = statement.executeQuery("""
                        SELECT EXISTS(
                          SELECT 1 FROM public.flyway_schema_history
                          WHERE version='93' AND success)
                        """)) {
                    result.next();
                    v93Applied = result.getBoolean(1);
                }
            }
            if (!v93Applied) {
                statement.execute("DROP SCHEMA IF EXISTS registry CASCADE");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to isolate the dedicated test database before migration replay",
                    exception);
        }
    }

    public String[] springApplicationArguments() {
        return new String[] {
            "--spring.datasource.url=" + url,
            "--spring.datasource.username=" + username,
            "--spring.datasource.password=" + password
        };
    }

    private static void requireDedicatedConnection(Connection connection) throws SQLException {
        String actualUrl = connection.getMetaData().getURL();
        requireDedicatedDatabaseUrl(actualUrl);
        String actualCatalog = connection.getCatalog();
        if (!DATABASE_NAME.equals(actualCatalog)) {
            throw new IllegalStateException(
                    "Test database connection must use exact database " + DATABASE_NAME
                            + " but JDBC catalog was " + actualCatalog);
        }
    }

    private static Connection protect(Connection connection) throws SQLException {
        try {
            requireDedicatedConnection(connection);
            return connection;
        } catch (RuntimeException | SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    private static void requireDedicatedDatabaseUrl(String url) {
        String databaseName = databaseName(url);
        if (!DATABASE_NAME.equals(databaseName)) {
            throw new IllegalStateException(
                    "Test database URL must use exact database " + DATABASE_NAME
                            + " but was " + url);
        }
    }

    private static String databaseName(String url) {
        try {
            if (url == null || !url.startsWith("jdbc:postgresql://")) {
                return null;
            }
            URI uri = URI.create(url.substring("jdbc:".length()));
            String path = uri.getPath();
            if (path == null || path.length() < 2 || path.indexOf('/', 1) >= 0) {
                return null;
            }
            return path.substring(1);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
