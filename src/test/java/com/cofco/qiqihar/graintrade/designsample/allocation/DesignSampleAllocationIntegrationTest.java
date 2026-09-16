package com.cofco.qiqihar.graintrade.designsample.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
@Transactional
class DesignSampleAllocationIntegrationTest {
    private static final String TOWNSHIP = "990209001";
    @Autowired DesignSampleAllocationPlanner planner;
    @Autowired DesignSampleTownshipWriter writer;
    @Autowired DesignSampleAllocationService allocationService;
    @Autowired JdbcClient jdbc;

    @Test
    void plansPointTouchingVillagesAndCreatesStablePointsInsideRealBoundaries() {
        fixtures();

        var plan = planner.planTownship(TOWNSHIP, "设计样本测试乡");
        assertThat(plan.blockers()).isEmpty();
        assertThat(plan.selectedVillageCodes()).hasSize(3);
        assertThat(plan.adjacencyEdgeCount()).isEqualTo(6);
        assertThat(plan.projectedCreated()).isEqualTo(3);
        assertThat(plan.coverageProof()).hasSize(4)
                .allSatisfy((village,sample)->assertThat(
                        village.equals(sample)||touches(village,sample)).isTrue());
        assertThat(jdbc.sql("""
                SELECT ST_Touches(left_boundary.geometry,right_boundary.geometry)
                FROM overview.administrative_boundary left_boundary
                JOIN overview.administrative_boundary right_boundary ON right_boundary.region_code='990209001004'
                WHERE left_boundary.region_code='990209001001'
                """).query(Boolean.class).single()).isTrue();

        var first = writer.apply(TOWNSHIP, "allocation-test", UUID.fromString("20900000-0000-0000-0000-000000000001"));
        assertThat(first.blockers()).isEmpty();
        assertThat(first.created()).isEqualTo(3);
        assertThat(activeCount()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT bool_and(ST_Covers(boundary.geometry,point.governed_point))
                FROM platform.design_sample_point point
                JOIN overview.administrative_boundary boundary ON boundary.region_code=point.region_code
                WHERE point.assignment_run_id='20900000-0000-0000-0000-000000000001'
                """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT bool_and(point.sample_name=region.name||'设计样本点'
                    AND point.detailed_address LIKE '%村内设计样本点')
                FROM platform.design_sample_point point
                JOIN platform.region region ON region.code=point.region_code
                WHERE point.lifecycle_status='ACTIVE' AND region.parent_code=:township
                """).param("township", TOWNSHIP).query(Boolean.class).single()).isTrue();

        var second = writer.apply(TOWNSHIP, "allocation-test", UUID.fromString("20900000-0000-0000-0000-000000000002"));
        assertThat(second.created()).isZero();
        assertThat(second.moved()).isZero();
        assertThat(second.expired()).isZero();
        assertThat(activeCount()).isEqualTo(3);
    }

    @Test
    void surplusActiveRecordExpiresAndRemainsStored() {
        fixtures();
        for (int index = 1; index <= 4; index++) {
            insertExisting(index);
        }

        var result = writer.apply(TOWNSHIP, "allocation-test", UUID.fromString("20900000-0000-0000-0000-000000000003"));

        assertThat(result.expired()).isOne();
        assertThat(activeCount()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.design_sample_point point
                JOIN platform.region region ON region.code=point.region_code
                WHERE region.parent_code=:township AND point.lifecycle_status='EXPIRED'
                """).param("township", TOWNSHIP).query(Long.class).single()).isOne();
    }

    @Test
    void batchMarksSurplusAndReusesItBeforeCreatingNewRecords() {
        fixtures();
        UUID recyclable = UUID.fromString("20900000-0000-0000-0000-000000000020");
        UUID previouslyExpired = UUID.fromString("20900000-0000-0000-0000-000000000021");
        insertCountyExisting(recyclable, "ACTIVE", null);
        insertCountyExisting(previouslyExpired, "EXPIRED", "20900000-0000-0000-0000-000000000099");
        var plan = planner.planTownship(TOWNSHIP, "设计样本测试乡");
        var projected = allocationService.preflight().townships().stream()
                .filter(value -> TOWNSHIP.equals(value.townshipCode())).findFirst().orElseThrow();

        assertThat(projected.projectedMoved()).isOne();
        assertThat(projected.projectedCreated()).isEqualTo(2);

        var result = writer.apply(plan.townshipCode(), "allocation-test",
                UUID.fromString("20900000-0000-0000-0000-000000000022"));

        assertThat(result.moved()).isOne();
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.newlyExpired()).isOne();
        assertThat(activeCount()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT lifecycle_status FROM platform.design_sample_point WHERE design_sample_point_id=:id")
                .param("id", recyclable).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT assignment_run_id FROM platform.design_sample_point WHERE design_sample_point_id=:id")
                .param("id", recyclable).query(UUID.class).single())
                .isEqualTo(UUID.fromString("20900000-0000-0000-0000-000000000022"));
        assertThat(jdbc.sql("SELECT lifecycle_status FROM platform.design_sample_point WHERE design_sample_point_id=:id")
                .param("id", previouslyExpired).query(String.class).single()).isEqualTo("EXPIRED");
    }

    @Test
    void batchLeavesUnusedCurrentBatchSurplusMarkedExpired() {
        fixtures();
        UUID runId = UUID.fromString("20900000-0000-0000-0000-000000000030");
        for (int index = 0; index < 4; index++) {
            insertCountyExisting(UUID.fromString("20900000-0000-0000-0000-00000000003" + (index + 1)), "ACTIVE", null);
        }

        var result = writer.apply(TOWNSHIP, "allocation-test", runId);

        assertThat(result.moved()).isEqualTo(3);
        assertThat(result.created()).isZero();
        assertThat(result.newlyExpired()).isEqualTo(4);
        assertThat(activeCount()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.design_sample_point
                WHERE assignment_run_id=:run AND lifecycle_status='EXPIRED'
                """).param("run", runId).query(Long.class).single()).isOne();
    }

    private long activeCount() {
        return jdbc.sql("""
                SELECT count(*) FROM platform.design_sample_point point
                JOIN platform.region region ON region.code=point.region_code
                WHERE region.parent_code=:township AND point.lifecycle_status='ACTIVE'
                """).param("township", TOWNSHIP).query(Long.class).single();
    }

    private void fixtures() {
        jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES('ALLOCATION_TEST','设计样本测试单位',99212) ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code) VALUES('allocation-test','设计样本测试员','ALLOCATION_TEST') ON CONFLICT DO NOTHING").update();
        GovernedMasterDataFixtures.insertRegion(
                jdbc, TOWNSHIP, "设计样本测试乡", "230202", "TOWNSHIP", 99209);
        for (int index = 1; index <= 4; index++) {
            GovernedMasterDataFixtures.insertRegion(
                    jdbc, village(index), "设计样本测试村" + index, TOWNSHIP, "VILLAGE", index);
        }
        boundary(1, 120, 45, 121, 46);
        boundary(2, 121, 45, 122, 46);
        boundary(3, 120, 46, 121, 47);
        boundary(4, 121, 46, 122, 47);
        for (int index = 1; index <= 4; index++) {
            GovernedMasterDataFixtures.publishBoundary(jdbc, village(index));
        }
    }

    private void boundary(int index, int minX, int minY, int maxX, int maxY) {
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES(:region,ST_Multi(ST_MakeEnvelope(:minX,:minY,:maxX,:maxY,4326)),
                  '设计样本测试边界','urn:test:design-sample-allocation','test-v1','Test fixture',
                  :region,DATE '2026-09-16',encode(sha256(ST_AsEWKB(ST_Multi(ST_MakeEnvelope(:minX,:minY,:maxX,:maxY,4326)))),'hex'))
                """).param("region", village(index)).param("minX", minX).param("minY", minY)
                .param("maxX", maxX).param("maxY", maxY).update();
    }

    private void insertExisting(int index) {
        String village = village(index);
        jdbc.sql("""
                INSERT INTO platform.design_sample_point(design_sample_point_id,contract_version,domain_code,
                  product_code,object_type_code,values_json,sample_name,region_code,governed_point,
                  idempotency_key,request_digest,created_by,updated_by)
                VALUES(:id,'design-sample-fields-v3','REFERENCE','GENERAL','REFERENCE_POINT','{}',
                  :name,:region,ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326),:key,repeat('a',64),
                  'allocation-test','allocation-test')
                """).param("id", UUID.randomUUID()).param("name", "旧设计样本" + index)
                .param("region", village).param("longitude", index % 2 == 1 ? 120.5 : 121.5)
                .param("latitude", index <= 2 ? 45.5 : 46.5)
                .param("key", "existing-" + index).update();
    }

    private void insertCountyExisting(UUID id, String status, String assignmentRun) {
        jdbc.sql("""
                INSERT INTO platform.design_sample_point(design_sample_point_id,contract_version,domain_code,
                  product_code,object_type_code,values_json,sample_name,region_code,governed_point,
                  idempotency_key,request_digest,created_by,updated_by,lifecycle_status,expired_at,assignment_run_id)
                VALUES(:id,'design-sample-fields-v3','REFERENCE','GENERAL','REFERENCE_POINT','{}',
                  :name,'230202',ST_SetSRID(ST_MakePoint(123.9,47.3),4326),:key,repeat('b',64),
                  'allocation-test','allocation-test',:status,
                  CASE WHEN :status='EXPIRED' THEN now() ELSE NULL END,CAST(:run AS uuid))
                """).param("id", id).param("name", "县级待复用样本" + id)
                .param("key", "county-existing-" + id).param("status", status)
                .param("run", assignmentRun).update();
    }

    private boolean touches(String left,String right) {
        return jdbc.sql("""
                SELECT ST_Touches(a.geometry,b.geometry)
                FROM overview.administrative_boundary a
                JOIN overview.administrative_boundary b ON b.region_code=:right
                WHERE a.region_code=:left
                """).param("left",left).param("right",right).query(Boolean.class).single();
    }

    private static String village(int index) {
        return "99020900100" + index;
    }
}
