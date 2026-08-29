package com.cofco.qiqihar.graintrade.logistics.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class LogisticsPublicContractMigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting", "workflow", "overview",
        "evidence", "registry"
    };

    @AfterEach
    void restoreLatestSchema() {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            if (tableExists(statement, "logistics", "route_event")) {
                statement.execute("""
                        DELETE FROM logistics.route_event
                        WHERE event_id='21000000-0000-0000-0000-000000000001'
                        """);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to clean logistics migration fixture", failure);
        }
        DATABASE.flyway().migrate();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(
                JdbcClient.create(DATABASE.dataSource()));
    }

    @Test
    void freshMigrationPublishesTheCompleteLogisticsBusinessContract() throws Exception {
        resetDatabase();

        DATABASE.flyway().migrate();
        assertV117AppliedExactlyOnce();
        assertPublicContract();
    }

    @Test
    void upgradesV116DataWithoutExposingOrDestroyingLegacyRouteFields() throws Exception {
        resetDatabase();
        DATABASE.flywayToVersion("116").migrate();
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                    VALUES('LEGACY-ORIGIN','历史起点','RAIL_NODE','230202'),
                          ('LEGACY-DESTINATION','历史终点','ROAD_NODE','230200')
                    """);
            statement.execute("""
                    INSERT INTO logistics.route_event(event_id,product_code,monitoring_period_code,collection_date,
                      origin_region_code,origin_node_id,destination_region_code,destination_node_id,
                      transport_mode_code,direction_code,source_organization,reporter,reported_at,status_code,version,created_by,
                      last_modified_by,created_at,updated_at,origin_node_code,destination_node_code,
                      survey_year,survey_month,survey_period_precision,survey_period_governance_state)
                    SELECT '21000000-0000-0000-0000-000000000001','CORN','2026-W32',DATE '2026-08-09',
                      '230202',origin.node_id,'230200',destination.node_id,'RAIL','INFLOW','历史物流样本点',
                      '历史填报人',CURRENT_TIMESTAMP,'APPROVED',3,'migration-test','migration-test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                      origin.node_code,destination.node_code,2026,8,'YEAR_MONTH','CONFIRMED'
                    FROM logistics.logistics_node origin,logistics.logistics_node destination
                    WHERE origin.node_code='LEGACY-ORIGIN' AND destination.node_code='LEGACY-DESTINATION'
                    """);
        }

        assertThat(DATABASE.flywayToVersion("117").migrate().migrationsExecuted).isEqualTo(1);
        assertV117AppliedExactlyOnce();
        assertV117PublicContract();
        assertThat(query("""
                SELECT business_region_code || '|' || monitoring_period_code || '|' || origin_node_code || '|' ||
                  destination_node_code || '|' || version
                FROM logistics.route_event WHERE event_id='21000000-0000-0000-0000-000000000001'
                """)).isEqualTo("230200|2026-W32|LEGACY-ORIGIN|LEGACY-DESTINATION|3");
        assertThat(query("""
                SELECT count(*) FROM platform.logistics_core_field_applicability applicability
                WHERE applicability.field_code IN
                  ('LOG_PERIOD','LOG_COLLECTION_DATE','LOG_ORIGIN','LOG_DESTINATION','LOG_TRANSIT_TIME')
                """)).isEqualTo("0");
    }

    private void assertPublicContract() throws Exception {
        assertThat(query("""
                SELECT string_agg(definition.code || ':' || definition.label || ':' ||
                  definition.control_type || ':' || definition.required, ',' ORDER BY applicability.sort_order)
                FROM platform.logistics_core_field_applicability applicability
                JOIN platform.logistics_core_field_definition definition ON definition.code=applicability.field_code
                WHERE applicability.product_code='CORN'
                """)).isEqualTo("surveyYear:数据年份:DECIMAL:true,surveyMonth:数据月份:DECIMAL:false," +
                "fillingDate:填报日期:READONLY_DATE:false,LOG_SAMPLE_NAME:物流样本点名称:TEXT:true," +
                "LOG_REGION:地区:SELECT:true,LOG_REPORTER:填报人:READONLY_TEXT:false," +
                "LOG_SURVEYOR_NAME:调研人:TEXT:false,LOG_SURVEYOR_PHONE:调研人联系方式:TEXT:false," +
                "LOG_SAMPLE_CONTACT:物流样本点联系方式:TEXT:true," +
                "LOG_SAMPLE_LATITUDE:纬度:DECIMAL:true,LOG_SAMPLE_LONGITUDE:经度:DECIMAL:true," +
                "LOG_TRANSPORT_MODE:运输方式:SELECT:true,LOG_DIRECTION:运输方向:SELECT:true," +
                "LOG_ROUTE_VOLUME:运输数量:DECIMAL:true,LOG_FREIGHT_RATE:物流运价（不含车板价）:DECIMAL:true," +
                "LOG_BOARD_PRICE:车板价:DECIMAL:true,LOG_STATUS:填报状态:READONLY_STATUS:false");
    }

    private void assertV117PublicContract() throws Exception {
        assertThat(query("""
                SELECT string_agg(definition.code || ':' || definition.label || ':' ||
                  definition.control_type || ':' || definition.required, ',' ORDER BY applicability.sort_order)
                FROM platform.logistics_core_field_applicability applicability
                JOIN platform.logistics_core_field_definition definition ON definition.code=applicability.field_code
                WHERE applicability.product_code='CORN'
                """)).isEqualTo("surveyYear:数据年份:DECIMAL:true,surveyMonth:数据月份:DECIMAL:false," +
                "fillingDate:填报日期:READONLY_DATE:false,LOG_SAMPLE_NAME:物流样本点名称:TEXT:true," +
                "LOG_REGION:地区:SELECT:true,LOG_REPORTER:填报人:READONLY_TEXT:false," +
                "LOG_REPORTER_PHONE:填报人联系方式:TEXT:true,LOG_SAMPLE_CONTACT:物流样本点联系方式:TEXT:true," +
                "LOG_SAMPLE_LATITUDE:纬度:DECIMAL:true,LOG_SAMPLE_LONGITUDE:经度:DECIMAL:true," +
                "LOG_TRANSPORT_MODE:运输方式:SELECT:true,LOG_DIRECTION:运输方向:SELECT:true," +
                "LOG_ROUTE_VOLUME:运输数量:DECIMAL:true,LOG_FREIGHT_RATE:物流运价（不含车板价）:DECIMAL:true," +
                "LOG_BOARD_PRICE:车板价:DECIMAL:true,LOG_STATUS:填报状态:READONLY_STATUS:false");
    }

    private void assertV117AppliedExactlyOnce() throws Exception {
        assertThat(query("""
                SELECT count(*) FROM public.flyway_schema_history
                WHERE version='117' AND script='V117__establish_public_logistics_survey_contract.sql'
                  AND success
                """)).isEqualTo("1");
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    private boolean tableExists(Statement statement, String schema, String table) throws Exception {
        try (ResultSet result = statement.executeQuery("""
                SELECT to_regclass('%s.%s') IS NOT NULL
                """.formatted(schema, table))) {
            result.next();
            return result.getBoolean(1);
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
