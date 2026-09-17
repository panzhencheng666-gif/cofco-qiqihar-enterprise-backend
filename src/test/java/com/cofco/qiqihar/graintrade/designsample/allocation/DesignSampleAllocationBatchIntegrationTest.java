package com.cofco.qiqihar.graintrade.designsample.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class DesignSampleAllocationBatchIntegrationTest {
    private static final String ACTOR="allocation-batch-test";
    private static final List<String> TOWNSHIPS=List.of("990209101","990209102");
    @Autowired DesignSampleAllocationService service;
    @Autowired JdbcClient jdbc;

    @Test
    void reusesSurplusFromLaterTownshipBeforeCreatingForEarlierTownship() {
        fixtures();
        try {
            jdbc.sql("""
                    INSERT INTO overview.administrative_boundary(region_code,geometry,source_name,source_url,
                      source_revision,source_license,source_feature_id,source_effective_on,geometry_sha256)
                    VALUES(:region,ST_Multi(ST_MakeEnvelope(123,45,125,47,4326)),
                      '批次测试乡边界','urn:test:design-sample-batch','test-v1','Test fixture',:region,
                      DATE '2026-09-17',encode(sha256(ST_AsEWKB(ST_Multi(ST_MakeEnvelope(123,45,125,47,4326)))),'hex'))
                    """).param("region",TOWNSHIPS.get(1)).update();
            GovernedMasterDataFixtures.publishBoundary(jdbc,TOWNSHIPS.get(1));
            jdbc.sql("""
                    UPDATE platform.design_sample_point SET region_code=:region,
                      governed_point=ST_SetSRID(ST_MakePoint(124,46),4326)
                    WHERE created_by=:actor
                    """).param("region",TOWNSHIPS.get(1)).param("actor",ACTOR).update();
            var plans=service.preflight().townships().stream()
                    .filter(plan->TOWNSHIPS.contains(plan.townshipCode())).toList();
            assertThat(plans.stream().mapToInt(DesignSampleTownshipPlan::projectedCreated).sum()).isZero();
            var result=service.apply(ACTOR);
            assertThat(result.created()).isZero();
            assertThat(result.reused()).isEqualTo(6);
            assertThat(result.expired()).isOne();
            assertThat(result.townships().stream().filter(t->TOWNSHIPS.contains(t.townshipCode())))
                    .allSatisfy(t->assertThat(t.blockers()).isEmpty());
        } finally {
            jdbc.sql("DELETE FROM platform.design_sample_point WHERE created_by=:actor OR updated_by=:actor")
                    .param("actor",ACTOR).update();
            jdbc.sql("DELETE FROM overview.administrative_boundary_render WHERE region_code=:region")
                    .param("region",TOWNSHIPS.get(1)).update();
            jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code=:region")
                    .param("region",TOWNSHIPS.get(1)).update();
            cleanup();
        }
    }

    @Test
    void commitsTownshipsSeparatelyAndCarriesTheCurrentBatchPoolAcrossThem() {
        fixtures();
        try {
            var preflight=service.preflight();
            var plans=preflight.townships().stream().filter(plan->TOWNSHIPS.contains(plan.townshipCode())).toList();
            assertThat(plans).hasSize(2).allSatisfy(plan->{
                assertThat(plan.ready()).isTrue();assertThat(plan.projectedCreated()).isZero();
                assertThat(plan.projectedMoved()).isEqualTo(3);
            });
            assertThat(plans.stream().mapToInt(DesignSampleTownshipPlan::projectedExpired).sum()).isOne();

            var result=service.apply(ACTOR);

            assertThat(result.reused()).isEqualTo(6);
            assertThat(result.created()).isZero();
            assertThat(result.expired()).isOne();
            assertThat(result.townships().stream().filter(value->TOWNSHIPS.contains(value.townshipCode())))
                    .hasSize(2).allSatisfy(value->assertThat(value.blockers()).isEmpty());
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM platform.design_sample_point
                    WHERE assignment_run_id=:run AND lifecycle_status='ACTIVE'
                    """).param("run",result.runId()).query(Long.class).single()).isEqualTo(6);
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM platform.design_sample_point
                    WHERE assignment_run_id=:run AND lifecycle_status='EXPIRED'
                    """).param("run",result.runId()).query(Long.class).single()).isOne();
        } finally {
            cleanup();
        }
    }

    @Test
    void resumesTheSameRunAndExpiresSurplusNewRecordsWithoutDeletingThem() {
        fixtures();
        UUID run=UUID.fromString("20910000-0000-0000-0000-000000000099");
        UUID original=UUID.fromString("20910000-0000-0000-0000-000000000001");
        UUID surplusNew=UUID.fromString("20910000-0000-0000-0000-000000000008");
        try {
            List<String> selectedVillages=List.copyOf(service.preflight().townships().stream()
                    .filter(plan->TOWNSHIPS.getFirst().equals(plan.townshipCode()))
                    .findFirst().orElseThrow().selectedVillageCodes());
            jdbc.sql("""
                    UPDATE platform.design_sample_point SET region_code=:region,assignment_run_id=:run,
                      governed_point=ST_SetSRID(ST_MakePoint(120.5,45.5),4326),
                      created_at=now()-interval '1 day'
                    WHERE design_sample_point_id=:id
                    """).param("region",selectedVillages.getFirst()).param("run",run).param("id",original).update();
            jdbc.sql("""
                    UPDATE platform.design_sample_point SET lifecycle_status='EXPIRED',expired_at=now(),
                      assignment_run_id=:run
                    WHERE design_sample_point_id IN (:ids)
                    """).param("run",run).param("ids",List.of(
                            UUID.fromString("20910000-0000-0000-0000-000000000002"),
                            UUID.fromString("20910000-0000-0000-0000-000000000003"))).update();
            jdbc.sql("""
                    INSERT INTO platform.design_sample_point(design_sample_point_id,contract_version,domain_code,
                      product_code,object_type_code,values_json,sample_name,region_code,governed_point,
                      idempotency_key,request_digest,created_by,updated_by,assignment_run_id)
                    VALUES(:id,'design-sample-fields-v3','REFERENCE','GENERAL','REFERENCE_POINT','{}',
                      '批次错误新增样本',:region,ST_SetSRID(ST_MakePoint(120.5,45.5),4326),
                      :key,repeat('d',64),:actor,:actor,:run)
                    """).param("id",surplusNew).param("region",selectedVillages.get(1)).param("actor",ACTOR)
                    .param("key","allocation:"+run+":"+selectedVillages.get(1)).param("run",run).update();

            var result=service.resume(ACTOR,run);

            assertThat(result.runId()).isEqualTo(run);
            assertThat(result.created()).isZero();
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM platform.design_sample_point
                    WHERE assignment_run_id=:run AND lifecycle_status='ACTIVE'
                    """).param("run",run).query(Long.class).single()).isEqualTo(6);
            assertThat(jdbc.sql("""
                    SELECT lifecycle_status FROM platform.design_sample_point
                    WHERE design_sample_point_id=:id
                    """).param("id",original).query(String.class).single()).isEqualTo("ACTIVE");
            assertThat(jdbc.sql("""
                    SELECT lifecycle_status FROM platform.design_sample_point
                    WHERE design_sample_point_id=:id
                    """).param("id",surplusNew).query(String.class).single()).isEqualTo("EXPIRED");
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM platform.design_sample_point
                    WHERE design_sample_point_id=:id
                    """).param("id",surplusNew).query(Long.class).single()).isOne();
        } finally {
            cleanup();
        }
    }

    private void fixtures(){
        jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES('ALLOCATION_BATCH_TEST','批次分配测试单位',99213) ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES(:actor,'批次分配测试员','ALLOCATION_BATCH_TEST') ON CONFLICT DO NOTHING")
                .param("actor",ACTOR).update();
        int townIndex=0;
        for(String township:TOWNSHIPS){
            GovernedMasterDataFixtures.insertRegion(jdbc,township,"批次测试乡"+(++townIndex),"230202","TOWNSHIP",99300+townIndex);
            for(int villageIndex=1;villageIndex<=4;villageIndex++){
                String village=township+"00"+villageIndex;
                GovernedMasterDataFixtures.insertRegion(jdbc,village,"批次测试村"+townIndex+"-"+villageIndex,township,"VILLAGE",villageIndex);
                int baseX=120+(townIndex-1)*3;int offsetX=(villageIndex-1)%2;int offsetY=(villageIndex-1)/2;
                jdbc.sql("""
                        INSERT INTO overview.administrative_boundary(region_code,geometry,source_name,source_url,
                          source_revision,source_license,source_feature_id,source_effective_on,geometry_sha256)
                        VALUES(:region,ST_Multi(ST_MakeEnvelope(:minX,:minY,:maxX,:maxY,4326)),
                          '批次分配测试边界','urn:test:design-sample-batch','test-v1','Test fixture',:region,
                          DATE '2026-09-16',encode(sha256(ST_AsEWKB(ST_Multi(ST_MakeEnvelope(:minX,:minY,:maxX,:maxY,4326)))),'hex'))
                        """).param("region",village).param("minX",baseX+offsetX).param("minY",45+offsetY)
                        .param("maxX",baseX+offsetX+1).param("maxY",46+offsetY).update();
                GovernedMasterDataFixtures.publishBoundary(jdbc,village);
            }
        }
        for(int index=1;index<=7;index++)jdbc.sql("""
                INSERT INTO platform.design_sample_point(design_sample_point_id,contract_version,domain_code,
                  product_code,object_type_code,values_json,sample_name,region_code,governed_point,
                  idempotency_key,request_digest,created_by,updated_by)
                VALUES(:id,'design-sample-fields-v3','REFERENCE','GENERAL','REFERENCE_POINT','{}',
                  :name,'230202',ST_SetSRID(ST_MakePoint(123.9,47.3),4326),:key,repeat('c',64),:actor,:actor)
                """).param("id",UUID.fromString("20910000-0000-0000-0000-00000000000"+index))
                .param("name","批次县级待复用样本"+index).param("key","batch-county-existing-"+index)
                .param("actor",ACTOR).update();
    }

    private void cleanup(){
        jdbc.sql("DELETE FROM platform.design_sample_point WHERE created_by=:actor OR updated_by=:actor")
                .param("actor",ACTOR).update();
        List<String> villages=TOWNSHIPS.stream().flatMap(township->java.util.stream.IntStream.rangeClosed(1,4)
                .mapToObj(index->township+"00"+index)).toList();
        jdbc.sql("DELETE FROM overview.administrative_boundary_render WHERE region_code IN (:regions)")
                .param("regions",villages).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code IN (:regions)")
                .param("regions",villages).update();
        List<String> regions=new ArrayList<>(villages);regions.addAll(TOWNSHIPS);
        GovernedMasterDataFixtures.deleteRegions(jdbc,regions);
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:actor").param("actor",ACTOR).update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code='ALLOCATION_BATCH_TEST'").update();
    }
}
