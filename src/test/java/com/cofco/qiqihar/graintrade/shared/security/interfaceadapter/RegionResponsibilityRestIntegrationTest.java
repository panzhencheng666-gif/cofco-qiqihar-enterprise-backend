package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class RegionResponsibilityRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    JdbcClient jdbc;
    String subject;
    static final String REGION="230202996";
    @BeforeEach void prepare() {
        jdbc=JdbcClient.create(dataSource);
        subject="region-responsibility-"+UUID.randomUUID();
        jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES('QIQIHAR_BUSINESS','地区责任测试单位',9980) ON CONFLICT DO NOTHING").update();
        GovernedMasterDataFixtures.insertRegion(jdbc,REGION,"地区责任测试乡镇","230202","TOWNSHIP",9996);
        jdbc.sql("INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code) VALUES('QIQIHAR_BUSINESS',:region),('TEST',:region) ON CONFLICT DO NOTHING").param("region",REGION).update();
        jdbc.sql("INSERT INTO platform.security_user_region_scope(subject_id,region_code) VALUES('production-tester',:region) ON CONFLICT DO NOTHING").param("region",REGION).update();
        jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled,account_status,employment_status) VALUES(:subject,'地区责任专用员工','QIQIHAR_BUSINESS',true,'ACTIVE','ACTIVE')").param("subject",subject).update();
        jdbc.sql("INSERT INTO platform.security_user_role(subject_id,role_code) VALUES(:subject,'BUSINESS_OPERATOR')").param("subject",subject).update();
    }
    @Test void savesIndependentResponsibilityAndNewSamplesInheritIt() throws Exception {
        UUID first=sample();
        String preview=preview();
        String token=json.readTree(preview).path("data").path("previewToken").asText();
        mvc.perform(put(path()).principal(()->"production-tester").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("regionCodes",java.util.List.of(REGION),"previewToken",token,"reason","岗位分工"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.regionCodes[0]").value(REGION));
        assertThat(owner(first)).isEqualTo(subject);
        assertThat(owner(sample())).isEqualTo(subject);
        mvc.perform(get(path()).principal(()->"production-tester"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.regionCodes[0]").value(REGION));
        assertThat(jdbc.sql("SELECT count(*) FROM platform.business_event_outbox WHERE action_code='REGION_RESPONSIBILITY_CHANGED' AND aggregate_id=:subject").param("subject",subject).query(Long.class).single()).isPositive();
    }
    @Test void rejectsStalePreviewWhenSampleSetChanges() throws Exception {
        String token=token();
        sample();
        save(token).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REGION_RESPONSIBILITY_CONFLICT"));
        assertThat(jdbc.sql("SELECT count(*) FROM platform.region_responsibility WHERE subject_id=:subject").param("subject",subject).query(Long.class).single()).isZero();
    }
    @Test void rollsBackResponsibilityAndSamplesWhenAuditFails() throws Exception {
        UUID id=sample();String previous=owner(id);String token=token();
        jdbc.sql("CREATE FUNCTION platform.reject_region_audit_test() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.action_code=''REGION_RESPONSIBILITY_CHANGED'' THEN RAISE EXCEPTION ''test audit failure''; END IF; RETURN NEW; END'").update();
        jdbc.sql("CREATE TRIGGER reject_region_audit_test BEFORE INSERT ON platform.business_audit_event FOR EACH ROW EXECUTE FUNCTION platform.reject_region_audit_test()").update();
        try {
            save(token).andExpect(status().is5xxServerError());
            assertThat(owner(id)).isEqualTo(previous);
            assertThat(jdbc.sql("SELECT count(*) FROM platform.region_responsibility WHERE subject_id=:subject").param("subject",subject).query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER reject_region_audit_test ON platform.business_audit_event").update();
            jdbc.sql("DROP FUNCTION platform.reject_region_audit_test()").update();
        }
    }
    @Test void rejectsOldSingleSampleReassignmentAndKeepsHistoricalReporter() throws Exception {
        UUID id=sample();UUID observation=UUID.randomUUID();
        jdbc.sql("INSERT INTO platform.formal_sample_observation(observation_id,source_domain,source_record_id,sample_point_id,product_code,observed_at,official_saved_at,actor_subject_id,idempotency_key,request_sha256,projection_version,response_json) VALUES(:observation,'PRODUCTION',:source,:id,'CORN',now(),now(),'production-tester',:source,repeat('a',64),'test','{}')")
            .param("observation",observation).param("source",observation.toString()).param("id",id).update();
        save(token()).andExpect(status().isOk());
        long version=jdbc.sql("SELECT version FROM registry.sample_point WHERE sample_point_id=:id").param("id",id).query(Long.class).single();
        mvc.perform(put("/api/v1/formal-sample-points/"+id+"/maintainer").principal(()->"production-tester").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("maintainerSubjectId","production-tester","maintainerChangeReason","旧入口试图覆盖","expectedVersion",version))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REGION_RESPONSIBILITY_CONFLICT"));
        assertThat(owner(id)).isEqualTo(subject);
        assertThat(jdbc.sql("SELECT actor_subject_id FROM platform.formal_sample_observation WHERE observation_id=:id").param("id",observation).query(String.class).single()).isEqualTo("production-tester");
    }
    @Test void rejectsDisabledEmployeeAndOutsideRegion() throws Exception {
        mvc.perform(post(path()+"/preview").principal(()->"production-tester").contentType(MediaType.APPLICATION_JSON).content("{\"regionCodes\":[\"230208\"]}"))
            .andExpect(status().isForbidden());
        jdbc.sql("UPDATE platform.security_user SET enabled=false WHERE subject_id=:subject").param("subject",subject).update();
        mvc.perform(post(path()+"/preview").principal(()->"production-tester").contentType(MediaType.APPLICATION_JSON).content("{\"regionCodes\":[\""+REGION+"\"]}"))
            .andExpect(status().isBadRequest());
    }
    @Test void allowsOnlyOneConcurrentHandoverFromTheSamePreview() throws Exception {
        String token=token();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> action=()->{start.await();return save(token).andReturn().getResponse().getStatus();};
            var first=pool.submit(action);var second=pool.submit(action);start.countDown();
            assertThat(java.util.List.of(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200,409);
        }
    }
    @Test void runtimeRoleCanLockAndReadResponsibilityWithoutCatalogWritePrivileges() throws Exception {
        try(var connection=dataSource.getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()){
                statement.execute("SET LOCAL ROLE qiqihar_enterprise_runtime");
                statement.execute("SELECT platform.lock_region_responsibility_change()");
                statement.execute("SELECT * FROM platform.region_responsibility");
            } finally {connection.rollback();}
        }
    }
    @Test void removingAllRegionsReleasesCurrentSamplesAndFutureSamples() throws Exception {
        UUID id=sample();save(token()).andExpect(status().isOk());
        var preview=mvc.perform(post(path()+"/preview").principal(()->"production-tester").contentType(MediaType.APPLICATION_JSON)
            .content("{\"regionCodes\":[]}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var token=json.readTree(preview).path("data").path("previewToken").asText();
        mvc.perform(put(path()).principal(()->"production-tester").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("regionCodes",java.util.List.of(),"previewToken",token,"reason","撤销地区分工"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.regionCodes").isEmpty());
        assertThat(owner(id)).isEmpty();assertThat(owner(sample())).isEmpty();
    }
    String token() throws Exception {return json.readTree(preview()).path("data").path("previewToken").asText();}
    org.springframework.test.web.servlet.ResultActions save(String token) throws Exception {
        return mvc.perform(put(path()).principal(()->"production-tester").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("regionCodes",java.util.List.of(REGION),"previewToken",token,"reason","岗位交接"))));
    }
    String preview() throws Exception {
        return mvc.perform(post(path()+"/preview").principal(()->"production-tester").contentType(MediaType.APPLICATION_JSON)
            .content("{\"regionCodes\":[\""+REGION+"\"]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    String path(){return "/api/v1/identity/employees/"+subject+"/region-responsibility";}
    UUID sample(){
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO registry.sample_point(sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,effective_from,created_by,updated_by) VALUES(:id,'SURVEY_SITE','地区责任专用样本',:region,'APPROVED','MISSING',DATE '2026-01-01','production-tester','production-tester')").param("id",id).param("region",REGION).update();
        return id;
    }
    String owner(UUID id){return jdbc.sql("SELECT coalesce(maintainer_subject_id,'') FROM registry.sample_point WHERE sample_point_id=:id").param("id",id).query(String.class).single();}
}
