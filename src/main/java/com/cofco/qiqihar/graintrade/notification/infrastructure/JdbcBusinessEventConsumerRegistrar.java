package com.cofco.qiqihar.graintrade.notification.infrastructure;

import com.cofco.qiqihar.graintrade.notification.application.BusinessEventConsumerRegistrar;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBusinessEventConsumerRegistrar implements BusinessEventConsumerRegistrar {
    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    public JdbcBusinessEventConsumerRegistrar(
            @Value("${qiqihar.business-event.consumer-registration.datasource.url}") String url,
            @Value("${qiqihar.business-event.consumer-registration.datasource.username}")
                    String username,
            @Value("${qiqihar.business-event.consumer-registration.datasource.password}")
                    String password) {
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName("business-event-consumer-registrar");
        configuration.setJdbcUrl(url);
        configuration.setUsername(username);
        configuration.setPassword(password);
        configuration.setMaximumPoolSize(4);
        configuration.setMinimumIdle(0);
        configuration.setInitializationFailTimeout(-1);
        configuration.setConnectionTimeout(5_000);
        configuration.setConnectionInitSql("SET ROLE qiqihar_event_consumer_registrar");
        dataSource = new HikariDataSource(configuration);
        jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public boolean ensureCheckpoint(
            String consumerId,
            String instanceId,
            long initialSequence,
            String authorizationSubjectId) {
        return jdbc.sql("""
                SELECT platform.ensure_business_event_consumer(
                  :consumerId,:instanceId,:initialSequence,:authorizationSubjectId)
                """).param("consumerId", consumerId).param("instanceId", instanceId)
                .param("initialSequence", initialSequence)
                .param("authorizationSubjectId", authorizationSubjectId)
                .query(Boolean.class).single();
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}
