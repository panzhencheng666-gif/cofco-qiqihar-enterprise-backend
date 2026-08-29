package com.cofco.qiqihar.graintrade.testsupport;

import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/** Restores shared protected fixtures before each test can customize them. */
public final class ProtectedTestDatabaseExecutionListener
        extends AbstractTestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext testContext) {
        DataSource dataSource = testContext.getApplicationContext().getBean(DataSource.class);
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(
                JdbcClient.create(dataSource));
    }
}
