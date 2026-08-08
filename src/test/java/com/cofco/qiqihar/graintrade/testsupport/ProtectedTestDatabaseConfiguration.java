package com.cofco.qiqihar.graintrade.testsupport;

import javax.sql.DataSource;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.application.CurrentSecuritySubject;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

@TestConfiguration(proxyBeanMethods = false)
public class ProtectedTestDatabaseConfiguration {

    @Bean
    @Primary
    DataSource protectedTestDataSource() {
        return ProtectedTestDatabase.shared().dataSource();
    }

    @Bean
    ApplicationRunner provisionSecurityTestSubjects(DataSource dataSource) {
        return arguments -> provisionSecurityTestSubjects(JdbcClient.create(dataSource));
    }

    @Bean
    @Primary
    AccessControl testAccessControl(
            CurrentSecuritySubject currentSubject, SecurityPrincipalRepository principals) {
        return new AccessControl(currentSubject, principals, true) {
            @Override
            public AuthorizedReadScope requireReadScope() {
                return currentSubject.subjectId().isEmpty()
                        ? AuthorizedReadScope.unrestricted()
                        : super.requireReadScope();
            }
        };
    }

    /** Restores shared test identities after a test intentionally replaces the security fixture. */
    public static void provisionSecurityTestSubjects(JdbcClient jdbc) {
            jdbc.sql("""
                    INSERT INTO platform.work_unit(code,name,sort_order)
                    VALUES ('TEST','自动化测试工作单位',9900)
                    ON CONFLICT(code) DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                    SELECT 'TEST', code FROM platform.region
                    ON CONFLICT DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                    VALUES ('production-tester','产情测试员','TEST'),
                           ('market-tester','市场测试员','TEST'),
                           ('logistics-tester','物流测试员','TEST'),
                           ('supply-reviewer','供需测试员','TEST'),
                           ('data-fault-test','数据边界测试员','TEST'),
                           ('metadata-fault-test','元数据边界测试员','TEST')
                    ON CONFLICT(subject_id) DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user_role(subject_id,role_code)
                    SELECT subject_id, 'SYSTEM_ADMIN' FROM platform.security_user
                    WHERE work_unit_code = 'TEST'
                    ON CONFLICT DO NOTHING
                    """).update();
            jdbc.sql("""
                    INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                    SELECT security_user.subject_id, unit_scope.region_code
                    FROM platform.security_user
                    CROSS JOIN platform.work_unit_region_scope unit_scope
                    WHERE security_user.work_unit_code = 'TEST'
                      AND unit_scope.work_unit_code = 'TEST'
                    ON CONFLICT DO NOTHING
                    """).update();
    }
}
