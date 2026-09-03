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
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(169);
        assertBoundaryDataset();
        assertThat(count("overview.administrative_boundary_v160_archive")).isZero();
    }

    @Test
    void upgradesV159ThroughV169WithoutChangingExistingBusinessStores() {
        assertThat(DATABASE.flywayToVersion("159").migrate().migrationsExecuted).isEqualTo(157);
        long formalSamples = count("registry.sample_point");
        long townships = jdbc.sql("""
                SELECT count(*) FROM platform.region WHERE administrative_level='TOWNSHIP'
                """).query(Long.class).single();

        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(12);

        assertThat(count("registry.sample_point")).isEqualTo(formalSamples);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.region WHERE administrative_level='TOWNSHIP'
                """).query(Long.class).single()).isEqualTo(townships);
        assertBoundaryDataset();
    }

    @Test
    void rejectsAnExistingQiqiharBoundaryWithDifferentGeometryAndProvenance() {
        assertThat(DATABASE.flywayToVersion("159").migrate().migrationsExecuted).isEqualTo(157);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,geometry_sha256)
                VALUES('230202',ST_Multi(ST_MakeEnvelope(120,40,121,41,4326)),
                  'conflicting boundary','urn:test:conflict','conflict-v1','Test fixture',
                  'conflicting-230202',repeat('f',64))
                """).update();

        assertThatThrownBy(() -> DATABASE.flyway().migrate())
                .hasMessageContaining("V159.1 refuses unknown Qiqihar boundary state")
                .hasMessageContaining("count 1, invalid 1");
        assertThat(jdbc.sql("""
                SELECT source_url FROM overview.administrative_boundary WHERE region_code='230202'
                """).query(String.class).single()).isEqualTo("urn:test:conflict");
        assertThat(jdbc.sql("SELECT to_regclass('overview.administrative_boundary_dataset')")
                .query(String.class).optional()).isEmpty();
    }

    @Test
    void normalizesOnlySubvisibleTownshipRenderArtifactsBeforeVillageRepartition() {
        assertThat(DATABASE.flywayToVersion("159.2").migrate().migrationsExecuted).isEqualTo(159);
        String townshipCode = "990001001";
        insertTownshipRenderFixture(townshipCode);
        jdbc.sql("""
                UPDATE overview.administrative_boundary_render
                SET geometry=ST_Multi(ST_Difference(
                      geometry,ST_Buffer(ST_PointOnSurface(geometry),0.000001)
                    ))::geometry(MultiPolygon,4326)
                WHERE region_code=:regionCode
                """).param("regionCode", townshipCode).update();

        assertThat(interiorRingCount(townshipCode)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT overview.normalize_subvisible_township_render_artifacts()")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(interiorRingCount(townshipCode)).isZero();
        assertThat(jdbc.sql("""
                SELECT geo_json=ST_AsGeoJSON(geometry,7)
                FROM overview.administrative_boundary_render WHERE region_code=:regionCode
                """).param("regionCode", townshipCode).query(Boolean.class).single()).isTrue();

        jdbc.sql("""
                UPDATE overview.administrative_boundary_render
                SET geometry=ST_Multi(ST_Difference(
                      geometry,ST_Buffer(ST_PointOnSurface(geometry),0.0001)
                    ))::geometry(MultiPolygon,4326)
                WHERE region_code=:regionCode
                """).param("regionCode", townshipCode).update();
        assertThat(jdbc.sql("SELECT overview.normalize_subvisible_township_render_artifacts()")
                .query(Integer.class).single()).isZero();
        assertThat(interiorRingCount(townshipCode)).isEqualTo(1);

        jdbc.sql("""
                WITH fixture AS (
                  SELECT ST_Multi(ST_UnaryUnion(ST_Collect(
                    boundary.geometry,
                    ST_MakeEnvelope(123.02,47,123.020001,47.000001,4326)
                  )))::geometry(MultiPolygon,4326) geometry
                  FROM overview.administrative_boundary boundary
                  WHERE boundary.region_code=:regionCode
                )
                UPDATE overview.administrative_boundary_render render
                SET geometry=fixture.geometry
                FROM fixture WHERE render.region_code=:regionCode
                """).param("regionCode", townshipCode).update();
        assertThat(geometryPartCount(townshipCode)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT overview.normalize_subvisible_township_render_artifacts()")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(geometryPartCount(townshipCode)).isEqualTo(1);
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

    private int interiorRingCount(String regionCode) {
        return jdbc.sql("""
                SELECT ST_NumInteriorRings(ST_GeometryN(geometry,1))
                FROM overview.administrative_boundary_render WHERE region_code=:regionCode
                """).param("regionCode", regionCode).query(Integer.class).single();
    }

    private int geometryPartCount(String regionCode) {
        return jdbc.sql("""
                SELECT ST_NumGeometries(geometry)
                FROM overview.administrative_boundary_render WHERE region_code=:regionCode
                """).param("regionCode", regionCode).query(Integer.class).single();
    }

    private void insertTownshipRenderFixture(String regionCode) {
        jdbc.sql("ALTER TABLE platform.region DISABLE TRIGGER USER").update();
        try {
            jdbc.sql("""
                    INSERT INTO platform.region(code,name,parent_code,administrative_level,sort_order)
                    VALUES(:regionCode,'subvisible-ring-test','230202','TOWNSHIP',990001001)
                    """).param("regionCode", regionCode).update();
        } finally {
            jdbc.sql("ALTER TABLE platform.region ENABLE TRIGGER USER").update();
        }
        jdbc.sql("""
                WITH fixture AS (
                  SELECT ST_Multi(ST_MakeEnvelope(123,47,123.01,47.01,4326))
                    ::geometry(MultiPolygon,4326) geometry
                )
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,geometry_sha256)
                SELECT :regionCode,geometry,'test fixture','urn:test:subvisible-ring','test-v1',
                       'Test fixture','test-subvisible-ring',
                       encode(sha256(ST_AsEWKB(geometry)),'hex')
                FROM fixture
                """).param("regionCode", regionCode).update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary_render(
                  region_code,geometry,geo_json,simplify_tolerance,full_point_count,
                  render_point_count,source_geometry_sha256,source_name,source_revision,
                  source_license)
                SELECT region_code,geometry,ST_AsGeoJSON(geometry,7),0,ST_NPoints(geometry),
                       ST_NPoints(geometry),geometry_sha256,source_name,source_revision,
                       source_license
                FROM overview.administrative_boundary WHERE region_code=:regionCode
                """).param("regionCode", regionCode).update();
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
