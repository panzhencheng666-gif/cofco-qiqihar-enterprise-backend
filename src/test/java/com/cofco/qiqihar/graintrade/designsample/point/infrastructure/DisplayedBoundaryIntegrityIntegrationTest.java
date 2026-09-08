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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
@Transactional
class DisplayedBoundaryIntegrityIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void serializesTheValidGeometryWithoutRoundingItIntoAnInvalidPolygon() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                UPDATE overview.administrative_boundary_render
                SET geometry=ST_Multi(ST_GeomFromText(
                  'POLYGON((124 47,125 47,125 47.0000000001,124 47))',4326)),
                  geo_json=ST_AsGeoJSON(ST_GeomFromText(
                  'POLYGON((124 47,125 47,125 47.0000000001,124 47))',4326),9),
                  full_point_count=4,render_point_count=4
                WHERE region_code='230202'
                """).update();
        assertThat(jdbc.sql("""
                SELECT ST_IsValid(ST_GeomFromGeoJSON(geo_json))
                  AND ST_Equals(geometry,ST_GeomFromGeoJSON(geo_json))
                FROM overview.administrative_boundary_render WHERE region_code='230202'
                """).query(Boolean.class).single()).isTrue();
    }

    @Test
    void cannotPublishDifferentDisplayGeometryThroughATextOnlyUpdate() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                UPDATE overview.administrative_boundary_render SET geo_json=
                  ST_AsGeoJSON(ST_Multi(ST_MakeEnvelope(130,50,131,51,4326)))
                WHERE region_code='230202'
                """).update();
        assertThat(jdbc.sql("""
                SELECT ST_Equals(geometry,ST_GeomFromGeoJSON(geo_json))
                FROM overview.administrative_boundary_render WHERE region_code='230202'
                """).query(Boolean.class).single()).isTrue();
    }

    @Test
    void repositionsDisplayCoordinatesWhenBoundaryChangesWithoutChangingReportedCoordinates() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        insertInsidePoint(jdbc);
        jdbc.sql("""
                UPDATE overview.administrative_boundary_render
                SET geometry=ST_Multi(ST_MakeEnvelope(130,50,131,51,4326)),
                  geo_json=ST_AsGeoJSON(ST_Multi(ST_MakeEnvelope(130,50,131,51,4326))),
                  render_point_count=5
                WHERE region_code='230202'
                """).update();
        assertThat(jdbc.sql("""
                SELECT bool_and(ST_Covers(ST_GeomFromGeoJSON(b.geo_json),p.display_point)
                  AND NOT ST_Covers(ST_GeomFromGeoJSON(b.geo_json),p.governed_point)
                  AND p.location_mode='REGION_SCHEMATIC')
                FROM platform.design_sample_point p
                JOIN overview.administrative_boundary_render b ON b.region_code=p.region_code
                WHERE p.region_code='230202'
                """).query(Boolean.class).single()).isTrue();
    }

    @Test
    void rejectsRemovingTheDisplayBoundaryOfAnExistingDesignPoint() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        insertInsidePoint(jdbc);
        assertThatThrownBy(() -> jdbc.sql("""
                DELETE FROM overview.administrative_boundary_render WHERE region_code='230202'
                """).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("existing design sample");
    }

    @Test
    void permitsABoundaryRefreshThatKeepsTheOriginalPointInside() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        insertInsidePoint(jdbc);
        String original = jdbc.sql("""
                SELECT ST_AsEWKT(governed_point) FROM platform.design_sample_point
                WHERE region_code='230202'
                """).query(String.class).single();
        jdbc.sql("""
                UPDATE overview.administrative_boundary_render SET geo_json=ST_AsGeoJSON(geometry,7)
                WHERE region_code='230202'
                """).update();
        assertThat(jdbc.sql("""
                SELECT ST_AsEWKT(governed_point) FROM platform.design_sample_point
                WHERE region_code='230202'
                """).query(String.class).single()).isEqualTo(original);
        assertThat(jdbc.sql("""
                SELECT overview.design_sample_display_boundary_state(
                  region_code,ST_X(governed_point)::numeric,ST_Y(governed_point)::numeric)
                FROM platform.design_sample_point WHERE region_code='230202'
                """).query(String.class).single()).isEqualTo("INSIDE");
    }

    private static void insertInsidePoint(JdbcClient jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.design_sample_point(
                  design_sample_point_id,contract_version,domain_code,product_code,
                  object_type_code,values_json,sample_name,region_code,governed_point,
                  idempotency_key,request_digest,created_by,updated_by)
                SELECT :id,'design-sample-fields-v1','PRODUCTION','CORN','FARMER',
                  '{}'::jsonb,:name,'230202',ST_PointOnSurface(ST_GeomFromGeoJSON(geo_json)),
                  :key,repeat('a',64),'production-tester','production-tester'
                FROM overview.administrative_boundary_render WHERE region_code='230202'
                """).param("id", id).param("name", "边界更新保护-" + id)
                .param("key", "boundary-guard-" + id).update();
    }
}
