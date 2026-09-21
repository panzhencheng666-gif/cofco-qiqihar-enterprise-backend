package com.cofco.qiqihar.graintrade.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class EnabledEmployeeBusinessAccessIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired SecurityPrincipalRepository principals;

    @Test
    void rolelessEmployeeCanRegisterBusinessStreamWhileDisabledEmployeeCannot() {
        var jdbc = JdbcClient.create(dataSource);
        jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES('shared-employee','共享业务测试','TEST') ON CONFLICT DO NOTHING").update();
        var employee = principals.findEnabled("shared-employee").orElseThrow();
        assertThat(employee.roleCodes()).isEmpty();
        assertThat(employee.regionCodes()).isEmpty();
        assertThat(employee.effectivePermissionCodes()).contains("BUSINESS_IMPORT", "BUSINESS_SUBMIT", "BUSINESS_VOID");
        assertThat(employee.permits("IDENTITY_ADMIN")).isFalse();
        assertThat(jdbc.sql("SELECT platform.ensure_business_event_consumer('shared-employee-test','test',0,'shared-employee')")
            .query(Boolean.class).single()).isTrue();
        jdbc.sql("UPDATE platform.security_user SET enabled=false WHERE subject_id='shared-employee'").update();
        assertThat(principals.findEnabled("shared-employee")).isEmpty();
        assertThatThrownBy(() -> jdbc.sql("SELECT platform.ensure_business_event_consumer('shared-employee-disabled','test',0,'shared-employee')")
            .query(Boolean.class).single()).hasMessageContaining("authorization subject cannot read business events");
    }
}
