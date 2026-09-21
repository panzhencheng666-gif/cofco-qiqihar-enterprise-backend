package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalRailwayRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes=GrainTradeApplication.class,properties="qiqihar.regional-public-data.enabled=false")
@UsesProtectedTestDatabase
@Transactional
class RegionalRailwayRepositoryIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired RegionalRailwayRepository railways;

    @Test void repairedSimplificationKeepsRegionalLineSummaryAvailable() {
        jdbc.sql("DELETE FROM overview.regional_railway_feature").update();
        jdbc.sql("""
          WITH source AS (
            SELECT ST_Collect(
              ST_GeomFromText('POLYGON((0 0,10 0,10 4,5 4,5 6,10 6,10 10,0 10,0 0))'),
              ST_GeomFromText('POLYGON((7 4.5,8 4.5,8 5.5,7 5.5,7 4.5))')
            )::geometry(MultiPolygon) AS geometry
          )
          UPDATE overview.administrative_boundary
          SET geometry=ST_SetSRID(
            ST_Translate(ST_Scale(source.geometry,0.00005,0.00005),127.9794,49.5439),4326)
          FROM source WHERE region_code='230202'
          """).update();
        assertThat(jdbc.sql("""
                SELECT ST_IsValid(geometry) AND NOT ST_IsValid(ST_Simplify(geometry,0.0001,true))
                FROM overview.administrative_boundary WHERE region_code='230202'
                """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT ST_IsValid(ST_CollectionExtract(
                  ST_MakeValid(ST_Simplify(geometry,0.0001,true)),3))
                FROM overview.administrative_boundary WHERE region_code='230202'
                """).query(Boolean.class).single()).isTrue();
        jdbc.sql("""
          INSERT INTO overview.regional_railway_feature(source_id,name,kind,tags,geometry,source_as_of)
          VALUES('way/920001','拓扑回归线','rail','{}'::jsonb,
            ST_GeomFromText('LINESTRING(127.97935 49.54415,127.98005 49.54415)',4326),now())
          """).update();

        assertThat(railways.find("230202").lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("拓扑回归线");
            assertThat(line.mappedTrackKm().signum()).isPositive();
        });
    }

    @Test void denseBoundaryLineSummaryAvoidsRepeatedWholeBoundaryClipping() {
        jdbc.sql("DELETE FROM overview.regional_railway_feature").update();
        jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_Buffer(ST_SetSRID(ST_Point(123,48),4326),2,'quad_segs=25000')) WHERE region_code='230202'").update();
        jdbc.sql("""
          INSERT INTO overview.regional_railway_feature(source_id,name,kind,tags,geometry,source_as_of)
          SELECT 'way/'||(920000+i),'跨界线','rail','{}'::jsonb,
            ST_SetSRID(ST_MakeLine(ST_Point(120,47+i*0.002),ST_Point(126,47+i*0.002)),4326),now()
          FROM generate_series(1,500) i
          """).update();
        long started=System.nanoTime();
        var result=railways.find("230202");
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(java.time.Duration.ofMillis(750));
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("跨界线");
            assertThat(line.mappedTrackKm().signum()).isPositive();
        });
    }

    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void denseBoundaryNearbyFacilitiesUseSpatialIndexWithoutRepeatedFullScans() {
        jdbc.sql("DELETE FROM overview.regional_railway_feature").update();
        jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_Buffer(ST_SetSRID(ST_Point(123,48),4326),2,'quad_segs=25000')) WHERE region_code='230202'").update();
        jdbc.sql("""
          INSERT INTO overview.regional_railway_feature(source_id,name,kind,tags,geometry,source_as_of)
          SELECT 'node/'||(910000+i),'邻近站'||i,'station','{}'::jsonb,
            ST_SetSRID(ST_Point(125.01+(i%20)*0.002,47.95+(i/20)*0.004),4326),now()
          FROM generate_series(1,500) i
          """).update();
        long initialStarted = System.nanoTime();
        var result = railways.findFacilities("230202");
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-initialStarted)).isLessThan(java.time.Duration.ofSeconds(1));
        assertThat(result.facilities()).hasSize(5).allSatisfy(f -> {
            assertThat(f.locationRelation()).isEqualTo("NEARBY");
            assertThat(f.distanceKm().doubleValue()).isBetween(0.0,25.0);
        });
        long repeatedStarted = System.nanoTime();
        assertThat(railways.findFacilities("230202")).isEqualTo(result);
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-repeatedStarted)).isLessThan(java.time.Duration.ofMillis(100));
        // A committed edit with the same source date must invalidate the cached result.
        jdbc.sql("UPDATE overview.regional_railway_feature SET geometry=ST_SetSRID(ST_Point(123,48),4326) WHERE source_id='node/910001'").update();
        assertThat(railways.findFacilities("230202").facilities()).anySatisfy(f -> {
            assertThat(f.sourceId()).isEqualTo("node/910001");
            assertThat(f.locationRelation()).isEqualTo("WITHIN");
        });
        jdbc.sql("DELETE FROM overview.regional_railway_feature WHERE source_id='node/910001'").update();
        assertThat(railways.findFacilities("230202").facilities()).noneMatch(f -> f.sourceId().equals("node/910001"));
        jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_MakeEnvelope(120,40,120.1,40.1,4326)) WHERE region_code='230202'").update();
        assertThat(railways.findFacilities("230202").facilities()).isEmpty();
    }

    @Test void denseBoundaryFacilityReadDoesNotExhaustRequestBudget() {
        jdbc.sql("DELETE FROM overview.regional_railway_feature").update();
        jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_Buffer(ST_SetSRID(ST_Point(123,48),4326),2,'quad_segs=25000')) WHERE region_code='230202'").update();
        jdbc.sql("""
          INSERT INTO overview.regional_railway_feature(source_id,name,kind,tags,geometry,source_as_of)
          SELECT 'node/'||(900000+i),'站'||i,'station','{}'::jsonb,
            ST_SetSRID(ST_Point(122.5+(i%20)*0.05,47.5+(i/20)*0.03),4326),now()
          FROM generate_series(1,500) i
          """).update();
        long started = System.nanoTime();
        var result = railways.findFacilities("230202");
        assertThat(result.facilities()).hasSize(500).allSatisfy(f -> {
            assertThat(f.locationRelation()).isEqualTo("WITHIN");
            assertThat(f.distanceKm()).isZero();
        });
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-started))
                .isLessThan(java.time.Duration.ofSeconds(3));
    }

    @Test void usesTheSelectedBoundaryAndSeparatesNearbyFacilities() {
        jdbc.sql("DELETE FROM overview.regional_railway_feature").update();
        jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_MakeEnvelope(122,46,122.1,46.1,4326)) WHERE region_code='230202'").update();
        jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_MakeEnvelope(122.1,46,122.2,46.1,4326)) WHERE region_code='230203'").update();
        jdbc.sql("""
          INSERT INTO overview.regional_railway_feature(source_id,name,kind,tags,geometry,source_as_of) VALUES
          ('node/1','本地站','station','{}',ST_SetSRID(ST_Point(122.04,46.05),4326),'2026-09-15Z'),
          ('node/2','邻区站','station','{"disused":"yes"}',ST_SetSRID(ST_Point(122.15,46.05),4326),'2026-09-15Z'),
          ('node/3','远处站','station','{}',ST_SetSRID(ST_Point(125,46.05),4326),'2026-09-15Z'),
          ('way/4','跨界铁路','rail','{"usage":"main"}',ST_GeomFromText('LINESTRING(122 46.05,122.2 46.05)',4326),'2026-09-15Z')
          """).update();
        var left = railways.find("230202");
        assertThat(left.boundaryAvailable()).isTrue();
        assertThat(left.facilities()).filteredOn(f -> f.locationRelation().equals("WITHIN"))
                .extracting(f -> f.name()).containsExactly("本地站");
        assertThat(left.facilities()).filteredOn(f -> f.locationRelation().equals("NEARBY"))
                .extracting(f -> f.name()).containsExactly("邻区站");
        assertThat(left.facilities()).noneMatch(f -> f.name().equals("远处站"));
        assertThat(left.lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("跨界铁路");
            assertThat(line.mappedTrackKm().doubleValue()).isBetween(7.0, 9.0);
        });
        assertThat(railways.find("230203").facilities()).filteredOn(f -> f.locationRelation().equals("WITHIN"))
                .extracting(f -> f.name()).containsExactly("邻区站");
    }

    @Test void neverBorrowsParentFacilitiesWhenTheSelectedBoundaryIsMissing() {
        assertThat(railways.find("000000000001").boundaryAvailable()).isFalse();
        assertThat(railways.find("000000000001").facilities()).isEmpty();
        assertThat(railways.find("000000000001").lines()).isEmpty();
    }

    @Test void facilityOnlyReadDoesNotBuildLineSummaries() {
        jdbc.sql("DELETE FROM overview.regional_railway_feature").update();
        jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_MakeEnvelope(122,46,122.1,46.1,4326)) WHERE region_code='230202'").update();
        jdbc.sql("""
          INSERT INTO overview.regional_railway_feature(source_id,name,kind,tags,geometry,source_as_of) VALUES
          ('node/1','本地站','station','{}',ST_SetSRID(ST_Point(122.04,46.05),4326),'2026-09-15Z'),
          ('way/4','跨界铁路','rail','{"usage":"main"}',ST_GeomFromText('LINESTRING(122 46.05,122.2 46.05)',4326),'2026-09-15Z')
          """).update();

        var result = railways.findFacilities("230202");

        assertThat(result.facilities()).extracting(f -> f.name()).containsExactly("本地站");
        assertThat(result.lines()).isEmpty();
    }

    @Test void operationalRoutesReturnSourceBackedGeometryWithoutLengthAggregation() {
        jdbc.sql("DELETE FROM overview.regional_railway_feature").update();
        jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_MakeEnvelope(122,46,122.1,46.1,4326)) WHERE region_code='230202'").update();
        jdbc.sql("""
          INSERT INTO overview.regional_railway_feature(source_id,name,kind,tags,geometry,source_as_of) VALUES
          ('way/4','跨界铁路','rail','{"usage":"main","operator":"测试铁路局"}',ST_GeomFromText('LINESTRING(121.99 46.05,122.2 46.05)',4326),'2026-09-15Z')
          """).update();

        var routes = railways.findRoutes("230202");

        assertThat(routes).singleElement().satisfies(route -> {
            assertThat(route.name()).isEqualTo("跨界铁路");
            assertThat(route.geometryGeoJson()).contains("LineString").contains("122.2");
            assertThat(route.operator()).isEqualTo("测试铁路局");
            assertThat(route.sourceUrl()).startsWith("https://www.openstreetmap.org/");
        });
    }
}
