package com.cofco.qiqihar.graintrade.identity.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.cofco.qiqihar.graintrade.identity.infrastructure.JdbcIdentitySessionInvalidator;
import com.cofco.qiqihar.graintrade.shared.security.infrastructure.JdbcSecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;

/** Explicit opt-in runner against the protected test database, with its own minimal fixtures. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="qiqihar.phone.acceptance",matches="true")
class PhoneIdentityIntegrationTest {
    static AnnotationConfigApplicationContext context;
    JdbcClient jdbc; PhoneIdentityService identities; SmsChallengeService sms;
    static final String SOURCE="phone-test-source", TARGET="phone-test-target", OTHER="phone-test-other";
    @BeforeAll static void start(){
        com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase.shared().flyway().migrate();
        context=new AnnotationConfigApplicationContext(Config.class);
        var jdbc=context.getBean(JdbcClient.class);
        jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES('TEST','隔离测试单位',9900) ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code) VALUES('TEST','230202') ON CONFLICT DO NOTHING").update();
    }
    @AfterAll static void end(){context.close();}
    @BeforeEach void setup(){
        jdbc=context.getBean(JdbcClient.class);identities=context.getBean(PhoneIdentityService.class);sms=context.getBean(SmsChallengeService.class);
        clean();
        masterFixture(()->{ for(int i=1;i<=3;i++)jdbc.sql("INSERT INTO platform.region(code,name,administrative_level,parent_code,sort_order) VALUES(:code,:name,'TOWNSHIP','230202',:sort)")
                .param("sort",991860+i).param("code","23020290"+i).param("name","手机号隔离验收"+i).update(); });
        for(String subject:List.of(SOURCE,TARGET,OTHER)) {
            jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES(:s,:s,'TEST')").param("s",subject).update();
            jdbc.sql("INSERT INTO platform.security_user_role(subject_id,role_code,granted_by) VALUES(:s,'BUSINESS_OPERATOR',:s)").param("s",subject).update();
        }
        grant(SOURCE,"230202901");grant(TARGET,"230202902");grant(OTHER,"230202903");
        identities.bind(SOURCE,"13900000186");
    }
    @AfterEach void clean(){
        if(jdbc==null)jdbc=context.getBean(JdbcClient.class);
        masterFixture(()->jdbc.sql("DELETE FROM registry.sample_point WHERE canonical_name='手机号合并隔离样本'").update());
        jdbc.sql("DELETE FROM platform.phone_merge_history WHERE source_subject LIKE 'phone-test-%'").update();
        jdbc.sql("DELETE FROM platform.phone_merge_ticket WHERE source_subject LIKE 'phone-test-%'").update();
        jdbc.sql("DELETE FROM platform.sms_challenge WHERE phone LIKE '13900000%'").update();
        jdbc.sql("DELETE FROM platform.phone_identity WHERE subject_id LIKE 'phone-test-%'").update();
        jdbc.sql("DELETE FROM platform.region_responsibility WHERE region_code LIKE '23020290%'").update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id LIKE 'phone-test-%'").update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id LIKE 'phone-test-%'").update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id LIKE 'phone-test-%'").update();
        masterFixture(()->jdbc.sql("DELETE FROM platform.region WHERE code LIKE '23020290%'").update());
    }
    void masterFixture(Runnable work) {
        // Only isolated catalog fixture setup/cleanup uses replica mode, on its own transaction.
        // Identity service calls below use separate normal connections and all allocation triggers.
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(DataSourceTransactionManager.class))
            .executeWithoutResult(status->{jdbc.sql("SET LOCAL session_replication_role=replica").update();work.run();});
    }
    void grant(String subject,String region){jdbc.sql("INSERT INTO platform.security_user_region_scope(subject_id,region_code,granted_by) VALUES(:s,:r,:s)").param("s",subject).param("r",region).update();}
    @Test void removingResponsibilityExpiresItsAuthorizationAndReleasesRegion() {
        jdbc.sql("INSERT INTO platform.region_responsibility(region_code,subject_id,updated_by,reason) VALUES('230202901',:s,:s,'test')").param("s",SOURCE).update();
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
            var repository=new com.cofco.qiqihar.graintrade.identity.infrastructure.JdbcRegionResponsibilityRepository(jdbc);
            repository.lockChange();
            repository.save(SOURCE,List.of(),List.of("230202901"),SOURCE,"撤销责任");
        });
        assertThat(jdbc.sql("SELECT platform.employee_region_available('230202901',:s)").param("s",OTHER).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.security_user_region_scope WHERE subject_id=:s AND valid_until IS NOT NULL").param("s",SOURCE).query(Long.class).single()).isEqualTo(1);
    }
    @Test void sixSelectedRegionsCannotMergeAndSourceRemainsActive() {
        masterFixture(()->{ for(int i=4;i<=8;i++)jdbc.sql("INSERT INTO platform.region(code,name,administrative_level,parent_code,sort_order) VALUES(:code,:name,'TOWNSHIP','230202',:sort)")
            .param("sort",991860+i).param("code","23020290"+i).param("name","手机号隔离验收"+i).update(); });
        masterFixture(()->{for(int i=4;i<=8;i++)grant(SOURCE,"23020290"+i);});
        var preview=identities.preview(TARGET,"13900000186","browser");
        assertThatThrownBy(()->identities.merge(TARGET,preview.ticketId(),"browser",PhoneIdentityService.RegionChoice.PHONE)).hasMessageContaining("5");
        assertThat(identities.login("13900000186").subject()).isEqualTo(SOURCE);
    }
    @Test void concurrentRegionGrantsCannotExceedFive() throws Exception {
        masterFixture(()->{ for(int i=4;i<=8;i++)jdbc.sql("INSERT INTO platform.region(code,name,administrative_level,parent_code,sort_order) VALUES(:code,:name,'TOWNSHIP','230202',:sort)")
            .param("sort",991860+i).param("code","23020290"+i).param("name","手机号隔离验收"+i).update(); });
        for(int i=4;i<=6;i++)grant(SOURCE,"23020290"+i);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            java.util.function.Function<String,Callable<Boolean>> action=region->()->{
                start.await();try{grant(SOURCE,region);return true;}catch(org.springframework.dao.DataIntegrityViolationException expected){return false;}
            };
            var first=pool.submit(action.apply("230202907"));var second=pool.submit(action.apply("230202908"));start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM platform.security_user_region_scope WHERE subject_id=:s AND valid_until IS NULL").param("s",SOURCE).query(Long.class).single()).isEqualTo(5);
    }
    @Test void fiveRegionMergePreservesAllChosenRegions() {
        masterFixture(()->{ for(int i=4;i<=7;i++)jdbc.sql("INSERT INTO platform.region(code,name,administrative_level,parent_code,sort_order) VALUES(:code,:name,'TOWNSHIP','230202',:sort)")
            .param("sort",991860+i).param("code","23020290"+i).param("name","手机号隔离验收"+i).update(); });
        for(int i=4;i<=7;i++)grant(SOURCE,"23020290"+i);
        var preview=identities.preview(TARGET,"13900000186","browser");
        identities.merge(TARGET,preview.ticketId(),"browser",PhoneIdentityService.RegionChoice.PHONE);
        assertThat(jdbc.sql("SELECT region_code FROM platform.security_user_region_scope WHERE subject_id=:s AND valid_until IS NULL ORDER BY region_code").param("s",TARGET).query(String.class).list())
            .containsExactly("230202901","230202904","230202905","230202906","230202907");
    }
    @Test void multipleAdministratorRolesHaveTheSameFullCatalogWithoutReservingRegions() {
        jdbc.sql("UPDATE platform.security_user_role SET role_code='BUSINESS_REVIEWER' WHERE subject_id=:s").param("s",SOURCE).update();
        jdbc.sql("UPDATE platform.security_user_role SET role_code='SYSTEM_ADMIN' WHERE subject_id=:s").param("s",TARGET).update();
        var repository=new JdbcSecurityPrincipalRepository(jdbc);
        var first=repository.findEnabled(SOURCE).orElseThrow();var second=repository.findEnabled(TARGET).orElseThrow();
        assertThat(first.isRootAdministrator()).isTrue();assertThat(second.isRootAdministrator()).isTrue();
        assertThat(first.permissionCodes()).containsExactlyInAnyOrderElementsOf(second.permissionCodes());
        assertThat(first.regionCodes()).containsExactlyInAnyOrderElementsOf(second.regionCodes());
        assertThat(first.permissionCodes()).contains("IDENTITY_ADMIN","BUSINESS_CREATE");
        assertThat(jdbc.sql("SELECT platform.employee_region_available('230202901',:s)").param("s",OTHER).query(Boolean.class).single()).isTrue();
        grant(SOURCE,"230202903");grant(TARGET,"230202903");
        assertThat(repository.findEnabled(OTHER).orElseThrow().isRootAdministrator()).isFalse();
    }
    @Test void wrongPurposeSessionExpiredAndReplayFailClosed(){
        UUID id=sms.send("13900000187","LOGIN","browser","client");
        assertThatThrownBy(()->sms.verify(id,"123456","BIND","browser")).isInstanceOf(ClientRequestException.class);
        assertThatThrownBy(()->sms.verify(id,"123456","LOGIN","other")).isInstanceOf(ClientRequestException.class);
        assertThatThrownBy(()->sms.verify(id,"000000","LOGIN","browser")).isInstanceOf(ClientRequestException.class);
        assertThat(jdbc.sql("SELECT attempts FROM platform.sms_challenge WHERE challenge_id=:id").param("id",id).query(Integer.class).single()).isEqualTo(1);
        assertThat(sms.verify(id,"123456","LOGIN","browser")).isEqualTo("13900000187");
        assertThatThrownBy(()->sms.verify(id,"123456","LOGIN","browser")).isInstanceOf(ClientRequestException.class);
        UUID expired=sms.send("13900000188","LOGIN","browser","client");
        jdbc.sql("UPDATE platform.sms_challenge SET expires_at=now()-interval '1 second' WHERE challenge_id=:id").param("id",expired).update();
        assertThatThrownBy(()->sms.verify(expired,"123456","LOGIN","browser")).isInstanceOf(ClientRequestException.class);
    }
    @Test void fiveAttemptsAndSendThrottlePersist(){
        UUID id=sms.send("13900000187","LOGIN","browser","client");
        for(int i=0;i<5;i++)assertThatThrownBy(()->sms.verify(id,"000000","LOGIN","browser")).isInstanceOf(ClientRequestException.class);
        assertThatThrownBy(()->sms.verify(id,"123456","LOGIN","browser")).isInstanceOf(ClientRequestException.class);
        assertThatThrownBy(()->sms.send("13900000187","BIND","other","client")).isInstanceOf(ClientRequestException.class);
    }
    @Test void duplicatePhoneCannotRebind(){assertThatThrownBy(()->identities.bind(TARGET,"13900000186")).hasMessageContaining("绑定");assertThat(identities.login("13900000186").subject()).isEqualTo(SOURCE);}
    @Test void phoneChoiceTransfersAndRevokesWithoutElevating(){
        var preview=identities.preview(TARGET,"13900000186","browser");
        identities.merge(TARGET,preview.ticketId(),"browser",PhoneIdentityService.RegionChoice.PHONE);
        assertThat(identities.login("13900000186").subject()).isEqualTo(TARGET);
        assertThat(jdbc.sql("SELECT enabled FROM platform.security_user WHERE subject_id=:s").param("s",SOURCE).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT session_version FROM platform.security_user WHERE subject_id=:s").param("s",SOURCE).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT region_code FROM platform.security_user_region_scope WHERE subject_id=:s AND valid_until IS NULL").param("s",TARGET).query(String.class).list()).containsExactly("230202901");
        assertThat(jdbc.sql("SELECT platform.employee_region_available('230202902',:s)").param("s",OTHER).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT role_code FROM platform.security_user_role WHERE subject_id=:s").param("s",TARGET).query(String.class).list()).containsExactly("BUSINESS_OPERATOR");
        assertThatThrownBy(()->identities.merge(TARGET,preview.ticketId(),"browser",PhoneIdentityService.RegionChoice.PHONE)).hasMessageContaining("过期");
    }
    @Test void originalChoiceKeepsOnlyOriginalAndPreservesHistory(){
        var preview=identities.preview(TARGET,"13900000186","browser");
        identities.merge(TARGET,preview.ticketId(),"browser",PhoneIdentityService.RegionChoice.ORIGINAL);
        assertThat(jdbc.sql("SELECT region_code FROM platform.security_user_region_scope WHERE subject_id=:s AND valid_until IS NULL").param("s",TARGET).query(String.class).list()).containsExactly("230202902");
        assertThat(jdbc.sql("SELECT count(*) FROM platform.security_user_region_scope WHERE subject_id=:s AND valid_until IS NOT NULL").param("s",SOURCE).query(Long.class).single()).isEqualTo(1);
    }
    @Test void changedPreviewAndWrongSessionCannotCommit(){
        var preview=identities.preview(TARGET,"13900000186","browser");
        assertThatThrownBy(()->identities.merge(TARGET,preview.ticketId(),"other",PhoneIdentityService.RegionChoice.PHONE)).hasMessageContaining("过期");
        jdbc.sql("UPDATE platform.security_user SET version=version+1 WHERE subject_id=:s").param("s",SOURCE).update();
        assertThatThrownBy(()->identities.merge(TARGET,preview.ticketId(),"browser",PhoneIdentityService.RegionChoice.PHONE)).hasMessageContaining("变化");
        assertThat(identities.login("13900000186").subject()).isEqualTo(SOURCE);
    }
    @Test void inactiveRegionsAreFreeButReactivationCannotSteal(){
        jdbc.sql("UPDATE platform.security_user SET enabled=false,account_status='REVOKED' WHERE subject_id=:s").param("s",SOURCE).update();
        grant(OTHER,"230202901");
        assertThatThrownBy(()->jdbc.sql("UPDATE platform.security_user SET enabled=true,account_status='ACTIVE' WHERE subject_id=:s").param("s",SOURCE).update()).hasMessageContaining("region");
    }
    @Test void legacyRegionConflictRollsBackEntireMerge() {
        // Model an overlap preserved by V185, not a new write bypass in the service.
        masterFixture(()->grant(OTHER,"230202901"));
        var preview=identities.preview(TARGET,"13900000186","browser");
        assertThatThrownBy(()->identities.merge(TARGET,preview.ticketId(),"browser",PhoneIdentityService.RegionChoice.PHONE)).hasMessageContaining("其他有效账号");
        assertThat(identities.login("13900000186").subject()).isEqualTo(SOURCE);
        assertThat(jdbc.sql("SELECT session_version FROM platform.security_user WHERE subject_id=:s").param("s",SOURCE).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.phone_merge_history WHERE source_subject=:s").param("s",SOURCE).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT region_code FROM platform.security_user_region_scope WHERE subject_id=:s AND valid_until IS NULL").param("s",TARGET).query(String.class).list()).containsExactly("230202902");
    }
    @Test void responsibilityMergeAlsoMovesAndReleasesActualSamples() {
        for(var pair:List.of(List.of(SOURCE,"230202901"),List.of(TARGET,"230202902"))) {
            jdbc.sql("INSERT INTO platform.region_responsibility(region_code,subject_id,updated_by,reason) VALUES(:r,:s,:s,'隔离验收')").param("r",pair.get(1)).param("s",pair.getFirst()).update();
            jdbc.sql("INSERT INTO registry.sample_point(sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,effective_from,created_by,updated_by,maintainer_subject_id) VALUES(:id,'SURVEY_SITE','手机号合并隔离样本',:r,'DRAFT','MISSING',CURRENT_DATE,:s,:s,:s)")
                    .param("id",UUID.randomUUID()).param("r",pair.get(1)).param("s",pair.getFirst()).update();
        }
        var preview=identities.preview(TARGET,"13900000186","browser");
        identities.merge(TARGET,preview.ticketId(),"browser",PhoneIdentityService.RegionChoice.PHONE);
        assertThat(jdbc.sql("SELECT maintainer_subject_id FROM registry.sample_point WHERE region_code='230202901'").query(String.class).single()).isEqualTo(TARGET);
        assertThat(jdbc.sql("SELECT count(*) FROM registry.sample_point WHERE region_code='230202902' AND maintainer_subject_id IS NULL").query(Long.class).single()).isEqualTo(1);
    }
    @Test void accessScopeDoesNotReassignThirdPartySamples() {
        jdbc.sql("INSERT INTO registry.sample_point(sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,effective_from,created_by,updated_by,maintainer_subject_id) VALUES(:id,'SURVEY_SITE','手机号合并隔离样本','230202901','DRAFT','MISSING',CURRENT_DATE,:s,:s,:s)")
                .param("id",UUID.randomUUID()).param("s",OTHER).update();
        var preview=identities.preview(TARGET,"13900000186","browser");
        identities.merge(TARGET,preview.ticketId(),"browser",PhoneIdentityService.RegionChoice.PHONE);
        assertThat(jdbc.sql("SELECT maintainer_subject_id FROM registry.sample_point WHERE canonical_name='手机号合并隔离样本'").query(String.class).single()).isEqualTo(OTHER);
    }
    @Test void onlyLiteralAdminHasAllocationException() {
        assertThat(jdbc.sql("SELECT platform.employee_region_available('230202901','admin')").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT platform.employee_region_available('230202901','Admin')").query(Boolean.class).single()).isFalse();
    }
    @Test void concurrentMergeConsumesOneTicket() throws Exception {
        var preview=identities.preview(TARGET,"13900000186","browser");
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> merge=()->{try{identities.merge(TARGET,preview.ticketId(),"browser",PhoneIdentityService.RegionChoice.ORIGINAL);return true;}catch(RuntimeException e){return false;}};
            var results=pool.invokeAll(List.of(merge,merge));
            assertThat(results.stream().filter(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).count()).isEqualTo(1);
        }
    }
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    static class Config {
        @Bean DriverManagerDataSource dataSource(){return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55435/qiqihar_enterprise_test","postgres","");}
        @Bean JdbcClient jdbc(DriverManagerDataSource ds){return JdbcClient.create(ds);}
        @Bean DataSourceTransactionManager transactionManager(DriverManagerDataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean SmsChallengeService sms(JdbcClient jdbc){return new SmsChallengeService(jdbc,new SmsVerificationGateway(){public void send(String p,String u,String i){} public boolean verify(String p,String c,String i){return c.equals("123456");}},true);}
        @Bean PhoneIdentityService identities(JdbcClient jdbc){return new PhoneIdentityService(jdbc,mock(EmployeeRegistrationService.class),new JdbcIdentitySessionInvalidator(jdbc,Clock.systemUTC()),new JdbcSecurityPrincipalRepository(jdbc));}
    }
}
