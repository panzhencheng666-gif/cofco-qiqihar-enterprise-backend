package com.cofco.qiqihar.graintrade.shared.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class JdbcOidcSessionRegistryTransactionTest {
    @Test
    void classProxyStartsAndCommitsSessionRemoval() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var registry = context.getBean(OidcSessionRegistry.class);
            var jdbc = context.getBean(JdbcClient.class);
            var manager = context.getBean(RecordingTransactionManager.class);
            // No database is needed: an unknown session exercises both statements.
            var statement = mock(JdbcClient.StatementSpec.class);
            var query = mock(JdbcClient.MappedQuerySpec.class);
            when(jdbc.sql(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return statement;
            });
            when(statement.param(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(statement);
            when(statement.query(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<OidcSessionInformation>>any()))
                    .thenReturn(query);
            when(query.optional()).thenReturn(java.util.Optional.empty());

            assertThat(AopUtils.isCglibProxy(registry)).isTrue();
            assertThat(registry.removeSessionInformation("missing-session")).isNull();
            assertThat(manager.commits).isEqualTo(1);
            assertThat(manager.rollbacks).isZero();
        }
    }

    @Test
    void classProxyRollsBackWhenSessionRemovalFails() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var registry = context.getBean(OidcSessionRegistry.class);
            var manager = context.getBean(RecordingTransactionManager.class);
            when(context.getBean(JdbcClient.class).sql(org.mockito.ArgumentMatchers.anyString()))
                    .thenThrow(new IllegalStateException("database unavailable"));
            assertThatThrownBy(() -> registry.removeSessionInformation("session"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");
            assertThat(manager.commits).isZero();
            assertThat(manager.rollbacks).isEqualTo(1);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean JdbcClient jdbc() { return mock(JdbcClient.class); }
        @Bean RecordingTransactionManager transactionManager() { return new RecordingTransactionManager(); }
        @Bean OidcSessionRegistry registry(JdbcClient jdbc) {
            return new JdbcOidcSessionRegistry(jdbc, new ObjectMapper(), 1);
        }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
