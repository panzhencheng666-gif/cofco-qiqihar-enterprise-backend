package com.cofco.qiqihar.graintrade.bootstrap;

import com.cofco.qiqihar.graintrade.shared.infrastructure.SessionPoolConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SessionPoolResilienceTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(DataSourceProperties.class, () -> {
                var properties = new DataSourceProperties();
                properties.setUrl(System.getenv("QIQIHAR_TEST_DB_URL"));
                properties.setUsername(System.getenv("QIQIHAR_TEST_DB_USERNAME"));
                properties.setPassword(System.getenv("QIQIHAR_TEST_DB_PASSWORD"));
                return properties;
            })
            .withUserConfiguration(SessionPoolConfiguration.class);

    @Test void customBusinessDataSourceRetainsItsGuardAndDedicatedSessionPool() {
        DataSource protectedDataSource = mock(DataSource.class);
        context.withBean("dataSource", DataSource.class, () -> protectedDataSource,
                definition -> definition.setPrimary(true)).run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(DataSource.class)).isSameAs(protectedDataSource);
            assertThat(c.getBean("sessionDataSource", HikariDataSource.class))
                    .isNotSameAs(protectedDataSource);
        });
    }

    @Test void bothActualPoolsBoundSocketReadsAndConnectionLifetime() {
        context.run(c -> {
            assertThat(c).hasNotFailed();
            for (String name : new String[]{"dataSource", "sessionDataSource"}) {
                var pool = c.getBean(name, HikariDataSource.class);
                assertThat(pool.getDataSourceProperties().getProperty("socketTimeout"))
                        .as(name + " socket read timeout").isEqualTo("30");
                assertThat(pool.getDataSourceProperties().getProperty("connectTimeout")).isEqualTo("3");
                assertThat(pool.getDataSourceProperties().getProperty("cancelSignalTimeout")).isEqualTo("3");
                assertThat(pool.getMaxLifetime()).isEqualTo(900_000);
                assertThat(pool.getMinimumIdle()).isLessThan(pool.getMaximumPoolSize());
            }
        });
    }

    @Test void businessPoolSaturationDoesNotConsumeSessionConnections() {
        context.run(c -> {
            var business = c.getBean("dataSource", HikariDataSource.class);
            var session = c.getBean("sessionDataSource", HikariDataSource.class);
            business.setMaximumPoolSize(1);
            try (var held = business.getConnection(); var login = session.getConnection();
                    var statement = login.createStatement(); var rows = statement.executeQuery("SELECT 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(business.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            }
        });
    }

    @Test void stalledSocketIsDiscardedAndPoolRecovers() {
        context.run(c -> {
            var pool = c.getBean("dataSource", HikariDataSource.class);
            // Exercise the real PostgreSQL driver with a shorter test-only deadline.
            pool.addDataSourceProperty("socketTimeout", "1");
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(0);
            long started = System.nanoTime();
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("SELECT pg_sleep(10)"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds()).isLessThan(6);
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        });
    }

    @Test void concurrentSessionReadsFinishWhileBusinessPoolIsFull() {
        context.run(c -> {
            var business = c.getBean("dataSource", HikariDataSource.class);
            var session = c.getBean("sessionDataSource", HikariDataSource.class);
            business.setMaximumPoolSize(1);
            business.setMinimumIdle(0);
            try (var held = business.getConnection();
                    var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var requests = java.util.stream.IntStream.range(0, 20).mapToObj(i ->
                        executor.submit(() -> {
                            try (var connection = session.getConnection(); var statement = connection.createStatement();
                                    var rows = statement.executeQuery("SELECT 1")) {
                                return rows.next() && rows.getInt(1) == 1;
                            }
                        })).toList();
                for (var request : requests) {
                    assertThat(request.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
            }
        });
    }
}
