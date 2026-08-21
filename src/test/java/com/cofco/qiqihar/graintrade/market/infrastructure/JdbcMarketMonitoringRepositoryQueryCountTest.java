package com.cofco.qiqihar.graintrade.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringService;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure.JdbcSamplePointCoordinateGuard;
import com.cofco.qiqihar.graintrade.shared.application.DefaultPageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import com.cofco.qiqihar.graintrade.shared.infrastructure.JdbcPageDefinitionRepository;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
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
                counting(DATABASE.dataSource(), queries),
                new JdbcSamplePointCoordinateGuard(DATABASE.dataSource()));

        assertThat(repository.findCoreFields("CORN")).hasSizeGreaterThan(10);
        assertThat(queries).hasValue(2);
    }

    @Test
    void checksPageDefinitionExistenceWithOneStatementWithoutLoadingThePage() {
        AtomicInteger queries = new AtomicInteger();
        DefaultPageDefinitionQuery definitions = new DefaultPageDefinitionQuery(
                new JdbcPageDefinitionRepository(counting(DATABASE.dataSource(), queries)));

        assertThat(definitions.hasDefinition(
                new BusinessPageKey("MARKET", "MONITORING", "CORN"))).isTrue();
        assertThat(queries).hasValue(1);
    }

    @Test
    void loadsACompleteObjectSpecificDefinitionInSixStatements() {
        AtomicInteger queries = new AtomicInteger();
        DataSource dataSource = counting(DATABASE.dataSource(), queries);
        MarketMonitoringService service = new MarketMonitoringService(
                new JdbcMarketMonitoringRepository(
                        dataSource, new JdbcSamplePointCoordinateGuard(DATABASE.dataSource())),
                new DefaultPageDefinitionQuery(new JdbcPageDefinitionRepository(dataSource)),
                Optional::empty,
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneId.of("Asia/Shanghai")));

        assertThat(service.definition("CORN", "FEED_MILL").coreFields()).isNotEmpty();
        assertThat(queries).hasValue(6);
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
