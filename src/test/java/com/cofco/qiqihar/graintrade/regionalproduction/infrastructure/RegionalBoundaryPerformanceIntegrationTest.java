package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalAgricultureBoundaryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes=GrainTradeApplication.class,properties="qiqihar.regional-public-data.enabled=false")
@UsesProtectedTestDatabase
class RegionalBoundaryPerformanceIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired RegionalAgricultureBoundaryRepository boundaries;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test void repeatedAreaReadReusesGeometryButObservesCommittedBoundaryEdits() {
        jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_Buffer(ST_SetSRID(ST_Point(123,48),4326),2,'quad_segs=100000')) WHERE region_code='230202'").update();
        var expected=boundaries.areaSquareMetres("230202").orElseThrow();
        long started=System.nanoTime();
        for (int repeat=0;repeat<5;repeat++) assertThat(boundaries.areaSquareMetres("230202")).contains(expected);
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(java.time.Duration.ofMillis(100));
        jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_MakeEnvelope(123,48,123.01,48.01,4326)) WHERE region_code='230202'").update();
        assertThat(boundaries.areaSquareMetres("230202").orElseThrow()).isLessThan(expected);
        assertThat(boundaries.areaSquareMetres("missing-region")).isEmpty();
    }

    @Test void successiveWritesInsideOneTransactionNeverReuseItsUnchangedRowVersion() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_MakeEnvelope(123,48,123.02,48.02,4326)) WHERE region_code='230202'").update();
            var first=boundaries.areaSquareMetres("230202").orElseThrow();
            jdbc.sql("UPDATE overview.administrative_boundary SET geometry=ST_Multi(ST_MakeEnvelope(123,48,123.01,48.01,4326)) WHERE region_code='230202'").update();
            assertThat(boundaries.areaSquareMetres("230202").orElseThrow()).isLessThan(first);
            status.setRollbackOnly();
        });
    }
}
