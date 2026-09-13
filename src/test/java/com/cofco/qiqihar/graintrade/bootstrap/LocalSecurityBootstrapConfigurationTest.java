package com.cofco.qiqihar.graintrade.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LocalSecurityBootstrapConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=local")
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withUserConfiguration(LocalSecurityBootstrapConfiguration.class);

    @Test void retainsExistingLocalInitializationByDefault() {
        context.run(c -> assertThat(c).hasBean("localSecurityBootstrap"));
    }

    @Test void populatedCopyCanDisableDemoInitialization() {
        context.withPropertyValues("qiqihar.local.bootstrap.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean("localSecurityBootstrap"));
    }
}
