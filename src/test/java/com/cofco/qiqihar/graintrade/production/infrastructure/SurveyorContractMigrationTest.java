package com.cofco.qiqihar.graintrade.production.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SurveyorContractMigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting", "workflow", "overview",
        "evidence", "registry"
    };

    @AfterEach
    void restoreLatestSchema() {
        DATABASE.flyway().migrate();
    }

    @Test
    void v127SeparatesSubmitterAndSurveyorWithoutDestroyingCultivarOrConflictEvidence() throws Exception {
        resetDatabase();
        assertThat(DATABASE.flywayToVersion("126").migrate().migrationsExecuted).isEqualTo(126);
        assertThat(query("SELECT count(*) FROM production.production_record")).isEqualTo("0");
        assertThat(query("SELECT count(*) FROM market.market_record")).isEqualTo("0");
        assertThat(query("SELECT count(*) FROM logistics.route_event")).isEqualTo("0");
        installProductionFixtures();
        installMarketFixture();
        installLogisticsFixture();

        assertThat(DATABASE.flywayToVersion("127").migrate().migrationsExecuted).isOne();

        assertThat(query("""
                SELECT string_agg(field_code || ':' || sort_order,',' ORDER BY sort_order)
                FROM platform.page_definition_field
                WHERE product_code='CORN' AND business_domain='PRODUCTION' AND page_kind='MONITORING'
                  AND field_code IN ('PROD_REPORTER_NAME','PROD_REPORTER_PHONE','PROD_SURVEYOR_NAME',
                    'PROD_SURVEYOR_PHONE','PROD_SAMPLE_CONTACT','PROD_SAMPLE_LATITUDE','PROD_SAMPLE_LONGITUDE')
                """)).isEqualTo("PROD_REPORTER_NAME:92,PROD_SURVEYOR_NAME:93,PROD_SURVEYOR_PHONE:94,"
                        + "PROD_SAMPLE_CONTACT:95,PROD_SAMPLE_LATITUDE:96,PROD_SAMPLE_LONGITUDE:97");
        assertThat(query("""
                SELECT string_agg(field_code || ':' || sort_order,',' ORDER BY sort_order)
                FROM platform.page_definition_field
                WHERE product_code='CORN' AND business_domain='MARKET' AND page_kind='MONITORING'
                  AND field_code IN ('MKT_REPORTER_NAME','MKT_REPORTER_PHONE','MKT_SURVEYOR_NAME',
                    'MKT_SURVEYOR_PHONE','MKT_SAMPLE_CONTACT','MKT_SAMPLE_LATITUDE','MKT_SAMPLE_LONGITUDE',
                    'MKT_SAMPLE_NAME','MKT_CULTIVAR_NAME','MKT_SAMPLE_SUBJECT_CODE')
                """)).isEqualTo("MKT_REPORTER_NAME:120,MKT_SURVEYOR_NAME:121,MKT_SURVEYOR_PHONE:122,"
                        + "MKT_SAMPLE_CONTACT:123,MKT_SAMPLE_LATITUDE:124,MKT_SAMPLE_LONGITUDE:125,"
                        + "MKT_SAMPLE_NAME:126,MKT_CULTIVAR_NAME:127,MKT_SAMPLE_SUBJECT_CODE:128");
        assertThat(query("""
                SELECT string_agg(field_code || ':' || value,',' ORDER BY field_code)
                FROM production.production_record_submission_metadata
                WHERE record_id='surveyor-corn'
                  AND field_code IN ('PROD_CULTIVAR_NAME','PROD_REPORTER_PHONE',
                    'PROD_SURVEYOR_NAME','PROD_SURVEYOR_PHONE')
                """)).isEqualTo("PROD_SURVEYOR_NAME:王雷,PROD_SURVEYOR_PHONE:13800000000");
        assertThat(query("""
                SELECT string_agg(record_id || ':' || value,',' ORDER BY record_id)
                FROM production.production_record_submission_metadata
                WHERE field_code='PROD_CULTIVAR_NAME'
                  AND record_id IN ('surveyor-rice-round','surveyor-rice-long','surveyor-soybean')
                """)).isEqualTo("surveyor-rice-long:长粒香,surveyor-rice-round:圆粒粳稻,surveyor-soybean:大豆");
        assertThat(query("""
                SELECT string_agg(field_code || ':' || value,',' ORDER BY field_code)
                FROM production.production_record_submission_metadata
                WHERE record_id='surveyor-conflict'
                  AND field_code IN ('PROD_CULTIVAR_NAME','PROD_SURVEYOR_NAME')
                """)).isEqualTo("PROD_CULTIVAR_NAME:另一姓名,PROD_SURVEYOR_NAME:已核对调研人");
        assertThat(query("""
                SELECT string_agg(field_code || ':' || value,',' ORDER BY field_code)
                FROM market.market_record_core_value
                WHERE record_id='surveyor-market'
                  AND field_code IN ('MKT_REPORTER_PHONE','MKT_SURVEYOR_PHONE')
                """)).isEqualTo("MKT_SURVEYOR_PHONE:13900000000");
        assertThat(query("""
                SELECT string_agg(definition.code || ':' || applicability.sort_order,',' ORDER BY applicability.sort_order)
                FROM platform.logistics_core_field_applicability applicability
                JOIN platform.logistics_core_field_definition definition ON definition.code=applicability.field_code
                WHERE applicability.product_code='CORN'
                  AND definition.code IN ('LOG_REPORTER_PHONE','LOG_SURVEYOR_NAME','LOG_SURVEYOR_PHONE',
                    'LOG_SAMPLE_CONTACT')
                """)).isEqualTo("LOG_SURVEYOR_NAME:70,LOG_SURVEYOR_PHONE:75,LOG_SAMPLE_CONTACT:80");
        assertThat(query("""
                SELECT value FROM logistics.route_event_core_value
                WHERE event_id='12700000-0000-0000-0000-000000000001'
                  AND field_code='LOG_SURVEYOR_PHONE'
                """)).isEqualTo("13700000000");
    }

    @Test
    void v128UnmountsSpecificCultivarWithoutDeletingHistoricalValues() throws Exception {
        resetDatabase();
        assertThat(DATABASE.flywayToVersion("127").migrate().migrationsExecuted).isEqualTo(127);
        installProductionFixtures();
        execute("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,sale_base_price,trade_direction,status_code,last_modified_by)
                VALUES('surveyor-market','CORN','FEED_MILL','230202',DATE '2026-08-01',CURRENT_TIMESTAMP,
                  2300,2380,'BOTH','APPROVED','fixture')
                """);
        execute("""
                INSERT INTO market.market_record_core_value(
                  record_id,field_code,value,product_code,domain_binding)
                VALUES('surveyor-market','MKT_CULTIVAR_NAME','历史市场品种','CORN','EXTENSION')
                """);

        assertThat(DATABASE.flywayToVersion("128").migrate().migrationsExecuted).isOne();

        assertThat(query("""
                SELECT count(*) FROM platform.page_definition_field
                WHERE page_kind='MONITORING'
                  AND field_code IN ('PROD_CULTIVAR','MKT_CULTIVAR_NAME')
                """)).isEqualTo("6");
        assertThat(query("""
                SELECT count(*) FROM platform.page_column_group_field
                WHERE page_kind='MONITORING'
                  AND field_code IN ('PROD_CULTIVAR','MKT_CULTIVAR_NAME')
                """)).isEqualTo("0");
        assertThat(query("""
                SELECT count(*) FROM platform.market_core_field_applicability
                WHERE page_kind='MONITORING' AND field_code='MKT_CULTIVAR_NAME'
                """)).isEqualTo("3");
        assertThat(query("""
                SELECT value FROM production.production_record_submission_metadata
                WHERE record_id='surveyor-rice-round' AND field_code='PROD_CULTIVAR_NAME'
                """)).isEqualTo("圆粒粳稻");
        assertThat(query("""
                SELECT value FROM market.market_record_core_value
                WHERE record_id='surveyor-market' AND field_code='MKT_CULTIVAR_NAME'
                """)).isEqualTo("历史市场品种");
    }

    private void installProductionFixtures() throws Exception {
        execute("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES
                  ('surveyor-corn','CORN','FARMER','230202',DATE '2026-08-01',CURRENT_TIMESTAMP,10,500,'APPROVED','fixture'),
                  ('surveyor-rice-round','RICE','FARMER','230202',DATE '2026-08-01',CURRENT_TIMESTAMP,10,500,'APPROVED','fixture'),
                  ('surveyor-rice-long','RICE','FARMER','230202',DATE '2026-08-01',CURRENT_TIMESTAMP,10,500,'APPROVED','fixture'),
                  ('surveyor-soybean','SOYBEAN','FARMER','230202',DATE '2026-08-01',CURRENT_TIMESTAMP,10,500,'APPROVED','fixture'),
                  ('surveyor-conflict','CORN','FARMER','230202',DATE '2026-08-01',CURRENT_TIMESTAMP,10,500,'APPROVED','fixture')
                """);
        execute("""
                INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                VALUES
                  ('surveyor-corn','PROD_CULTIVAR_NAME','王雷'),
                  ('surveyor-corn','PROD_REPORTER_PHONE','13800000000'),
                  ('surveyor-rice-round','PROD_CULTIVAR_NAME','圆粒粳稻'),
                  ('surveyor-rice-long','PROD_CULTIVAR_NAME','长粒香'),
                  ('surveyor-soybean','PROD_CULTIVAR_NAME','大豆'),
                  ('surveyor-conflict','PROD_CULTIVAR_NAME','另一姓名'),
                  ('surveyor-conflict','PROD_SURVEYOR_NAME','已核对调研人')
                """);
    }

    private void installMarketFixture() throws Exception {
        execute("""
                INSERT INTO market.market_record(
                  record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                  purchase_base_price,sale_base_price,trade_direction,status_code,last_modified_by)
                VALUES('surveyor-market','CORN','FEED_MILL','230202',DATE '2026-08-01',CURRENT_TIMESTAMP,
                  2300,2380,'BOTH','APPROVED','fixture')
                """);
        execute("""
                INSERT INTO market.market_record_core_value(record_id,field_code,value,product_code,domain_binding)
                VALUES('surveyor-market','MKT_REPORTER_PHONE','13900000000','CORN','EXTENSION')
                """);
    }

    private void installLogisticsFixture() throws Exception {
        execute("""
                INSERT INTO logistics.route_event(
                  event_id,product_code,collection_date,reported_at,origin_region_code,destination_region_code,
                  transport_mode_code,direction_code,source_organization,reporter,status_code,created_by,
                  last_modified_by,created_at,updated_at,business_region_code,reporter_phone)
                VALUES('12700000-0000-0000-0000-000000000001','CORN',DATE '2026-08-01',CURRENT_TIMESTAMP,
                  '230202','230200','RAIL','INFLOW','迁移物流样本点','登录填报人','APPROVED','fixture','fixture',
                  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'230202','13700000000')
                """);
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String query(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
