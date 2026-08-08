package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import static org.hamcrest.Matchers.hasItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ProductionRecordRestIntegrationTest {
    private static final Map<String, String> QUALITY_FACT = Map.of(
            "CORN", "MOISTURE", "SOYBEAN", "PROTEIN", "RICE", "MILLING_YIELD");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void removeTestRecords() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE platform.business_audit_event").update();
        jdbc.sql(
                "DELETE FROM production.production_record WHERE last_modified_by = 'production-tester'").update();
    }

    @Test
    void rejectsUnknownQueryParametersAndRequiresAnAuthenticatedPrincipalForWrites() throws Exception {
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20")
                        .queryParam("unrecognized", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD_QUERY"));

        mockMvc.perform(post("/api/v1/production-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} / {2} round-trips all four confirmed fact categories")
    @MethodSource("farmerAndVillageContexts")
    void farmerAndVillageRoundTripAllConfirmedFactsAndTransitions(
            String product, String qualityCode, String objectType) throws Exception {
        expectDefinitionFacts(product, objectType, qualityCode, true);
        String id = create(fullDraftBody(product, objectType, qualityCode, null));

        expectFullFactDetail(id, product, objectType, qualityCode);
        expectFullFactList(product, objectType, qualityCode);

        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.allowedActions[1]").value("APPROVE"));
        mockMvc.perform(post("/api/v1/production-records/{id}/return", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"补充依据\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        mockMvc.perform(put("/api/v1/production-records/{id}", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(fullDraftBody(product, objectType, qualityCode, 2L)
                                .replace("测试填报员", "修改填报员")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.costs.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_REPORTER_NAME").value("修改填报员"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SAMPLE_LATITUDE").value("47.3543"));
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type = 'PRODUCTION_RECORD' AND aggregate_id = :id
                """).param("id", id).query(Long.class).single()).isEqualTo(4L);
    }

    @ParameterizedTest(name = "{0} / {2} approval preserves all four formal fact categories")
    @MethodSource("farmerAndVillageContexts")
    void farmerAndVillageApprovePreservesAllFormalFacts(
            String product, String qualityCode, String objectType) throws Exception {
        expectDefinitionFacts(product, objectType, qualityCode, true);
        String id = create(fullDraftBody(product, objectType, qualityCode, null));

        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.quality." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.costs.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.insurance.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.subsidies.SUBSIDY_AMOUNT").value("6.0000"));

        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("VIEW"))
                .andExpect(jsonPath("$.data.quality." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.costs.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.insurance.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.subsidies.SUBSIDY_AMOUNT").value("6.0000"));

        expectApprovedFullFactDetail(id, product, objectType, qualityCode);
        expectApprovedFullFactList(product, objectType, qualityCode);
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type = 'PRODUCTION_RECORD' AND aggregate_id = :id
                """).param("id", id).query(Long.class).single()).isEqualTo(3L);
    }

    @ParameterizedTest(name = "{0} / AGRICULTURAL_TECH_STATION round-trips quality only")
    @MethodSource("productQualityContexts")
    void agriculturalTechStationRoundTripsOnlyItsConfirmedQualityFact(
            String product, String qualityCode) throws Exception {
        String objectType = "AGRICULTURAL_TECH_STATION";
        expectDefinitionFacts(product, objectType, qualityCode, false);
        String id = create(qualityOnlyDraftBody(product, objectType, qualityCode));

        mockMvc.perform(get("/api/v1/production-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value(product))
                .andExpect(jsonPath("$.data.objectTypeCode").value(objectType))
                .andExpect(jsonPath("$.data.quality." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.costs").isEmpty())
                .andExpect(jsonPath("$.data.insurance").isEmpty())
                .andExpect(jsonPath("$.data.subsidies").isEmpty());
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", product).queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100")
                        .queryParam("filter.objectTypeCode", objectType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].values." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.items[0].values.LAND_RENT").doesNotExist());
    }

    @ParameterizedTest(name = "{0} tech station rejects {2}")
    @MethodSource("agriculturalTechStationRejectedFacts")
    void agriculturalTechStationRejectsEachUnsupportedFactCategoryWithoutPartialWrites(
            String product, String qualityCode, String category, String factCode) throws Exception {
        long before = actorBusinessRowCount();

        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unsupportedTechStationDraftBody(product, qualityCode, category, factCode)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INAPPLICABLE_PRODUCTION_FACT"));

        org.assertj.core.api.Assertions.assertThat(actorBusinessRowCount()).isEqualTo(before);
    }

    @Test
    void exposesFourOrderedChineseGroupsAndObjectApplicabilityForAllProducts() throws Exception {
        for (String product : QUALITY_FACT.keySet()) {
            for (String objectType : new String[] {
                    "FARMER", "VILLAGE_COMMITTEE", "AGRICULTURAL_TECH_STATION"}) {
                var result = mockMvc.perform(get("/api/v1/production-record-definitions")
                                .queryParam("productCode", product)
                                .queryParam("objectTypeCode", objectType))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.productCode").value(product))
                        .andExpect(jsonPath("$.data.objectTypeCode").value(objectType))
                        .andExpect(jsonPath("$.data.groups.length()").value(4))
                        .andExpect(jsonPath("$.data.groups[0].category").value("QUALITY"))
                        .andExpect(jsonPath("$.data.groups[0].label").value("质量指标"))
                        .andExpect(jsonPath("$.data.groups[0].sortOrder").value(10))
                        .andExpect(jsonPath("$.data.groups[0].fields.length()").isNotEmpty())
                        .andExpect(jsonPath("$.data.groups[1].category").value("COST"))
                        .andExpect(jsonPath("$.data.groups[1].label").value("生产成本"))
                        .andExpect(jsonPath("$.data.groups[2].category").value("INSURANCE"))
                        .andExpect(jsonPath("$.data.groups[2].label").value("农业保险"))
                        .andExpect(jsonPath("$.data.groups[3].category").value("SUBSIDY"))
                        .andExpect(jsonPath("$.data.groups[3].label").value("农业补贴"));
                if (objectType.equals("AGRICULTURAL_TECH_STATION")) {
                    result.andExpect(jsonPath("$.data.groups[1].fields").isEmpty())
                            .andExpect(jsonPath("$.data.groups[2].fields").isEmpty())
                            .andExpect(jsonPath("$.data.groups[3].fields").isEmpty());
                } else {
                    result.andExpect(jsonPath("$.data.groups[1].fields").isNotEmpty())
                            .andExpect(jsonPath("$.data.groups[2].fields").isNotEmpty())
                            .andExpect(jsonPath("$.data.groups[3].fields").isNotEmpty());
                }
            }
        }
    }

    @Test
    void obtainsObjectTypeAndCultivarApplicabilityFromMasterData() throws Exception {
        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody().replace("\"FARMER\"", "\"TRADER\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INAPPLICABLE_PRODUCTION_OBJECT_TYPE"));

        mockMvc.perform(post("/api/v1/production-records").principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody().replace("\"surveyDate\"", "\"cultivarCode\":\"HEINONG_84\",\"surveyDate\"")
                                .replace("\"SOYBEAN\"", "\"CORN\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INAPPLICABLE_PRODUCTION_CULTIVAR"));
    }

    @Test
    void rejectsStrictInvalidFiltersFutureDatesAndIllegalTransitions() throws Exception {
        for (String query : new String[] {
                "filter.status=NOT_A_STATUS", "filter.objectTypeCode=TRADER", "filter.surveyDate=2026-99-99"}) {
            mockMvc.perform(get("/api/v1/production-records?productCode=SOYBEAN&pageKind=MONITORING&pageNumber=0&pageSize=20&" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD_QUERY"));
        }
        mockMvc.perform(post("/api/v1/production-records").principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody().replace("2026-08-01", "2099-08-01")))
                .andExpect(status().isBadRequest());

        String id = create(validDraftBody());
        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_TRANSITION"));
    }

    @Test
    void listsDatabaseLabelsAndApplicationComputedActions() throws Exception {
        create(fullDraftBody("SOYBEAN", "FARMER", "PROTEIN", null));
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].values.PROD_REGION").value("龙沙区"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_OBJECT_TYPE").value("农户"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_STATUS").value("草稿"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_REPORTER_NAME").value("测试填报员"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_SAMPLE_LONGITUDE").value("123.9182"))
                .andExpect(jsonPath("$.data.items[0].values.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.items[0].allowedActions.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].allowedActions[0]").value("VIEW"))
                .andExpect(jsonPath("$.data.items[0].allowedActions[1]").value("SUBMIT"));
    }

    @Test
    void acceptsCoordinateBoundariesAndRejectsValuesOutsideThemWithoutWriting() throws Exception {
        String valid = validDraftBody();
        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(valid.replace("\"47.3543\"", "\"-90\"")
                                .replace("\"123.9182\"", "\"180\"")))
                .andExpect(status().isCreated());

        long before = actorBusinessRowCount();
        for (String body : List.of(
                valid.replace("\"47.3543\"", "\"90.0000000000000001\""),
                valid.replace("\"123.9182\"", "\"-180.0000001\""))) {
            mockMvc.perform(post("/api/v1/production-records")
                            .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD"));
        }
        assertThat(actorBusinessRowCount()).isEqualTo(before);
    }

    private String create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.reportedAt").exists())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private void expectDefinitionFacts(String product, String objectType, String qualityCode,
            boolean supportsAllCategories) throws Exception {
        var result = mockMvc.perform(get("/api/v1/production-record-definitions")
                        .queryParam("productCode", product).queryParam("objectTypeCode", objectType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].fields[*].code").value(hasItem(qualityCode)));
        if (supportsAllCategories) {
            result.andExpect(jsonPath("$.data.groups[1].fields[*].code").value(hasItem("LAND_RENT")))
                    .andExpect(jsonPath("$.data.groups[2].fields[*].code").value(hasItem("INSURANCE_AMOUNT")))
                    .andExpect(jsonPath("$.data.groups[3].fields[*].code").value(hasItem("SUBSIDY_AMOUNT")));
        } else {
            result.andExpect(jsonPath("$.data.groups[1].fields").isEmpty())
                    .andExpect(jsonPath("$.data.groups[2].fields").isEmpty())
                    .andExpect(jsonPath("$.data.groups[3].fields").isEmpty());
        }
    }

    private void expectFullFactDetail(String id, String product, String objectType, String qualityCode)
            throws Exception {
        mockMvc.perform(get("/api/v1/production-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value(product))
                .andExpect(jsonPath("$.data.objectTypeCode").value(objectType))
                .andExpect(jsonPath("$.data.cultivatedAreaMu").value("1.2345"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_REPORTER_PHONE").value("13800000000"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SAMPLE_CONTACT").value("13900000000"))
                .andExpect(jsonPath("$.data.quality." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.costs.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.insurance.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.subsidies.SUBSIDY_AMOUNT").value("6.0000"));
    }

    private void expectFullFactList(String product, String objectType, String qualityCode) throws Exception {
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", product).queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100")
                        .queryParam("filter.objectTypeCode", objectType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].values." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.items[0].values.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.items[0].values.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.items[0].values.SUBSIDY_AMOUNT").value("6.0000"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_SAMPLE_CONTACT").value("13900000000"))
                .andExpect(jsonPath("$.data.items[0].values.PROD_SAMPLE_LATITUDE").value("47.3543"));
    }

    private void expectApprovedFullFactDetail(
            String id, String product, String objectType, String qualityCode) throws Exception {
        mockMvc.perform(get("/api/v1/production-records/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value(product))
                .andExpect(jsonPath("$.data.objectTypeCode").value(objectType))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.quality." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.costs.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.insurance.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.subsidies.SUBSIDY_AMOUNT").value("6.0000"));
    }

    private void expectApprovedFullFactList(
            String product, String objectType, String qualityCode) throws Exception {
        mockMvc.perform(get("/api/v1/production-records")
                        .queryParam("productCode", product).queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "100")
                        .queryParam("filter.objectTypeCode", objectType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].values.PROD_STATUS").value("已审核"))
                .andExpect(jsonPath("$.data.items[0].allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].allowedActions[0]").value("VIEW"))
                .andExpect(jsonPath("$.data.items[0].values." + qualityCode).value("3.0000"))
                .andExpect(jsonPath("$.data.items[0].values.LAND_RENT").value("4.0000"))
                .andExpect(jsonPath("$.data.items[0].values.INSURANCE_AMOUNT").value("5.0000"))
                .andExpect(jsonPath("$.data.items[0].values.SUBSIDY_AMOUNT").value("6.0000"));
    }

    private long actorBusinessRowCount() {
        return JdbcClient.create(dataSource).sql("""
                SELECT
                    (SELECT count(*) FROM production.production_record
                     WHERE last_modified_by = 'production-tester')
                  + (SELECT count(*) FROM production.production_record_quality fact
                     JOIN production.production_record record ON record.record_id = fact.record_id
                     WHERE record.last_modified_by = 'production-tester')
                  + (SELECT count(*) FROM production.production_record_cost fact
                     JOIN production.production_record record ON record.record_id = fact.record_id
                     WHERE record.last_modified_by = 'production-tester')
                  + (SELECT count(*) FROM production.production_record_insurance fact
                     JOIN production.production_record record ON record.record_id = fact.record_id
                     WHERE record.last_modified_by = 'production-tester')
                  + (SELECT count(*) FROM production.production_record_subsidy fact
                     JOIN production.production_record record ON record.record_id = fact.record_id
                     WHERE record.last_modified_by = 'production-tester')
                """).query(Long.class).single();
    }

    private static String validDraftBody() {
        return """
                {"productCode":"SOYBEAN","objectTypeCode":"FARMER","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"100","yieldPerMuKilograms":"180",
                 "quality":{},"costs":{},"insurance":{},"subsidies":{},%s}
                """.formatted(submissionMetadataProperty());
    }

    private static String fullDraftBody(String product, String objectType, String qualityCode, Long version) {
        String versionProperty = version == null ? "" : ",\"version\":" + version;
        return """
                {"productCode":"%s","objectTypeCode":"%s","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"1.2345","yieldPerMuKilograms":"2.3456",
                 "quality":{"%s":"3"},"costs":{"LAND_RENT":"4"},
                 "insurance":{"INSURANCE_AMOUNT":"5"},"subsidies":{"SUBSIDY_AMOUNT":"6"},%s%s}
                """.formatted(product, objectType, qualityCode, submissionMetadataProperty(), versionProperty);
    }

    private static String qualityOnlyDraftBody(String product, String objectType, String qualityCode) {
        return """
                {"productCode":"%s","objectTypeCode":"%s","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"1","yieldPerMuKilograms":"2",
                 "quality":{"%s":"3"},"costs":{},"insurance":{},"subsidies":{},%s}
                """.formatted(product, objectType, qualityCode, submissionMetadataProperty());
    }

    private static String unsupportedTechStationDraftBody(
            String product, String qualityCode, String category, String factCode) {
        String costs = category.equals("COST") ? "{\"%s\":\"4\"}".formatted(factCode) : "{}";
        String insurance = category.equals("INSURANCE") ? "{\"%s\":\"5\"}".formatted(factCode) : "{}";
        String subsidies = category.equals("SUBSIDY") ? "{\"%s\":\"6\"}".formatted(factCode) : "{}";
        return """
                {"productCode":"%s","objectTypeCode":"AGRICULTURAL_TECH_STATION","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"1","yieldPerMuKilograms":"2",
                 "quality":{"%s":"3"},"costs":%s,"insurance":%s,"subsidies":%s,%s}
                """.formatted(product, qualityCode, costs, insurance, subsidies, submissionMetadataProperty());
    }

    private static String submissionMetadataProperty() {
        return """
                "submissionMetadata":{"PROD_REPORTER_NAME":"测试填报员","PROD_REPORTER_PHONE":"13800000000",
                 "PROD_SAMPLE_CONTACT":"13900000000","PROD_SAMPLE_LATITUDE":"47.3543",
                 "PROD_SAMPLE_LONGITUDE":"123.9182"}
                """.strip();
    }

    private static List<Arguments> farmerAndVillageContexts() {
        return productQualityContexts().stream().flatMap(context -> List.of(
                Arguments.of(context.get()[0], context.get()[1], "FARMER"),
                Arguments.of(context.get()[0], context.get()[1], "VILLAGE_COMMITTEE")).stream()).toList();
    }

    private static List<Arguments> productQualityContexts() {
        return List.of(
                Arguments.of("CORN", "MOISTURE"),
                Arguments.of("SOYBEAN", "PROTEIN"),
                Arguments.of("RICE", "MILLING_YIELD"));
    }

    private static List<Arguments> agriculturalTechStationRejectedFacts() {
        return productQualityContexts().stream().flatMap(context -> List.of(
                Arguments.of(context.get()[0], context.get()[1], "COST", "LAND_RENT"),
                Arguments.of(context.get()[0], context.get()[1], "INSURANCE", "INSURANCE_AMOUNT"),
                Arguments.of(context.get()[0], context.get()[1], "SUBSIDY", "SUBSIDY_AMOUNT")).stream()).toList();
    }
}
