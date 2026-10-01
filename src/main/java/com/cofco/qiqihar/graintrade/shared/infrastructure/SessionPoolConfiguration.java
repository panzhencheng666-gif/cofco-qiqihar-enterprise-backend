package com.cofco.qiqihar.graintrade.shared.infrastructure;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

@Configuration(proxyBeanMethods = false)
public class SessionPoolConfiguration {
    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "dataSource")
    public DataSource dataSource(DataSourceProperties properties,
            @Value("${QIQIHAR_DB_PREPARE_THRESHOLD:-1}") String prepareThreshold) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        dataSource.setPoolName("business");
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(2);
        dataSource.setConnectionTimeout(3_000);
        boundConnections(dataSource);
        dataSource.addDataSourceProperty("prepareThreshold", prepareThreshold);
        return dataSource;
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource sessionDataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        dataSource.setPoolName("session-login");
        dataSource.setMaximumPoolSize(2);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(1_000);
        boundConnections(dataSource);
        return dataSource;
    }

    private static void boundConnections(HikariDataSource dataSource) {
        // maxLifetime only retires returned connections. Socket timeouts are
        // required to release a connection stuck in driver I/O while borrowed.
        dataSource.setValidationTimeout(1_000);
        dataSource.setIdleTimeout(300_000);
        dataSource.setMaxLifetime(900_000);
        dataSource.setKeepaliveTime(120_000);
        dataSource.setLeakDetectionThreshold(60_000);
        dataSource.addDataSourceProperty("connectTimeout", "3");
        dataSource.addDataSourceProperty("socketTimeout", "30");
        dataSource.addDataSourceProperty("cancelSignalTimeout", "3");
        dataSource.addDataSourceProperty("tcpKeepAlive", "true");
    }

    @Bean
    @Primary
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean(name = "sessionJdbcClient")
    public JdbcClient sessionJdbcClient(@Qualifier("sessionDataSource") DataSource sessionDataSource) {
        return JdbcClient.create(sessionDataSource);
    }

    @Bean
    public JdbcIndexedSessionRepository sessionRepository(
            @Qualifier("sessionDataSource") DataSource sessionDataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(sessionDataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(sessionDataSource));
        JdbcIndexedSessionRepository repository = new JdbcIndexedSessionRepository(jdbc, transactions);
        repository.setTableName("platform.http_session");
        repository.setDefaultMaxInactiveInterval(Duration.ofHours(8));
        return repository;
    }
}
