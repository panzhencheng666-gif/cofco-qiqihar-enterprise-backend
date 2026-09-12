package com.cofco.qiqihar.graintrade.identity.application;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import static org.assertj.core.api.Assertions.*;

class ResponsibilityLimitsDatabaseTest {
    @Test void tenAllowedEleventhDeniedExpiredGrantFreedAndRoleAdministratorExempt() {
        var database = ProtectedTestDatabase.shared();
        database.flyway().migrate();
        var jdbc = JdbcClient.create(database.dataSource());
        new TransactionTemplate(new DataSourceTransactionManager(database.dataSource())).executeWithoutResult(status -> {
            status.setRollbackOnly();
            var regions=new ArrayList<String>();
            for(int i=1;i<=11;i++) {
                String region="23020298"+String.format("%02d",i);
                com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures.insertRegion(jdbc,region,"隔离责任测试"+i,"230202","TOWNSHIP",99990+i);
                regions.add(region);
            }
            String unit="task-limit-unit";
            jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES(:unit,'隔离测试单位',9999)").param("unit",unit).update();
            jdbc.sql("INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code) VALUES(:unit,'230202')").param("unit",unit).update();
            String employee="task-limit-employee", admin="task-limit-role-admin";
            for (String subject : List.of(employee,admin)) {
                jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES(:s,:s,:unit)").param("s",subject).param("unit",unit).update();
                jdbc.sql("INSERT INTO platform.security_user_role(subject_id,role_code,granted_by) VALUES(:s,:role,:s)")
                        .param("s",subject).param("role",subject.equals(admin)?"BUSINESS_REVIEWER":"BUSINESS_OPERATOR").update();
            }
            for(int i=1;i<=10;i++) grant(jdbc,employee,regions.get(i-1));
            Object savepoint=status.createSavepoint();
            assertThatThrownBy(() -> grant(jdbc,employee,regions.get(10))).hasMessageContaining("account_region_limit");
            status.rollbackToSavepoint(savepoint); status.releaseSavepoint(savepoint);
            assertThat(jdbc.sql("SELECT count(*) FROM platform.security_user_region_scope WHERE subject_id=:s AND valid_until IS NULL")
                    .param("s",employee).query(Long.class).single()).isEqualTo(10);
            jdbc.sql("UPDATE platform.security_user_region_scope SET valid_until=CURRENT_TIMESTAMP WHERE subject_id=:s AND region_code=:r").param("s",employee).param("r",regions.getFirst()).update();
            grant(jdbc,employee,regions.get(10));
            for(int i=1;i<=11;i++) grant(jdbc,admin,regions.get(i-1));
            assertThat(jdbc.sql("SELECT count(*) FROM platform.security_user_region_scope WHERE subject_id=:s")
                    .param("s",admin).query(Long.class).single()).isEqualTo(11);
        });
    }
    private static void grant(JdbcClient jdbc,String subject,String region) {
        jdbc.sql("INSERT INTO platform.security_user_region_scope(subject_id,region_code,granted_by) VALUES(:s,:r,:s)")
                .param("s",subject).param("r",region).update();
    }
}
