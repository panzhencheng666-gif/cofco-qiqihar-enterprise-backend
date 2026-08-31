package com.cofco.qiqihar.graintrade.designsample.point.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class QiqiharOpenBoundaryMigrationIntegrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting",
        "workflow", "overview", "evidence", "registry"
    };
    private JdbcClient jdbc;

    @BeforeEach
    void reset() throws Exception {
        resetDatabase();
        jdbc = JdbcClient.create(DATABASE.dataSource());
    }

    @AfterEach
    void restoreLatestSchema() throws Exception {
        resetDatabase();
        DATABASE.flyway().migrate();
    }

    @Test
    void freshMigrationSeedsSeventeenTraceableValidQiqiharBoundaries() {
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(160);
        assertBoundaryDataset();
    }

    @Test
    void upgradesV159ToV160WithoutChangingExistingBusinessStores() {
        assertThat(DATABASE.flywayToVersion("159").migrate().migrationsExecuted).isEqualTo(159);
        long formalSamples = count("registry.sample_point");
        long townships = jdbc.sql("""
                SELECT count(*) FROM platform.region WHERE administrative_level='TOWNSHIP'
                """).query(Long.class).single();

        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isOne();

        assertThat(count("registry.sample_point")).isEqualTo(formalSamples);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.region WHERE administrative_level='TOWNSHIP'
                """).query(Long.class).single()).isEqualTo(townships);
        assertBoundaryDataset();
    }

    @Test
    void rejectsAnExistingQiqiharBoundaryWithDifferentGeometryAndProvenance() {
        assertThat(DATABASE.flywayToVersion("159").migrate().migrationsExecuted).isEqualTo(159);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,geometry_sha256)
                VALUES('230202',ST_Multi(ST_MakeEnvelope(120,40,121,41,4326)),
                  'conflicting boundary','urn:test:conflict','conflict-v1','Test fixture',
                  'conflicting-230202',repeat('f',64))
                """).update();

        assertThatThrownBy(() -> DATABASE.flyway().migrate())
                .hasMessageContaining("V160 refuses conflicting Qiqihar administrative boundary")
                .hasMessageContaining("230202");
        assertThat(jdbc.sql("""
                SELECT source_url FROM overview.administrative_boundary WHERE region_code='230202'
                """).query(String.class).single()).isEqualTo("urn:test:conflict");
        assertThat(jdbc.sql("SELECT to_regclass('overview.administrative_boundary_dataset')")
                .query(String.class).optional()).isEmpty();
    }

    private void assertBoundaryDataset() {
        assertThat(jdbc.sql("""
                SELECT source_owner || '|' || source_revision || '|' || source_build_on || '|' ||
                       represented_year || '|' || source_crs || '|' || derived_crs || '|' ||
                       source_license || '|' || official_survey || '|' || original_sha256 || '|' ||
                       derived_sha256
                FROM overview.administrative_boundary_dataset
                WHERE dataset_id='geoboundaries-chn-adm3-9469f09-qiqihar-2017'
                """).query(String.class).single()).isEqualTo(
                "OpenStreetMap contributors and geoBoundaries|geoBoundaries commit 9469f09|" +
                "2023-12-12|2017|OGC CRS84|EPSG:4326|ODbL-1.0|false|" +
                "3cc71d6cd23e7dbb5646422b40dd92a7a74d8779f340eb9377158c961dec310e|" +
                "b80bf91b0c11ecc1b34fa3bc7a75cab0aaf9d197d452772de17d557c0cc0a824");
        assertThat(jdbc.sql("""
                SELECT string_agg(region_code,',' ORDER BY region_code)
                FROM overview.administrative_boundary
                WHERE source_dataset_id='geoboundaries-chn-adm3-9469f09-qiqihar-2017'
                """).query(String.class).single()).isEqualTo(
                "230200,230202,230203,230204,230205,230206,230207,230208," +
                "230221,230223,230224,230225,230227,230229,230230,230231,230281");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM overview.administrative_boundary
                WHERE source_dataset_id='geoboundaries-chn-adm3-9469f09-qiqihar-2017'
                  AND (GeometryType(geometry)<>'MULTIPOLYGON' OR NOT ST_IsValid(geometry)
                    OR ST_IsEmpty(geometry) OR ST_SRID(geometry)<>4326
                    OR geometry_sha256<>encode(sha256(ST_AsEWKB(geometry)),'hex'))
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT encode(sha256(ST_AsEWKB(geometry)),'hex')
                FROM overview.administrative_boundary WHERE region_code='230200'
                """).query(String.class).single())
                .isEqualTo("f9c5917f68cc0f0d145de145a05b04665013716adfafd67d5c1f75168d1dc505");
        assertThat(jdbc.sql("""
                SELECT ST_NumGeometries(geometry)=1
                  AND ST_Area(geometry::geography)/1000000 BETWEEN 42000 AND 42400
                FROM overview.administrative_boundary WHERE region_code='230200'
                """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM overview.administrative_boundary child
                JOIN overview.administrative_boundary city ON city.region_code='230200'
                WHERE child.source_dataset_id='geoboundaries-chn-adm3-9469f09-qiqihar-2017'
                  AND child.region_code<>'230200' AND NOT ST_CoveredBy(child.geometry,city.geometry)
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM overview.administrative_boundary left_boundary
                JOIN overview.administrative_boundary right_boundary
                  ON right_boundary.region_code>left_boundary.region_code
                WHERE left_boundary.source_dataset_id='geoboundaries-chn-adm3-9469f09-qiqihar-2017'
                  AND right_boundary.source_dataset_id=left_boundary.source_dataset_id
                  AND left_boundary.region_code<>'230200' AND right_boundary.region_code<>'230200'
                  AND ST_Area(ST_Intersection(
                    left_boundary.geometry,right_boundary.geometry)::geography)>1
                """).query(Long.class).single()).isZero();
    }

    private long count(String relation) {
        return jdbc.sql("SELECT count(*) FROM " + relation).query(Long.class).single();
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection();
                Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
