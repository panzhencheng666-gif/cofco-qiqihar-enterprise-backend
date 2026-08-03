package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.UUID;
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
class OverviewRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE production.production_record,market.market_record,logistics.route_event,logistics.logistics_node,
                  supply.calculation_run RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603)
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code='230200'").update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256
                ) VALUES ('230200',ST_GeomFromText('MULTIPOLYGON(((123 47,124 47,124 48,123 47)))',4326),
                  'test fixture','https://example.invalid/boundary','test','test',repeat('0',64))
                """).update();
    }
    @AfterEach void cleanAfterEach() { clean(); }

    @Test
    void aggregatesOnlyApprovedFactsAcrossTheSelectedRegionHierarchy() throws Exception {
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,survey_date,
                  reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:approved,'CORN','FARMER','230202',DATE '2026-08-01',now(),10,20,'APPROVED','test'),
                      (:draft,'CORN','FARMER','230202',DATE '2026-08-01',now(),99,99,'DRAFT','test')
                """).param("approved", UUID.randomUUID().toString()).param("draft", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                INSERT INTO market.market_record(record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,trade_direction,carriage_board_amount,freight_amount,status_code,last_modified_by)
                VALUES(:approved,'CORN','TRADER','230202',DATE '2026-08-02',now(),2000,'PURCHASE',30,20,'APPROVED','test'),
                      (:draft,'CORN','TRADER','230202',DATE '2026-08-02',now(),9999,'PURCHASE',0,0,'DRAFT','test')
                """).param("approved", UUID.randomUUID().toString()).param("draft", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES('OV-A','A','RAIL_NODE','231100'),('OV-B','B','RAIL_NODE','230202')
                """).update();
        String eventId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO logistics.route_event(event_id,product_code,monitoring_period_code,collection_date,reported_at,
                  origin_region_code,origin_node_id,origin_node_code,destination_region_code,destination_node_id,destination_node_code,
                  transport_mode_code,direction_code,source_organization,reporter,status_code,created_by,last_modified_by,created_at,updated_at)
                SELECT CAST(:id AS uuid),'CORN','2026-Q3',DATE '2026-08-03',now(),'231100',origin.node_id,origin.node_code,
                  '230202',destination.node_id,destination.node_code,'RAIL','INFLOW','测试','test','APPROVED','test','test',now(),now()
                FROM logistics.logistics_node origin CROSS JOIN logistics.logistics_node destination
                WHERE origin.node_code='OV-A' AND destination.node_code='OV-B'
                """).param("id", eventId).update();
        jdbc.sql("INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code) VALUES(CAST(:id AS uuid),'ROUTE_VOLUME',2,'万吨')")
                .param("id", eventId).update();

        mvc.perform(get("/api/v1/overview/options"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.products[0].code").value("CORN"))
                .andExpect(jsonPath("$.data.periods[0].code").value("2026-Q3"));
        mvc.perform(get("/api/v1/overview/regions").queryParam("productCode", "CORN").queryParam("periodCode", "2026-Q3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].code").value("230200"))
                .andExpect(jsonPath("$.data[0].boundaryGeoJson").isString());
        mvc.perform(get("/api/v1/overview/indicators").queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200").queryParam("periodCode", "2026-Q3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].value").value("10"))
                .andExpect(jsonPath("$.data[1].value").value("200"))
                .andExpect(jsonPath("$.data[2].value").value("2050"))
                .andExpect(jsonPath("$.data[3].value").value("20000"))
                .andExpect(jsonPath("$.data[0].sourceCount").value(1))
                .andExpect(jsonPath("$.data[5].value").value("0"));
    }
}
