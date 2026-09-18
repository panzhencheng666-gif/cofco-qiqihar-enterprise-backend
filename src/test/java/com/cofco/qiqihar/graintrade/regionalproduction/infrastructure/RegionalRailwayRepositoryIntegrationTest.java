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
}
