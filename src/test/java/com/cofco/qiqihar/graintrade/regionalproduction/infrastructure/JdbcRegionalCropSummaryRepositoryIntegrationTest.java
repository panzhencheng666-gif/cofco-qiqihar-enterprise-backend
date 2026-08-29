package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class JdbcRegionalCropSummaryRepositoryIntegrationTest {
    private static final String PREFECTURE = "230200";
    private static final String COUNTY_ONE = "230202";
    private static final String COUNTY_TWO = "230203";
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

    @Autowired JdbcRegionalCropAnnualStatRepository annualStats;
    @Autowired JdbcRegionalCropSummaryRepository summaries;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE production.regional_crop_annual_stat_history,
                         production.regional_crop_annual_stat
                """).update();
    }

    @Test
    void aggregatesPrefectureWithWeightedYieldAndComparesSummedAreasAcrossYears() {
        save(COUNTY_ONE, 2025, "CORN", "100000.0000", "500.0000");
        save(COUNTY_TWO, 2025, "CORN", "100000.0000", "1000.0000");
        save(COUNTY_ONE, 2026, "CORN", "140000.0000", "500.0000");
        save(COUNTY_TWO, 2026, "CORN", "100000.0000", "1000.0000");

        var summary = summaries.summarize(
                2026, "CORN", PREFECTURE, Set.of(COUNTY_ONE, COUNTY_TWO)).orElseThrow();

        assertThat(summary.plantedAreaMu()).isEqualByComparingTo("240000.0000");
        assertThat(summary.totalOutputKg()).isEqualByComparingTo("170000000.0000");
        assertThat(summary.yieldPerMuKg()).isEqualByComparingTo("708.3333333333333333");
        assertThat(summary.areaChangeWanMu()).isEqualByComparingTo("4.0000");
        assertThat(summary.areaChangeRatePercent()).isEqualByComparingTo("20.0000");
        assertThat(summary.comparisonAvailable()).isTrue();
        assertThat(summary.areaChangeRateAvailable()).isTrue();
    }

    @Test
    void distinguishesMissingPreviousYearAndZeroPreviousArea() {
        save(COUNTY_ONE, 2026, "RICE", "10.0000", "500.0000");
        var missing = summaries.summarize(
                2026, "RICE", COUNTY_ONE, Set.of(COUNTY_ONE)).orElseThrow();
        assertThat(missing.comparisonAvailable()).isFalse();
        assertThat(missing.comparisonMessage()).isEqualTo("缺少对比年度数据");
        assertThat(missing.areaChangeWanMu()).isNull();

        save(COUNTY_ONE, 2025, "RICE", "0.0000", "900.0000");
        var zero = summaries.summarize(
                2026, "RICE", COUNTY_ONE, Set.of(COUNTY_ONE)).orElseThrow();
        assertThat(zero.comparisonAvailable()).isTrue();
        assertThat(zero.areaChangeWanMu()).isEqualByComparingTo("0.0010");
        assertThat(zero.areaChangeRateAvailable()).isFalse();
        assertThat(zero.areaChangeRatePercent()).isNull();
        assertThat(zero.comparisonMessage()).contains("不可计算");
    }

    @Test
    void aggregatesAreaButWithholdsPrefectureYieldAndOutputUntilEveryCountyHasYield() {
        save(COUNTY_ONE, 2026, "CORN", "100000.0000", "500.0000");
        annualStats.upsert(COUNTY_TWO, 2026, "CORN", new BigDecimal("200000.0000"),
                null, 0, "summary-test", NOW).orElseThrow();

        var summary = summaries.summarize(
                2026, "CORN", PREFECTURE, Set.of(COUNTY_ONE, COUNTY_TWO)).orElseThrow();

        assertThat(summary.plantedAreaMu()).isEqualByComparingTo("300000.0000");
        assertThat(summary.yieldPerMuKg()).isNull();
        assertThat(summary.totalOutputKg()).isNull();
        assertThat(summary.currentDataAvailable()).isTrue();
    }

    @Test
    void summarySqlHasNoSampleOrBusinessRecordLineage() {
        String sql = JdbcRegionalCropSummaryRepository.SUMMARY_SQL.toLowerCase(Locale.ROOT);
        assertThat(sql).doesNotContain("sample", "market_record", "production_record", "logistics");
        assertThat(sql).contains("production.regional_crop_annual_stat");
    }

    @Test
    void representativePrefectureSummaryQueryCompletesUnderOneHundredMilliseconds() {
        save(COUNTY_ONE, 2025, "SOYBEAN", "100000.0000", "400.0000");
        save(COUNTY_TWO, 2026, "SOYBEAN", "120000.0000", "450.0000");

        var plan = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) "
                        + JdbcRegionalCropSummaryRepository.SUMMARY_SQL)
                .param("regionCode", PREFECTURE)
                .param("authorizedRegions", Set.of(COUNTY_ONE, COUNTY_TWO))
                .param("productCode", "SOYBEAN").param("year", 2026).param("previousYear", 2025)
                .query(String.class).list();
        String executionLine = plan.stream().filter(line -> line.startsWith("Execution Time:"))
                .findFirst().orElseThrow();
        var matcher = Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(executionLine);
        assertThat(matcher.find()).isTrue();
        assertThat(Double.parseDouble(matcher.group(1))).isLessThan(100.0);
    }

    private void save(String county, int year, String product, String area, String yield) {
        annualStats.upsert(county, year, product, new BigDecimal(area),
                new BigDecimal(yield), 0, "summary-test", NOW).orElseThrow();
    }
}
