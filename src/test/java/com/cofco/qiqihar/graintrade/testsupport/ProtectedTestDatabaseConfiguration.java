package com.cofco.qiqihar.graintrade.testsupport;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class ProtectedTestDatabaseConfiguration {

    @Bean
    @Primary
    DataSource protectedTestDataSource() {
        return ProtectedTestDatabase.shared().dataSource();
    }
}
