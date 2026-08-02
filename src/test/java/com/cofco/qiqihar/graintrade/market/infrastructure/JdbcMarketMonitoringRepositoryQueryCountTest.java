package com.cofco.qiqihar.graintrade.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class JdbcMarketMonitoringRepositoryQueryCountTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();

    @BeforeAll
    static void migrate() { DATABASE.flyway().migrate(); }

    @Test
    void loadsCoreFieldsAndAllRegularAndObjectOptionsInTwoQueries() {
        AtomicInteger queries = new AtomicInteger();
        JdbcMarketMonitoringRepository repository = new JdbcMarketMonitoringRepository(
                counting(DATABASE.dataSource(), queries));

        assertThat(repository.findCoreFields("CORN")).hasSizeGreaterThan(10);
        assertThat(queries).hasValue(2);
    }

    private static DataSource counting(DataSource delegate, AtomicInteger queries) {
        return new DelegatingDataSource(delegate) {
            @Override
            public Connection getConnection() throws SQLException {
                return connection(super.getConnection(), queries);
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return connection(super.getConnection(username, password), queries);
            }
        };
    }

    private static Connection connection(Connection delegate, AtomicInteger queries) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().startsWith("prepareStatement")) queries.incrementAndGet();
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }
}
