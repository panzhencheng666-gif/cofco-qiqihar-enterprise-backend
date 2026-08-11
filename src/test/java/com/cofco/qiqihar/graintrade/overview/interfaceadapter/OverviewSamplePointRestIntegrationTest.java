package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class OverviewSamplePointRestIntegrationTest {
    private static final String TOWNSHIP = "230202997";
    private static final String VILLAGE = "230202997001";
    private static final String SURVEY_POINT = "94000000-0000-0000-0000-000000000001";
    private static final String LOGISTICS_POINT = "94000000-0000-0000-0000-000000000002";
    private static final String MISSING_POINT = "94000000-0000-0000-0000-000000000003";
    private static final String DRAFT_POINT = "94000000-0000-0000-0000-000000000004";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        clean();
        insertRegionAndBoundaryFixtures();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        insertSamplePointFixtures();
        insertApprovedSourceFixtures();
    }

    @AfterEach
    void cleanAfterEach() {
        clean();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
    }

    @Test
    void keepsAdministrativeAggregatesIndependentFromListFilters() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", TOWNSHIP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].regionCode").value(VILLAGE))
                .andExpect(jsonPath("$.data[0].regionName").value("契约测试村"))
                .andExpect(jsonPath("$.data[0].regionLevel").value("VILLAGE"))
                .andExpect(jsonPath("$.data[0].samplePointCount").value(3))
                .andExpect(jsonPath("$.data[0].unresolvedSourceCount").value(3))
                .andExpect(jsonPath("$.data[0].categoryCode").doesNotExist())
                .andExpect(jsonPath("$.data[0].pointGeometry").doesNotExist());

        mvc.perform(get("/api/v1/overview/sample-point-aggregates")
                        .principal(() -> "production-tester")
                        .queryParam("parentCode", TOWNSHIP)
                        .queryParam("categoryCode", "MARKET")
                        .queryParam("typeCode", "TRADER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].samplePointCount").value(3))
                .andExpect(jsonPath("$.data[0].unresolvedSourceCount").value(3));
    }

    @Test
    void filtersListsButExposesGeometryOnlyForCategorizedVillageIcons() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("typeCode", "FARMER")
                        .queryParam("query", "同一跨产品"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regionCode").value(VILLAGE))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.unresolvedSourceCount").value(3))
                .andExpect(jsonPath("$.data.categories[?(@.code == 'PRODUCTION')].name")
                        .value(org.hamcrest.Matchers.hasItem("产情类")))
                .andExpect(jsonPath("$.data.categories[?(@.code == 'PRODUCTION')].types[?(@.code == 'FARMER')].name")
                        .value(org.hamcrest.Matchers.hasItem("农户")))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].samplePointId").value(SURVEY_POINT))
                .andExpect(jsonPath("$.data.items[0].products.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].longitude").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].pointGeometry").doesNotExist());

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(SURVEY_POINT))
                .andExpect(jsonPath("$.data[0].longitude").isNumber())
                .andExpect(jsonPath("$.data[0].latitude").isNumber());

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("categoryCode", "LOGISTICS")
                        .queryParam("typeCode", "RAIL_NODE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].samplePointId").value(LOGISTICS_POINT));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", TOWNSHIP)
                        .queryParam("categoryCode", "PRODUCTION"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OVERVIEW_SAMPLE_POINT_QUERY"));

        mvc.perform(get("/api/v1/overview/sample-point-icons")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsOneAuthorizedDetailWithAllApprovedAssociationsAndNoMapCommand() throws Exception {
        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.samplePointId").value(SURVEY_POINT))
                .andExpect(jsonPath("$.data.name").value("同一跨产品样本点"))
                .andExpect(jsonPath("$.data.locationState").value("VALID"))
                .andExpect(jsonPath("$.data.associations.length()").value(4))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'PRODUCTION')].productCode")
                        .value(org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'MARKET')].typeName")
                        .value(org.hamcrest.Matchers.hasItem("贸易商")))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'PRODUCTION' && @.productCode == 'CORN')].businessValues.CONTACT.value")
                        .value(org.hamcrest.Matchers.hasItem("13900000000")))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'PRODUCTION' && @.productCode == 'CORN')].businessValues.ESTIMATED_OUTPUT_KG.value")
                        .value(org.hamcrest.Matchers.hasItem("200")))
                .andExpect(jsonPath("$.data.associations[?(@.categoryCode == 'MARKET')].businessValues.OPENING_INVENTORY_TONNES.value")
                        .value(org.hamcrest.Matchers.hasItem("80")))
                .andExpect(jsonPath("$.data.longitude").doesNotExist())
                .andExpect(jsonPath("$.data.pointGeometry").doesNotExist());

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", DRAFT_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OVERVIEW_SAMPLE_POINT_NOT_FOUND"));
    }

    @Test
    void excludesAssociationsWhoseBusinessSourceRegionIsOutsideTheReaderScope() throws Exception {
        insertProductionAtRegion("94000000-0000-0000-0000-000000000108", "CORN", "APPROVED",
                SURVEY_POINT, "230281");
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id='production-tester'").update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES('production-tester',:region)
                """).param("region", VILLAGE).update();

        mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.associations.length()").value(4));
    }

    @Test
    void enforcesExistingRegionAndObjectTypeAuthorization() throws Exception {
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id='production-tester'").update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES('production-tester',:region)
                """).param("region", VILLAGE).update();

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", "230281"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        mvc.perform(get("/api/v1/overview/sample-points")
                        .principal(() -> "production-tester")
                        .queryParam("regionCode", VILLAGE)
                        .queryParam("categoryCode", "PRODUCTION")
                        .queryParam("typeCode", "TRADER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OVERVIEW_SAMPLE_POINT_QUERY"));
    }

    @Test
    void cannotReadApprovedRowsBeforeTheirTransactionCommits() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO production.production_record(
                      record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                      cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,sample_point_id)
                    VALUES('94000000-0000-0000-0000-000000000109','CORN','FARMER',?,DATE '2026-08-05',
                      TIMESTAMPTZ '2026-08-06 08:00:00+08',10,20,'APPROVED','production-tester',
                      CAST(? AS uuid))
                    """)) {
                statement.setString(1, VILLAGE);
                statement.setString(2, SURVEY_POINT);
                statement.executeUpdate();

                mvc.perform(get("/api/v1/overview/sample-points/{samplePointId}", SURVEY_POINT)
                                .principal(() -> "production-tester")
                                .queryParam("regionCode", VILLAGE))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.associations.length()").value(4));
            } finally {
                connection.rollback();
            }
        }
    }

    private void insertRegionAndBoundaryFixtures() {
        jdbc.sql("""
                INSERT INTO platform.region(code,name,parent_code,administrative_level,sort_order)
                VALUES(:township,'契约测试乡','230202','TOWNSHIP',997),
                      (:village,'契约测试村',:township,'VILLAGE',1)
                """).param("township", TOWNSHIP).param("village", VILLAGE).update();
        jdbc.sql("""
                WITH township AS (
                  SELECT ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123.9,47.3),4326),0.005)) geometry
                )
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                SELECT :township,geometry,'sample-point contract fixture','urn:test:sample-point-query',
                       'test-v1','Test fixture',:township,DATE '2026-08-11',repeat('9',64)
                FROM township
                UNION ALL
                SELECT :village,ST_Multi(ST_Buffer(ST_PointOnSurface(geometry),0.002)),
                       'sample-point contract fixture','urn:test:sample-point-query','test-v1',
                       'Test fixture',:village,DATE '2026-08-11',repeat('8',64)
                FROM township
                """).param("township", TOWNSHIP).param("village", VILLAGE).update();
    }

    private void insertSamplePointFixtures() {
        insertValidPoint(SURVEY_POINT, "SURVEY_SITE", "同一跨产品样本点", "APPROVED");
        insertValidPoint(LOGISTICS_POINT, "LOGISTICS_NODE", "铁路物流节点", "APPROVED");
        insertValidPoint(DRAFT_POINT, "SURVEY_SITE", "未批准样本点", "DRAFT");
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  effective_from,created_by,updated_by)
                VALUES(CAST(:id AS uuid),'SURVEY_SITE','缺少坐标样本点',:region,'APPROVED','MISSING',
                  DATE '2026-01-01','production-tester','production-tester')
                """).param("id", MISSING_POINT).param("region", VILLAGE).update();
    }

    private void insertValidPoint(String id, String kind, String name, String approvalState) {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,created_by,updated_by)
                SELECT CAST(:id AS uuid),:kind,:name,:region,:approval,'VALID',
                       ST_PointOnSurface(boundary.geometry),DATE '2026-01-01',
                       'production-tester','production-tester'
                FROM overview.administrative_boundary boundary WHERE boundary.region_code=:region
                """).param("id", id).param("kind", kind).param("name", name)
                .param("region", VILLAGE).param("approval", approvalState).update();
    }

    private void insertApprovedSourceFixtures() {
        insertProduction("94000000-0000-0000-0000-000000000101", "CORN", "APPROVED", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000102", "SOYBEAN", "APPROVED", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000103", "RICE", "APPROVED", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000104", "CORN", "DRAFT", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000110", "CORN", "PENDING_REVIEW", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000111", "CORN", "RETURNED", SURVEY_POINT);
        insertProduction("94000000-0000-0000-0000-000000000105", "CORN", "APPROVED", MISSING_POINT);
        insertProduction("94000000-0000-0000-0000-000000000106", "CORN", "APPROVED", null);
        insertProduction("94000000-0000-0000-0000-000000000107", "CORN", "APPROVED", DRAFT_POINT);
        jdbc.sql("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,trade_direction,carriage_board_amount,packaging_amount,
                  freight_amount,packaging_form,status_code,last_modified_by,party_id,sample_point_id)
                VALUES('94000000-0000-0000-0000-000000000201','CORN','TRADER',:region,
                  DATE '2026-08-05',TIMESTAMPTZ '2026-08-06 08:00:00+08',2500,'PURCHASE',0,0,0,
                  'BULK','APPROVED','market-tester',NULL,CAST(:point AS uuid))
                """).param("region", VILLAGE).param("point", SURVEY_POINT).update();
        jdbc.sql("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES('94000000-0000-0000-0000-000000000101','PROD_SAMPLE_CONTACT','13900000000')
                """).update();
        jdbc.sql("""
                INSERT INTO market.market_record_core_value(
                  record_id,field_code,value,product_code,domain_binding)
                VALUES('94000000-0000-0000-0000-000000000201','MKT_SAMPLE_CONTACT',
                  '13800000000','CORN','EXTENSION')
                """).update();
        jdbc.sql("""
                INSERT INTO market.market_record_fact(
                  record_id,fact_code,value,product_code,object_type_code)
                VALUES('94000000-0000-0000-0000-000000000201','OPENING_INVENTORY',80,'CORN','TRADER'),
                      ('94000000-0000-0000-0000-000000000201','PURCHASE_VOLUME',12,'CORN','TRADER')
                """).update();
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code,sample_point_id)
                VALUES('QUERY_RAIL','铁路物流节点','RAIL_NODE',:region,CAST(:point AS uuid)),
                      ('QUERY_ROAD','公路物流节点','ROAD_NODE',:region,CAST(:point AS uuid))
                """).param("region", VILLAGE).param("point", LOGISTICS_POINT).update();
        jdbc.sql("""
                INSERT INTO logistics.route_event(
                  event_id,product_code,monitoring_period_code,collection_date,reported_at,
                  origin_region_code,origin_node_code,destination_region_code,destination_node_code,
                  transport_mode_code,direction_code,source_organization,reporter,status_code,
                  version,created_by,last_modified_by,created_at,updated_at)
                VALUES('94000000-0000-0000-0000-000000000301','CORN','2026-W32',DATE '2026-08-05',
                  TIMESTAMPTZ '2026-08-06 08:00:00+08',:region,'QUERY_RAIL',:region,'QUERY_ROAD',
                  'RAIL','INFLOW','测试来源','测试员','APPROVED',0,'logistics-tester','logistics-tester',
                  TIMESTAMPTZ '2026-08-06 08:00:00+08',TIMESTAMPTZ '2026-08-06 08:00:00+08')
                """).param("region", VILLAGE).update();
        jdbc.sql("""
                INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code)
                VALUES('94000000-0000-0000-0000-000000000301','ROUTE_VOLUME',36,'吨')
                """).update();
    }

    private void insertProduction(String id, String product, String status, String pointId) {
        insertProductionAtRegion(id, product, status, pointId, VILLAGE);
    }

    private void insertProductionAtRegion(
            String id, String product, String status, String pointId, String regionCode) {
        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,return_reason,last_modified_by,sample_point_id)
                VALUES(:id,:product,'FARMER',:region,DATE '2026-08-05',
                  TIMESTAMPTZ '2026-08-06 08:00:00+08',10,20,:status,
                  CASE WHEN :status='RETURNED' THEN '退回测试' ELSE NULL END,'production-tester',
                  CAST(:point AS uuid))
                """).param("id", id).param("product", product).param("region", regionCode)
                .param("status", status).param("point", pointId).update();
    }

    private void clean() {
        jdbc.sql("""
                TRUNCATE registry.sample_point,production.production_record,market.market_record,
                  logistics.route_event,logistics.logistics_node RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE region_code IN (:township,:village)")
                .param("township", TOWNSHIP).param("village", VILLAGE).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE region_code IN (:township,:village)")
                .param("township", TOWNSHIP).param("village", VILLAGE).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code IN (:township,:village)")
                .param("township", TOWNSHIP).param("village", VILLAGE).update();
        jdbc.sql("DELETE FROM platform.region WHERE code IN (:village,:township)")
                .param("township", TOWNSHIP).param("village", VILLAGE).update();
    }
}
