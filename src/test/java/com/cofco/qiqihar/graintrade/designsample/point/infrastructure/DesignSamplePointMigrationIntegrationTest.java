package com.cofco.qiqihar.graintrade.designsample.point.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class DesignSamplePointMigrationIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void rejectsAStoredCoordinateOutsideTheSelectedAuthoritativeRegion() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO platform.design_sample_point(
                  design_sample_point_id,contract_version,domain_code,product_code,
                  object_type_code,values_json,sample_name,region_code,governed_point,
                  idempotency_key,request_digest,created_by,updated_by)
                VALUES(:id,'design-sample-fields-v1','PRODUCTION','CORN','FARMER',
                  CAST(:values AS jsonb),'越界设计样本点','230202',
                  ST_SetSRID(ST_MakePoint(130,50),4326),
                  'migration-containment-red',repeat('a',64),'production-tester','production-tester')
                """).param("id", UUID.randomUUID()).param("values", """
                        {"DSP_NAME":"越界设计样本点","DSP_REGION_CODE":"230202",
                         "DSP_LONGITUDE":130,"DSP_LATITUDE":50,
                         "OBSERVED_ON":"2026-06-01","PROD_AREA_MU":1}
                        """).update())
                .hasMessageContaining("outside selected administrative region")
                .hasMessageContaining("230202");
    }

    @Test
    void isYearIndependentRuntimeWritableAndSeparateFromExistingSampleStores() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='platform' AND table_name='design_sample_point'
                  AND column_name IN ('survey_year','network_year','year')
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT has_table_privilege('qiqihar_enterprise_runtime',
                  'platform.design_sample_point','SELECT,INSERT,UPDATE,DELETE')
                """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.table_privileges
                WHERE grantee='PUBLIC' AND table_schema='platform'
                  AND table_name='design_sample_point'
                  AND privilege_type IN ('SELECT','INSERT','UPDATE','DELETE')
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*)
                FROM pg_constraint constraint_record
                WHERE constraint_record.conrelid='platform.design_sample_point'::regclass
                  AND constraint_record.contype='f'
                  AND constraint_record.confrelid IN (
                    'registry.sample_point'::regclass,
                    'registry.sample_network_year'::regclass,
                    'registry.sample_network_membership'::regclass,
                    'platform.region_location'::regclass)
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT obj_description('platform.design_sample_point'::regclass)
                """).query(String.class).single()).contains("Year-independent");
    }
}
