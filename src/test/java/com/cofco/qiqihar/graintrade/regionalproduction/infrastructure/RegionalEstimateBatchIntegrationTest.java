package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.regionalproduction.application.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes=GrainTradeApplication.class,properties="qiqihar.regional-public-data.enabled=false")
@UsesProtectedTestDatabase
@Transactional
class RegionalEstimateBatchIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired RegionalPublicDataRepository publicData;
    @Autowired RegionalEstimateBatchService batches;
    @Test void dailyRecalculationPersistsAReadableSnapshotAndConfirmsUnchangedInputs() {
        jdbc.sql("DELETE FROM production.regional_public_indicator WHERE root_region_code='230200'").update();
        jdbc.sql("INSERT INTO production.regional_public_source(source_id,root_region_code,source_type,source_name,source_url,parser_key,evidence) VALUES('estimate-proof','230200','AGRICULTURE','来源','https://example.org/report','ANNUAL_QQHR','正文')").update();
        var now=Instant.parse("2026-09-14T00:30:00Z");
        for(int year=2023;year<=2025;year++) publicData.recordIndicators("estimate-proof",List.of(new RegionalPublicIndicatorParser.Metric(
                "CROP_GRAIN","稻谷产量",BigDecimal.valueOf(100+(year-2023)*10),"万吨",year,"OBSERVED","公开数据")),now);
        batches.refresh(now);
        var first=batches.load("230200",2026);
        assertThat(first.calculationStatus()).isEqualTo("RECALCULATED_CHANGED");
        assertThat(first.comparisons()).singleElement().satisfies(row -> assertThat(row.current().value()).isEqualByComparingTo("130"));
        batches.refresh(now.plusSeconds(86400));
        var second=batches.load("230200",2026);
        assertThat(second.calculationStatus()).isEqualTo("RECALCULATED_UNCHANGED");
        assertThat(second.calculatedAt()).isNotEqualTo(first.calculatedAt());
        assertThat(second.comparisons()).isEqualTo(first.comparisons());
        assertThat(jdbc.sql("SELECT count(*) FROM production.regional_estimate_batch WHERE root_region_code='230200' AND data_year=2026").query(Integer.class).single()).isEqualTo(2);
    }
}
