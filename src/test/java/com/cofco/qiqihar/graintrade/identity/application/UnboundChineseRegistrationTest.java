package com.cofco.qiqihar.graintrade.identity.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.identity.infrastructure.JdbcIdentityGovernanceRepository;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.infrastructure.JdbcSecurityPrincipalRepository;
class UnboundChineseRegistrationTest {
 @Test void chineseEmployeePersistsWithoutRegionAndNeverGainsAdministratorScope(){
  ProtectedTestDatabase.shared().flyway().migrate();
  var ds=ProtectedTestDatabase.shared().dataSource();var jdbc=JdbcClient.create(ds);
  new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{
   status.setRollbackOnly();
   jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) SELECT 'QIQIHAR_BUSINESS','隔离注册测试单位',COALESCE(MAX(sort_order),0)+1 FROM platform.work_unit ON CONFLICT(code) DO UPDATE SET active=true").update();
   var repo=new JdbcIdentityGovernanceRepository(jdbc);var audit=mock(BusinessAuditRecorder.class);
   var governance=new IdentityGovernanceService(repo,mock(AccessControl.class),audit,Clock.systemUTC(),null,null);
   var service=new EmployeeRegistrationService(repo,governance,jdbc,audit,true,"https://issuer.example.test");
   String name="中文注册验证"+UUID.randomUUID().toString().replace("-","");
   var assignment=new EmployeeAssignment(name,"QIQIHAR_BUSINESS","ACTIVE","ACTIVE",List.of("BUSINESS_OPERATOR"),List.of(),List.of());
   assertEquals(1L,jdbc.sql("SELECT count(*) FROM platform.work_unit WHERE code='QIQIHAR_BUSINESS' AND active").query(Long.class).single(),"active work unit");
   assertEquals(1L,jdbc.sql("SELECT count(*) FROM platform.access_role WHERE code='BUSINESS_OPERATOR' AND active").query(Long.class).single(),"ordinary role");
   assertTrue(repo.validAssignment(assignment),"empty-region ordinary assignment");
   service.validateDraft(name,assignment);
   service.register("https://issuer.example.test",UUID.randomUUID().toString(),name,assignment);
   assertTrue(jdbc.sql("SELECT enabled FROM platform.security_user WHERE subject_id=:s").param("s",name).query(Boolean.class).single());
   assertEquals(List.of("BUSINESS_OPERATOR"),jdbc.sql("SELECT role_code FROM platform.security_user_role WHERE subject_id=:s").param("s",name).query(String.class).list());
   assertEquals(0L,jdbc.sql("SELECT count(*) FROM platform.security_user_region_scope WHERE subject_id=:s").param("s",name).query(Long.class).single());
   var principal=new JdbcSecurityPrincipalRepository(jdbc).findEnabled(name).orElseThrow();
   assertFalse(principal.isRootAdministrator());assertFalse(principal.includesRegion("230202"));
   assertTrue(principal.regionCodes().isEmpty());
   return null;
  });
 }
}
