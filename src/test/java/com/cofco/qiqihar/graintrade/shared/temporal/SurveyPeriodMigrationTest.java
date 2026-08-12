package com.cofco.qiqihar.graintrade.shared.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SurveyPeriodMigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting", "workflow", "overview", "evidence",
        "registry"
    };

    @AfterEach
    void restoreLatestSharedSchemaAndSecurityFixtures() {
        DATABASE.flyway().migrate();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(JdbcClient.create(DATABASE.dataSource()));
    }

    @Test
    void backfillsProvenSurveyPeriodsAndSubmissionAuditsWithoutInventingAmbiguousLogisticsMonths()
            throws Exception {
        resetDatabase();
        DATABASE.flywayToVersion("87").migrate();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(JdbcClient.create(DATABASE.dataSource()));

        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                      survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by,created_at)
                    VALUES('survey-period-production','CORN','FARMER','230200',DATE '2026-08-10',
                      TIMESTAMPTZ '2026-08-11 09:00:00+08',100,500,'PENDING_REVIEW','migration-test',
                      TIMESTAMPTZ '2026-08-10 08:00:00+08')
                    """);
            statement.execute("""
                    INSERT INTO market.market_record(record_id,product_code,object_type_code,region_code,trade_date,
                      reported_at,purchase_base_price,sale_base_price,trade_direction,status_code,last_modified_by,created_at)
                    VALUES('survey-period-market','CORN','TRADER','230200',DATE '2026-07-20',
                      TIMESTAMPTZ '2026-07-21 09:00:00+08',2300,2310,'PURCHASE','DRAFT','migration-test',
                      TIMESTAMPTZ '2026-07-20 08:00:00+08')
                    """);
            statement.execute("""
                    INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order,marketing_year_code)
                    VALUES('MIGRATION-CROSS-MONTH','跨月物流调查期',DATE '2026-08-28',DATE '2026-09-03',999,'2026/27')
                    """);
            statement.execute("""
                    INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                    VALUES('MIGRATION-ORIGIN','迁移起点','RAIL_NODE','230200'),
                          ('MIGRATION-DESTINATION','迁移终点','ROAD_NODE','230200')
                    """);
            statement.execute("""
                    INSERT INTO logistics.route_event(event_id,product_code,monitoring_period_code,collection_date,
                      reported_at,origin_region_code,origin_node_id,destination_region_code,destination_node_id,
                      transport_mode_code,direction_code,source_organization,reporter,status_code,version,created_by,
                      last_modified_by,created_at,updated_at,origin_node_code,destination_node_code)
                    SELECT '20000000-0000-0000-0000-000000000001','CORN','MIGRATION-CROSS-MONTH',DATE '2026-08-30',
                      TIMESTAMPTZ '2026-08-30 10:00:00+08','230200',origin.node_id,'230200',destination.node_id,
                      'RAIL','INFLOW','迁移来源','迁移填报员','PENDING_REVIEW',0,'migration-test','migration-test',
                      TIMESTAMPTZ '2026-08-30 08:00:00+08',TIMESTAMPTZ '2026-08-30 10:00:00+08',
                      'MIGRATION-ORIGIN','MIGRATION-DESTINATION'
                    FROM logistics.logistics_node origin,logistics.logistics_node destination
                    WHERE origin.node_code='MIGRATION-ORIGIN' AND destination.node_code='MIGRATION-DESTINATION'
                    """);
            statement.execute("""
                    INSERT INTO platform.business_audit_event(event_id,aggregate_type,aggregate_id,action_code,
                      actor_subject_id,work_unit_code,occurred_at,detail)
                    SELECT '30000000-0000-0000-0000-000000000001','PRODUCTION_RECORD','survey-period-production',
                      'PRODUCTION_RECORD_SUBMITTED',subject_id,work_unit_code,
                      TIMESTAMPTZ '2026-08-11 10:30:00+08','{}'::jsonb
                    FROM platform.security_user WHERE subject_id='production-tester'
                    """);
            statement.execute("""
                    INSERT INTO platform.business_audit_event(event_id,aggregate_type,aggregate_id,action_code,
                      actor_subject_id,work_unit_code,occurred_at,detail)
                    SELECT '30000000-0000-0000-0000-000000000002','LOGISTICS_RECORD',
                      '20000000-0000-0000-0000-000000000001','LOGISTICS_RECORD_SUBMITTED',subject_id,work_unit_code,
                      TIMESTAMPTZ '2026-08-30 11:00:00+08','{}'::jsonb
                    FROM platform.security_user WHERE subject_id='logistics-tester'
                    """);
        }

        assertThat(DATABASE.flywayToVersion("88").migrate().migrationsExecuted).isOne();

        assertThat(queryString("""
                SELECT survey_year || ':' || survey_month || ':' || survey_period_precision || ':' ||
                  survey_period_governance_state || ':' || to_char(submitted_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD HH24:MI')
                FROM production.production_record WHERE record_id='survey-period-production'
                """)).isEqualTo("2026:8:YEAR_MONTH:CONFIRMED:2026-08-11 10:30");
        assertThat(queryString("""
                SELECT survey_year || ':' || survey_month || ':' || survey_period_precision || ':' ||
                  survey_period_governance_state || ':' || COALESCE(submitted_at::text,'NO_SUBMISSION')
                FROM market.market_record WHERE record_id='survey-period-market'
                """)).isEqualTo("2026:7:YEAR_MONTH:CONFIRMED:NO_SUBMISSION");
        assertThat(queryString("""
                SELECT survey_year || ':' || COALESCE(survey_month::text,'NO_MONTH') || ':' ||
                  survey_period_precision || ':' || survey_period_governance_state || ':' ||
                  to_char(submitted_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD HH24:MI')
                FROM logistics.route_event WHERE event_id='20000000-0000-0000-0000-000000000001'
                """)).isEqualTo("2026:NO_MONTH:YEAR:PENDING_GOVERNANCE:2026-08-30 11:00");
        assertThat(queryString("""
                SELECT count(*) FROM platform.page_filter_definition
                WHERE product_code='CORN'
                  AND business_domain IN ('PRODUCTION','MARKET','LOGISTICS')
                  AND code IN ('surveyYear','surveyMonth','fillingDateFrom','fillingDateTo')
                """)).isEqualTo("12");
        assertThat(queryString("""
                SELECT string_agg(code || '=' || name, ',' ORDER BY code)
                FROM platform.field_definition
                WHERE code IN ('PROD_REPORTED_AT','MKT_REPORTED_AT','LOG_REPORTED_AT')
                """)).isEqualTo("LOG_REPORTED_AT=物流最后保存时间（兼容字段）,MKT_REPORTED_AT=市场最后保存时间（兼容字段）,PROD_REPORTED_AT=产情最后保存时间（兼容字段）");
        assertThat(DATABASE.flywayToVersion("88").migrate().migrationsExecuted).isZero();
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    private String queryString(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
