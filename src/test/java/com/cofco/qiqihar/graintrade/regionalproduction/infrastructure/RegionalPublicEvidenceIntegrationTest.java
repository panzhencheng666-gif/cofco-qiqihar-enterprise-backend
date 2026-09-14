package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalPublicDataRepository;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalPublicIndicatorParser;
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
class RegionalPublicEvidenceIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired RegionalPublicDataRepository repository;
    @Test void separatesRegionsAndRetainsNoChangeEvidenceWithNewVerificationTime() {
        jdbc.sql("DELETE FROM production.regional_public_crop_metric").update();
        jdbc.sql("""
            INSERT INTO production.regional_public_source(source_id,root_region_code,source_type,source_name,source_url,parser_key,evidence)
            VALUES('proof-qqhr','230200','AGRICULTURE','测试公开来源','https://example.org/qqhr','ANNUAL_QQHR','测试证据'),
                  ('proof-heihe','231100','AGRICULTURE','另一地区来源','https://example.org/heihe','ANNUAL_HEIHE','测试证据')
            """).update();
        Instant first=Instant.parse("2026-09-14T00:30:00Z"),second=first.plusSeconds(86400);
        repository.recordCropMetrics("proof-qqhr",List.of(new RegionalPublicDataRepository.PublicCropMetric(2025,"CORN",new BigDecimal("100"),new BigDecimal("500"),new BigDecimal("50000"),"原文")),first);
        repository.recordCropMetrics("proof-heihe",List.of(new RegionalPublicDataRepository.PublicCropMetric(2025,"CORN",new BigDecimal("900"),new BigDecimal("100"),new BigDecimal("90000"),"不同地区")),first);
        assertThat(repository.load("230200",2026).observations()).singleElement().satisfies(o -> {
            assertThat(o.plantedAreaMu()).isEqualByComparingTo("100");
            assertThat(o.yieldPerMuKg()).isEqualByComparingTo("500");
        });
        var metric=new RegionalPublicIndicatorParser.Metric("CROP_GRAIN","玉米播种面积",new BigDecimal("100"),"万亩",2025,"OBSERVED","原文100万亩");
        repository.recordIndicators("proof-qqhr",List.of(metric),first);
        repository.recordPageSuccess("proof-qqhr",first,"same-content","同一份正文");
        repository.recordIndicators("proof-qqhr",List.of(metric),second);
        repository.recordPageSuccess("proof-qqhr",second,"same-content","同一份正文");
        var context=repository.load("230200",2026);
        assertThat(context.sources().stream().filter(x -> x.id().equals("proof-qqhr")).findFirst().orElseThrow().status()).isEqualTo("SUCCESS_UNCHANGED");
        assertThat(repository.history("230200",2026).stream().filter(x -> x.sourceUrl().equals("https://example.org/qqhr")).toList()).singleElement().satisfies(i -> {
            assertThat(i.value()).isEqualByComparingTo("100");
            assertThat(i.verifiedAt()).isEqualTo(second.toString());
        });
    }
}
