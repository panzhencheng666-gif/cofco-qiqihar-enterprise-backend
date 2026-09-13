package com.cofco.qiqihar.graintrade.shared.security.application;

import static org.assertj.core.api.Assertions.*;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.shared.security.infrastructure.JdbcSecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.*;
import com.cofco.qiqihar.graintrade.formalsampleobservation.infrastructure.JdbcFormalSampleObservationRepository;
import com.cofco.qiqihar.graintrade.overview.infrastructure.JdbcOverviewSamplePointRepository;
import com.cofco.qiqihar.graintrade.production.infrastructure.JdbcProductionRecordRepository;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** All fixture writes roll back and may only use the guarded, isolated test database. */
class BusinessTaskScopeDatabaseTest {
    private static final String FIRST = "230221990", SECOND = "230221991";
    private static final OffsetDateTime OBSERVED = OffsetDateTime.parse("2026-09-12T12:00:00+08:00");

    @Test void currentGrantsControlTasksWhileGlobalReadsAndAdministratorSqlRemainUnrestricted() {
        var database = ProtectedTestDatabase.shared();
        database.flyway().migrate();
        var ds = database.dataSource();
        var jdbc = JdbcClient.create(ds);
        new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status -> {
            status.setRollbackOnly();
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String unit = "scope-" + suffix, old = "scope-old-" + suffix,
                    current = "scope-current-" + suffix, empty = "scope-empty-" + suffix;
            String root = "scope-admin-" + suffix, reviewer = "scope-reviewer-" + suffix;
            String prefix = "任务地区验收" + suffix;
            jdbc.sql("INSERT INTO platform.work_unit(code,name,sort_order) VALUES(:code,:code,9988)")
                    .param("code", unit).update();
            GovernedMasterDataFixtures.insertRegion(jdbc, FIRST, "任务范围隔离乡镇甲", "230221", "TOWNSHIP", 99990);
            GovernedMasterDataFixtures.insertRegion(jdbc, SECOND, "任务范围隔离乡镇乙", "230221", "TOWNSHIP", 99991);
            for (String region : List.of(FIRST, SECOND)) {
                jdbc.sql("INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code) VALUES(:unit,:region)")
                        .param("unit", unit).param("region", region).update();
                boundary(jdbc, region);
            }
            for (String subject : List.of(old, current, empty, root, reviewer)) {
                jdbc.sql("INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled) VALUES(:s,:s,:unit,true)")
                        .param("s", subject).param("unit", unit).update();
                String role = subject.equals(root) ? "SYSTEM_ADMIN" : subject.equals(reviewer) ? "BUSINESS_REVIEWER" : "BUSINESS_OPERATOR";
                jdbc.sql("INSERT INTO platform.security_user_role(subject_id,role_code) VALUES(:s,:role)")
                        .param("s", subject).param("role", role).update();
            }
            grant(jdbc, old, FIRST);
            UUID first = sample(jdbc, FIRST, old, prefix + "甲");
            UUID second = sample(jdbc, SECOND, old, prefix + "乙");
            var principals = new JdbcSecurityPrincipalRepository(jdbc);
            var overview = new JdbcOverviewSamplePointRepository(jdbc, Clock.systemUTC());
            var repository = new JdbcFormalSampleObservationRepository(jdbc, new ObjectMapper(), overview);
            var production = new JdbcProductionRecordRepository(ds, null);
            var oldAccess = access(old, principals);
            var oldService = observations(repository, oldAccess);
            assertThat(ids(oldService, prefix, null)).containsExactlyInAnyOrder(first, second);
            assertThat(ids(oldService, prefix, "MY_TASKS")).containsExactly(first);

            // Preserve old ownership in the registry, but revoke its current grant and assign the successor.
            jdbc.sql("UPDATE platform.security_user_region_scope SET valid_until=CURRENT_TIMESTAMP - interval '1 second' WHERE subject_id=:s")
                    .param("s", old).update();
            grant(jdbc, current, FIRST);
            var currentAccess = access(current, principals);
            assertThat(ids(oldService, prefix, "MY_TASKS")).isEmpty();
            assertThat(ids(observations(repository, currentAccess), prefix, "MY_TASKS")).containsExactly(first);
            assertThat(jdbc.sql("SELECT maintainer_subject_id FROM registry.sample_point WHERE sample_point_id=:id")
                    .param("id", first).query(String.class).single()).isEqualTo(old);
            currentAccess.require("BUSINESS_CREATE", FIRST);
            assertThat(repository.lockEligibleSample(FormalSampleObservationDomain.PRODUCTION, first,
                    "CORN", OBSERVED.toLocalDate(), currentAccess.requireTaskReadScope().regionCodes()).samplePointId()).isEqualTo(first);
            assertThatThrownBy(() -> oldAccess.require("BUSINESS_CREATE", FIRST))
                    .isInstanceOf(com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException.class);

            var query = new ProductionRecordQuery("CORN", "MONITORING", 0, 1, Map.of("surveyYear", "2026"));
            var taskPage = production.findPage(query.authorizedFor(currentAccess.requireTaskReadScope().regionCodes()));
            assertThat(taskPage.totalElements()).isEqualTo(1);
            assertThat(taskPage.items()).hasSize(1);
            assertThat(taskPage.items().getFirst().regionCode()).isEqualTo(FIRST);
            assertThat(jdbc.sql("SELECT sample_point_id FROM production.production_record WHERE record_id::text=:id")
                    .param("id", taskPage.items().getFirst().id()).query(UUID.class).single()).isEqualTo(first);
            assertThat(production.findPage(new ProductionRecordQuery("CORN", "MONITORING", 1, 1,
                    Map.of("surveyYear", "2026")).authorizedFor(currentAccess.requireTaskReadScope().regionCodes())).items()).isEmpty();
            var emptyAccess = access(empty, principals);
            assertThat(ids(observations(repository, emptyAccess), prefix, "MY_TASKS")).isEmpty();
            assertThat(production.findPage(query.authorizedFor(emptyAccess.requireTaskReadScope().regionCodes())).totalElements()).isZero();
            assertThat(ids(observations(repository, emptyAccess), prefix, null)).hasSize(2);

            var summaryRepo = new com.cofco.qiqihar.graintrade.regionalproduction.infrastructure.JdbcRegionalCropSummaryRepository(jdbc);
            var summaryService = new com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalCropSummaryService(
                    summaryRepo, new com.cofco.qiqihar.graintrade.regionalproduction.infrastructure.JdbcRegionalCropAnnualStatRepository(jdbc), emptyAccess);
            assertThat(summaryService.summarize(2026, "CORN", "230200").regionCode()).isEqualTo("230200");
            assertThat(summaryService.summarize(2026, "CORN", "230221").regionCode()).isEqualTo("230221");
            assertThat(summaryRepo.summarize(2026, "CORN", "230221", Set.of())).isEmpty();

            for (String administrator : List.of(root, reviewer)) {
                assertThat(jdbc.sql("SELECT count(*) FROM platform.security_user_region_scope WHERE subject_id=:s")
                        .param("s", administrator).query(Long.class).single()).isZero();
                var adminAccess = access(administrator, principals);
                var adminScope = adminAccess.requireTaskReadScope();
                assertThat(adminScope.isUnrestricted()).isTrue();
                assertThat(ids(observations(repository, adminAccess), prefix, "MY_TASKS")).containsExactlyInAnyOrder(first, second);
                assertThat(production.findPage(query.authorizedFor(adminScope.regionCodes())).totalElements()).isGreaterThanOrEqualTo(2);
                assertThat(repository.findHistory(FormalSampleObservationDomain.PRODUCTION, second,
                        "CORN", 2026, 0, 20, adminScope.regionCodes()).totalElements()).isEqualTo(1);
                assertThat(repository.lockEligibleSample(FormalSampleObservationDomain.PRODUCTION, second,
                        "CORN", OBSERVED.toLocalDate(), adminScope.regionCodes()).samplePointId()).isEqualTo(second);
            }
            return null;
        });
    }

    private static AccessControl access(String subject, JdbcSecurityPrincipalRepository principals) {
        return new AccessControl(() -> Optional.of(subject), principals, true);
    }
    private static FormalSampleObservationService observations(JdbcFormalSampleObservationRepository repo, AccessControl access) {
        return new FormalSampleObservationService(repo, access, null, null, null, null, new ObjectMapper(), Clock.systemUTC(), null);
    }
    private static List<UUID> ids(FormalSampleObservationService service, String keyword, String scope) {
        return service.eligibleSamples(FormalSampleObservationDomain.PRODUCTION, "CORN", null, null,
                keyword, 2026, OBSERVED, scope).stream().map(EligibleFormalSample::samplePointId).toList();
    }
    private static void grant(JdbcClient jdbc, String subject, String region) {
        jdbc.sql("INSERT INTO platform.security_user_region_scope(subject_id,region_code,valid_from,granted_by) VALUES(:s,:r,CURRENT_TIMESTAMP - interval '2 days',:s)")
                .param("s", subject).param("r", region).update();
    }
    private static void boundary(JdbcClient jdbc, String region) {
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(region_code,geometry,source_name,source_url,
                  source_revision,source_license,source_feature_id,source_effective_on,geometry_sha256)
                VALUES(:r,ST_Multi(ST_MakeEnvelope(123.0,47.0,123.5,47.6,4326)),
                  'scope isolation fixture','urn:test:scope','scope-test-v1','Test fixture',:r,
                  DATE '2026-01-01',repeat('9',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=excluded.geometry,
                  geometry_sha256=excluded.geometry_sha256,source_revision=excluded.source_revision
                """).param("r", region).update();
        GovernedMasterDataFixtures.publishBoundary(jdbc, region);
    }
    private static UUID sample(JdbcClient jdbc, String region, String owner, String name) {
        UUID point = UUID.randomUUID(), record = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO registry.sample_point(sample_point_id,kind_code,canonical_name,region_code,
                  approval_state,location_state,governed_point,effective_from,maintainer_subject_id,created_by,updated_by)
                VALUES(:id,'SURVEY_SITE',:name,:r,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.2,47.3),4326),DATE '2026-01-01',:owner,:owner,:owner)
                """).param("id", point).param("name", name).param("r", region).param("owner", owner).update();
        jdbc.sql("""
                INSERT INTO registry.formal_sample_point_profile(sample_point_id,object_type_code,address,created_by,updated_by)
                VALUES(:id,'FARMER',:name,:owner,:owner)
                """).param("id", point).param("name", name).param("owner", owner).update();
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                  survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,
                  survey_year,survey_period_precision,survey_period_governance_state,sample_point_id)
                VALUES(:record,'CORN','FARMER',:r,DATE '2026-08-20',TIMESTAMPTZ '2026-08-20 09:30:00+08',
                  100,500,'APPROVED',:owner,2026,'YEAR','CONFIRMED',:id)
                """).param("record", record).param("r", region).param("owner", owner).param("id", point).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES(:record,'PROD_SAMPLE_NAME',:name),(:record,'PROD_SAMPLE_CONTACT',:phone),
                  (:record,'PROD_SAMPLE_LATITUDE','47.3000000'),(:record,'PROD_SAMPLE_LONGITUDE','123.2000000')
                """).param("record", record).param("name", name)
                .param("phone", region.equals(FIRST) ? "13800000990" : "13800000991").update();
        return point;
    }
}
