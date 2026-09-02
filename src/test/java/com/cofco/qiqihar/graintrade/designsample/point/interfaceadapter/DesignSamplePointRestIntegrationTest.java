package com.cofco.qiqihar.graintrade.designsample.point.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrainTradeApplication.class,
        properties = "qiqihar.security.require-read-authentication=true")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class DesignSamplePointRestIntegrationTest {
    private static final String ENDPOINT = "/api/v1/design-sample-points";
    private static final String ACTOR = "production-tester";
    private static final String READER = "design-sample-reader";
    private static final String OTHER_REGION_READER = "design-sample-other-region-reader";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;
    private String contractVersion;
    private String contractDigest;
    private long auditBaseline;
    private long outboxBaseline;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        contractVersion = jdbc.sql("SELECT contract_version FROM platform.design_sample_contract WHERE active")
                .query(String.class).single();
        contractDigest = jdbc.sql("SELECT platform.current_design_sample_contract_digest()")
                .query(String.class).single();
        jdbc.sql("""
                DELETE FROM platform.design_sample_point;
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES('DESIGN_SAMPLE_TEST','设计样本点测试单位',9945)
                ON CONFLICT(code) DO UPDATE SET active=true;
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES('DESIGN_SAMPLE_TEST','230202') ON CONFLICT DO NOTHING;
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:reader,'设计样本点只读员','DESIGN_SAMPLE_TEST'),
                      (:otherReader,'设计样本点其他区域只读员','DESIGN_SAMPLE_TEST')
                ON CONFLICT(subject_id) DO UPDATE SET enabled=true,work_unit_code=EXCLUDED.work_unit_code;
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:reader,'REPORTER'),(:otherReader,'REPORTER')
                ON CONFLICT(subject_id,role_code,valid_from) DO UPDATE SET valid_until=NULL;
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES(:reader,'230202'),(:otherReader,'230208')
                ON CONFLICT(subject_id,region_code,valid_from) DO UPDATE SET valid_until=NULL
                """).param("reader", READER)
                .param("otherReader", OTHER_REGION_READER).update();
        auditBaseline = countEvents("platform.business_audit_event");
        outboxBaseline = countEvents("platform.business_event_outbox");
    }

    @Test
    void replaysAnIdenticalCreateButRejectsIdempotencyKeyReuseForAnotherPayload()
            throws Exception {
        MvcResult first = mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-idempotency")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("幂等设计样本点", "10", null)))
                .andExpect(status().isCreated()).andReturn();
        String id = body(first).path("data").path("id").asText();

        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-idempotency")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("幂等设计样本点", "10", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));

        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-idempotency")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("另一个设计样本点", "10", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("DESIGN_SAMPLE_POINT_IDEMPOTENCY_CONFLICT"));

        assertThat(count("platform.design_sample_point")).isOne();
        assertThat(countEvents("platform.business_audit_event")).isEqualTo(auditBaseline + 1);
        assertThat(countEvents("platform.business_event_outbox")).isEqualTo(outboxBaseline + 1);
    }

    @Test
    void persistsValidatedFieldValuesInOneCanonicalJsonRepresentation() throws Exception {
        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-canonical-values")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractVersion":"%s",
                                 "contractDigest":"%s",
                                 "context":{"domainCode":"REFERENCE","productCode":"GENERAL",
                                            "objectTypeCode":"REFERENCE_POINT"},
                                 "values":{"DSP_NAME":"  规范化设计样本点  ",
                                           "DSP_REGION_CODE":"230202",
                                           "DSP_ADDRESS":"  龙沙区详细地址  ",
                                           "DSP_LONGITUDE":"123.9500000",
                                           "DSP_LATITUDE":"47.3500000"}}
                                """.formatted(contractVersion, contractDigest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.values.DSP_NAME").value("规范化设计样本点"))
                .andExpect(jsonPath("$.data.values.DSP_ADDRESS").value("龙沙区详细地址"))
                .andExpect(jsonPath("$.data.values.DSP_LONGITUDE").value(123.95))
                .andExpect(jsonPath("$.data.values.DSP_LATITUDE").value(47.35));
    }

    @Test
    void acceptsOnlyLightweightLocationFieldsForNewDesignPoints() throws Exception {
        String version = jdbc.sql("SELECT contract_version FROM platform.design_sample_contract WHERE active")
                .query(String.class).single();

        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-lightweight-location")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractVersion":"%s","contractDigest":"%s",
                                 "context":{"domainCode":"REFERENCE","productCode":"GENERAL",
                                            "objectTypeCode":"REFERENCE_POINT"},
                                 "values":{"DSP_NAME":"轻量设计参考点",
                                           "DSP_REGION_CODE":"230202",
                                           "DSP_ADDRESS":"龙沙区验收详细地址",
                                           "DSP_LONGITUDE":123.95,
                                           "DSP_LATITUDE":47.35}}
                                """.formatted(version, contractDigest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.values.DSP_ADDRESS")
                        .value("龙沙区验收详细地址"))
                .andExpect(jsonPath("$.data.values.OBSERVED_ON").doesNotExist());

        assertThat(jdbc.sql("""
                SELECT values_json->>'DSP_ADDRESS'
                FROM platform.design_sample_point
                WHERE sample_name='轻量设计参考点'
                """).query(String.class).single()).isEqualTo("龙沙区验收详细地址");
    }

    @Test
    void rejectsBusinessObservationFieldsFromDesignPointWritesWithoutSideEffects()
            throws Exception {
        long before = count("platform.design_sample_point");
        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-reject-observation")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("不应写入的设计点", "10", null)
                                .replace("\"DSP_LATITUDE\":47.35",
                                        "\"DSP_LATITUDE\":47.35,\"OBSERVED_ON\":\"2026-06-01\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_NOT_APPLICABLE"));
        assertThat(count("platform.design_sample_point")).isEqualTo(before);
    }

    @Test
    void requeriesOneAuthoritativePointWithinTheCurrentReadScope() throws Exception {
        String id = create("design-sample-authoritative-get", "权威重查设计样本点", "10");

        mvc.perform(get(ENDPOINT + "/{id}", id).principal(() -> READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.regionCode").value("230202"))
                .andExpect(jsonPath("$.data.values.DSP_NAME").value("权威重查设计样本点"));

        mvc.perform(get(ENDPOINT + "/{id}", id).principal(() -> OTHER_REGION_READER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
    }

    @Test
    void backfillsAddressWithoutDeletingHistoricalStoredValues()
            throws Exception {
        String id = create("design-sample-historical-values", "历史设计样本点", "旧址");
        jdbc.sql("""
                UPDATE platform.design_sample_point
                SET values_json=(values_json - 'DSP_ADDRESS')
                    || '{"OBSERVED_ON":"2025-01-01","LEGACY_INTERNAL_SOURCE":"preserve"}'::jsonb
                WHERE design_sample_point_id=CAST(:id AS uuid)
                """).param("id", id).update();
        mvc.perform(put(ENDPOINT + "/{id}", id)
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("历史设计样本点", "补录详细地址", 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.values.DSP_ADDRESS").value("补录详细地址"))
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbc.sql("""
                SELECT values_json->>'OBSERVED_ON',
                       values_json->>'LEGACY_INTERNAL_SOURCE',
                       values_json->>'DSP_ADDRESS'
                FROM platform.design_sample_point
                WHERE design_sample_point_id=CAST(:id AS uuid)
                """).param("id", id).query((rs, rowNum) -> List.of(
                        rs.getString(1), rs.getString(2), rs.getString(3))).single())
                .containsExactly("2025-01-01", "preserve", "补录详细地址");
    }

    @Test
    void serializesConcurrentIdenticalCreatesAsOneCreateAndOneReplay() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> {
                start.await();
                return concurrentCreate();
            });
            Future<MvcResult> second = executor.submit(() -> {
                start.await();
                return concurrentCreate();
            });
            start.countDown();
            MvcResult firstResult = first.get();
            MvcResult secondResult = second.get();

            assertThat(Set.of(firstResult.getResponse().getStatus(),
                    secondResult.getResponse().getStatus())).containsExactlyInAnyOrder(200, 201);
            assertThat(body(firstResult).path("data").path("id").asText())
                    .isEqualTo(body(secondResult).path("data").path("id").asText());
        }
        assertThat(count("platform.design_sample_point")).isOne();
        assertThat(countEvents("platform.business_audit_event")).isEqualTo(auditBaseline + 1);
        assertThat(countEvents("platform.business_event_outbox")).isEqualTo(outboxBaseline + 1);
    }

    @Test
    void rejectsOutsideCoordinatesInapplicableFieldsAndUnauthorizedWritesWithoutPersistence()
            throws Exception {
        mvc.perform(post(ENDPOINT)
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("缺少幂等键设计样本点", "10", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IDEMPOTENCY_KEY"));

        mvc.perform(get(ENDPOINT).principal(() -> ACTOR)
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DESIGN_SAMPLE_POINT"));

        mvc.perform(get(ENDPOINT).principal(() -> ACTOR)
                        .queryParam("pageNumber", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DESIGN_SAMPLE_POINT"));

        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-invalid-region")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("无效地区设计样本点", "10", null)
                                .replace("230202", "999999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DESIGN_SAMPLE_POINT"));

        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-boundary-unavailable")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("缺少边界设计样本点", "10", null)
                                .replace("230202", "231102")
                                .replace("123.95", "126.5")
                                .replace("47.35", "49.5")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ADMIN_BOUNDARY_UNAVAILABLE"));

        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-invalid-longitude")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("非法经度设计样本点", "10", null)
                                .replace("123.95", "181")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_VALUE_INVALID"));

        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-invalid-latitude")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("非法纬度设计样本点", "10", null)
                                .replace("47.35", "91")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_VALUE_INVALID"));

        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-outside")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("越界设计样本点", "10", null)
                                .replace("123.95", "130").replace("47.35", "50")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("COORDINATE_OUTSIDE_REGION"));

        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-inapplicable")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("字段越权设计样本点", "10", null)
                                .replace("\"DSP_LATITUDE\":47.35",
                                        "\"DSP_LATITUDE\":47.35,\"MKT_PURCHASE_BASE_PRICE\":1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_NOT_APPLICABLE"));

        mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-reader-write")
                        .principal(() -> READER).contentType(MediaType.APPLICATION_JSON)
                        .content(request("只读越权设计样本点", "10", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));

        assertThat(count("platform.design_sample_point")).isZero();
        assertThat(countEvents("platform.business_audit_event")).isEqualTo(auditBaseline);
        assertThat(countEvents("platform.business_event_outbox")).isEqualTo(outboxBaseline);
    }

    @Test
    void rejectsStaleUpdatesAndDeletesWithoutChangingThePersistedVersion() throws Exception {
        String id = create("design-sample-version", "并发设计样本点", "10");
        mvc.perform(put(ENDPOINT + "/{id}", id)
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("并发设计样本点（版本一）", "20", 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(put(ENDPOINT + "/{id}", id)
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("并发设计样本点（陈旧写）", "30", 0L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DESIGN_SAMPLE_POINT_VERSION_CONFLICT"));
        mvc.perform(delete(ENDPOINT + "/{id}", id)
                        .principal(() -> ACTOR).queryParam("expectedVersion", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DESIGN_SAMPLE_POINT_VERSION_CONFLICT"));

        mvc.perform(get(ENDPOINT).principal(() -> ACTOR)
                        .queryParam("keyword", "版本一"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.DSP_ADDRESS").value("20"))
                .andExpect(jsonPath("$.data.items[0].version").value(1));
        assertThat(countEvents("platform.business_audit_event")).isEqualTo(auditBaseline + 2);
        assertThat(countEvents("platform.business_event_outbox")).isEqualTo(outboxBaseline + 2);
    }

    @Test
    void rollsBackThePointAndAuditWhenOutboxPersistenceFails() throws Exception {
        jdbc.sql("""
                CREATE FUNCTION platform.reject_design_sample_outbox_test()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.aggregate_type='DESIGN_SAMPLE_POINT' THEN
                    RAISE EXCEPTION 'forced design sample outbox failure';
                  END IF;
                  RETURN NEW;
                END
                $$;
                CREATE TRIGGER reject_design_sample_outbox_test
                BEFORE INSERT ON platform.business_event_outbox
                FOR EACH ROW EXECUTE FUNCTION platform.reject_design_sample_outbox_test()
                """).update();
        try {
            mvc.perform(post(ENDPOINT)
                            .header("Idempotency-Key", "design-sample-outbox-failure")
                            .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                            .content(request("事务回滚设计样本点", "10", null)))
                    .andExpect(status().isInternalServerError());
        } finally {
            jdbc.sql("""
                    DROP TRIGGER reject_design_sample_outbox_test
                    ON platform.business_event_outbox;
                    DROP FUNCTION platform.reject_design_sample_outbox_test()
                    """).update();
        }
        assertThat(count("platform.design_sample_point")).isZero();
        assertThat(countEvents("platform.business_audit_event")).isEqualTo(auditBaseline);
        assertThat(countEvents("platform.business_event_outbox")).isEqualTo(outboxBaseline);
    }

    @Test
    void failsClosedWhenARealReferencePreventsPhysicalDeletion() throws Exception {
        String id = create("design-sample-reference", "被引用设计样本点", "10");
        jdbc.sql("""
                CREATE TABLE platform.design_sample_point_reference_test(
                  point_id uuid PRIMARY KEY REFERENCES platform.design_sample_point(
                    design_sample_point_id) ON DELETE RESTRICT);
                INSERT INTO platform.design_sample_point_reference_test(point_id)
                VALUES(:id::uuid)
                """).param("id", id).update();
        try {
            mvc.perform(delete(ENDPOINT + "/{id}", id)
                            .principal(() -> ACTOR).queryParam("expectedVersion", "0"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("DESIGN_SAMPLE_POINT_REFERENCED"));
            assertThat(count("platform.design_sample_point")).isOne();
            assertThat(countEvents("platform.business_audit_event")).isEqualTo(auditBaseline + 1);
            assertThat(countEvents("platform.business_event_outbox")).isEqualTo(outboxBaseline + 1);
        } finally {
            jdbc.sql("DROP TABLE platform.design_sample_point_reference_test").update();
        }
    }

    @Test
    void createsListsUpdatesRequeriesAndPhysicallyDeletesAYearIndependentDesignSamplePoint()
            throws Exception {
        MvcResult created = mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-core-lifecycle")
                        .principal(() -> ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                .content(request("验收设计样本点", "100", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.context.objectTypeCode").value("REFERENCE_POINT"))
                .andExpect(jsonPath("$.data.values.DSP_NAME").value("验收设计样本点"))
                .andExpect(jsonPath("$.data.regionPath").isNotEmpty())
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();
        String id = body(created).path("data").path("id").asText();

        assertThat(jdbc.sql("""
                SELECT source_dataset_id
                FROM overview.administrative_boundary
                WHERE region_code='230202'
                """).query(String.class).single())
                .isEqualTo("geoboundaries-chn-adm3-9469f09-qiqihar-2017");

        mvc.perform(get(ENDPOINT)
                        .principal(() -> ACTOR)
                        .queryParam("domainCode", "REFERENCE")
                        .queryParam("productCode", "GENERAL")
                        .queryParam("objectTypeCode", "REFERENCE_POINT")
                        .queryParam("regionCode", "230202")
                        .queryParam("keyword", "验收")
                        .queryParam("page", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id));

        mvc.perform(put(ENDPOINT + "/{id}", id)
                        .principal(() -> ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("验收设计样本点（修改）", "120", 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.values.DSP_NAME").value("验收设计样本点（修改）"))
                .andExpect(jsonPath("$.data.values.DSP_ADDRESS").value("120"))
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(get(ENDPOINT)
                        .principal(() -> ACTOR)
                        .queryParam("keyword", "修改")
                        .queryParam("page", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].version").value(1));

        mvc.perform(delete(ENDPOINT + "/{id}", id)
                        .principal(() -> ACTOR)
                        .queryParam("expectedVersion", "1"))
                .andExpect(status().isNoContent());

        mvc.perform(get(ENDPOINT)
                        .principal(() -> ACTOR)
                        .queryParam("page", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        assertThat(jdbc.sql("SELECT count(*) FROM platform.design_sample_point")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='DESIGN_SAMPLE_POINT' AND aggregate_id=:id
                """).param("id", id).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='DESIGN_SAMPLE_POINT' AND aggregate_id=:id
                  AND region_codes @> ARRAY['230202']::varchar[]
                  AND product_code='GENERAL'
                """).param("id", id).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    @Transactional
    void leaves1064FormalSamplesAnd232TownshipMasterRowsUnchanged() throws Exception {
        for (int index = 1; index <= 232; index++) {
            String code = "2302027%05d".formatted(index);
            GovernedMasterDataFixtures.insertRegion(
                    jdbc, code, "设计样本隔离乡镇%03d".formatted(index),
                    "230202", "TOWNSHIP", 9700 + index);
        }
        String village = "230202799999";
        GovernedMasterDataFixtures.insertRegion(
                jdbc, village, "设计样本隔离村", "230202700001", "VILLAGE", 9999);
        jdbc.sql("""
                INSERT INTO registry.sample_network_year(
                  network_year,status_code,version,created_by,created_at,
                  submitted_by,submitted_at,reviewed_by,reviewed_at,published_by,published_at)
                VALUES(2199,'PUBLISHED',0,:actor,CURRENT_TIMESTAMP,
                  :actor,CURRENT_TIMESTAMP,'market-tester',CURRENT_TIMESTAMP,
                  :actor,CURRENT_TIMESTAMP);
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,effective_from,created_by,updated_by)
                SELECT CAST(md5('design-sample-formal-sentinel-' || value) AS uuid),
                  'SURVEY_SITE','设计样本隔离正式样本-' || value,'230202','APPROVED',
                  'MISSING',DATE '2199-01-01',:actor,:actor
                FROM generate_series(1,1064) value;
                INSERT INTO registry.sample_network_membership(
                  network_year,sample_point_id,village_region_code,status_code,source_code,
                  version,decided_by,decided_at,created_by,created_at)
                SELECT 2199,CAST(md5('design-sample-formal-sentinel-' || value) AS uuid),
                  :village,'ACTIVE','MANUAL',0,:actor,CURRENT_TIMESTAMP,:actor,CURRENT_TIMESTAMP
                FROM generate_series(1,1064) value
                """).param("actor", ACTOR).param("village", village).update();

        assertThat(formalSentinelCount()).isEqualTo(1064);
        assertThat(townshipSentinelCount()).isEqualTo(232);

        String id = create("design-sample-isolation-sentinel", "隔离哨兵设计样本点", "10");
        mvc.perform(put(ENDPOINT + "/{id}", id)
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("隔离哨兵设计样本点（修改）", "20", 0L)))
                .andExpect(status().isOk());
        mvc.perform(delete(ENDPOINT + "/{id}", id)
                        .principal(() -> ACTOR).queryParam("expectedVersion", "1"))
                .andExpect(status().isNoContent());

        assertThat(formalSentinelCount()).isEqualTo(1064);
        assertThat(townshipSentinelCount()).isEqualTo(232);
    }

    private String request(String name, String area, Long expectedVersion) {
        String version = expectedVersion == null ? "" : ",\"expectedVersion\":" + expectedVersion;
        return """
                {"contractVersion":"%s",
                 "contractDigest":"%s",
                 "context":{"domainCode":"REFERENCE","productCode":"GENERAL",
                            "objectTypeCode":"REFERENCE_POINT"},
                 "values":{"DSP_NAME":"%s","DSP_REGION_CODE":"230202",
                           "DSP_ADDRESS":"%s",
                           "DSP_LONGITUDE":123.95,"DSP_LATITUDE":47.35}%s}
                """.formatted(contractVersion, contractDigest, name, area, version);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String create(String key, String name, String area) throws Exception {
        MvcResult result = mvc.perform(post(ENDPOINT).header("Idempotency-Key", key)
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request(name, area, null)))
                .andExpect(status().isCreated()).andReturn();
        return body(result).path("data").path("id").asText();
    }

    private MvcResult concurrentCreate() throws Exception {
        return mvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", "design-sample-concurrent-idempotency")
                        .principal(() -> ACTOR).contentType(MediaType.APPLICATION_JSON)
                        .content(request("并发幂等设计样本点", "10", null)))
                .andReturn();
    }

    private long count(String relation) {
        return jdbc.sql("SELECT count(*) FROM " + relation).query(Long.class).single();
    }

    private long countEvents(String relation) {
        return jdbc.sql("SELECT count(*) FROM " + relation
                        + " WHERE aggregate_type='DESIGN_SAMPLE_POINT'")
                .query(Long.class).single();
    }

    private long formalSentinelCount() {
        return jdbc.sql("""
                SELECT count(*)
                FROM registry.sample_network_membership membership
                JOIN registry.sample_point point USING(sample_point_id)
                WHERE membership.network_year=2199 AND membership.status_code='ACTIVE'
                  AND point.approval_state='APPROVED'
                  AND point.canonical_name LIKE '设计样本隔离正式样本-%'
                """).query(Long.class).single();
    }

    private long townshipSentinelCount() {
        return jdbc.sql("""
                SELECT count(*) FROM platform.region
                WHERE administrative_level='TOWNSHIP' AND code LIKE '2302027%'
                """).query(Long.class).single();
    }
}
