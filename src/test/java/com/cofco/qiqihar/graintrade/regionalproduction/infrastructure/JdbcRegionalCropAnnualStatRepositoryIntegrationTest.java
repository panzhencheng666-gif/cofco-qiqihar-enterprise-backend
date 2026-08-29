package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalCropAnnualStat;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class JdbcRegionalCropAnnualStatRepositoryIntegrationTest {
    private static final String COUNTY = "230202";
    private static final String PREFECTURE = "230200";
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

    @Autowired JdbcRegionalCropAnnualStatRepository repository;
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
    void createsFormalRowUpdatesSameKeyAndArchivesReplacedVersion() {
        RegionalCropAnnualStat created = repository.upsert(
                COUNTY, 2026, "CORN", new BigDecimal("100.0000"),
                new BigDecimal("500.0000"), 0, "regional-test", NOW).orElseThrow();

        assertThat(created.totalOutputKg()).isEqualByComparingTo("50000.0000");
        assertThat(created.version()).isZero();

        RegionalCropAnnualStat updated = repository.upsert(
                COUNTY, 2026, "CORN", new BigDecimal("120.0000"),
                new BigDecimal("550.0000"), 0, "regional-test", NOW.plusSeconds(1)).orElseThrow();

        assertThat(updated.totalOutputKg()).isEqualByComparingTo("66000.0000");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(repository.upsert(
                COUNTY, 2026, "CORN", BigDecimal.ONE, BigDecimal.ONE,
                0, "regional-test", NOW.plusSeconds(2))).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM production.regional_crop_annual_stat")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM production.regional_crop_annual_stat_history")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT planted_area_mu FROM production.regional_crop_annual_stat_history
                """).query(BigDecimal.class).single()).isEqualByComparingTo("100.0000");
    }

    @Test
    void listsOnlyAuthorizedCountiesAndKeepsMissingCountyAsEmptyRow() {
        repository.upsert(COUNTY, 2026, "CORN", new BigDecimal("100.0000"),
                new BigDecimal("500.0000"), 0, "regional-test", NOW).orElseThrow();

        var result = repository.findAll(
                2026, "CORN", PREFECTURE, Set.of(COUNTY, "230203"));

        assertThat(result).extracting(RegionalCropAnnualStat::regionCode)
                .containsExactly(COUNTY, "230203");
        assertThat(result.get(0).totalOutputKg()).isEqualByComparingTo("50000.0000");
        assertThat(result.get(1).plantedAreaMu()).isNull();
    }
}
