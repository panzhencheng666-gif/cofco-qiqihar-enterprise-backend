package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalCropSummary;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalCropSummaryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegionalCropSummaryRepository implements RegionalCropSummaryRepository {
    static final String SUMMARY_SQL = """
            WITH target AS MATERIALIZED (
                SELECT code,name,administrative_level FROM platform.region WHERE code=:regionCode
            ), target_counties AS MATERIALIZED (
                SELECT county.code
                FROM target
                JOIN platform.region county
                  ON (target.administrative_level='COUNTY' AND county.code=target.code)
                  OR (target.administrative_level='PREFECTURE'
                      AND county.parent_code=target.code AND county.administrative_level='COUNTY')
                WHERE county.code IN (:authorizedRegions)
            ), annual AS MATERIALIZED (
                SELECT stat.data_year,COUNT(*) AS row_count,
                       SUM(stat.planted_area_mu) AS planted_area_mu,
                       CASE WHEN COUNT(stat.yield_per_mu_kg)=COUNT(*)
                            THEN SUM(stat.total_output_kg)
                       END AS total_output_kg,
                       CASE WHEN (SELECT administrative_level FROM target)='COUNTY'
                            THEN MAX(stat.yield_per_mu_kg)
                            WHEN COUNT(stat.yield_per_mu_kg)<>COUNT(*) THEN NULL::numeric
                            WHEN SUM(stat.planted_area_mu)=0 THEN 0::numeric
                            ELSE SUM(stat.total_output_kg)/SUM(stat.planted_area_mu)
                       END AS yield_per_mu_kg
                FROM production.regional_crop_annual_stat stat
                JOIN target_counties county ON county.code=stat.region_code
                WHERE stat.product_code=:productCode
                  AND stat.data_year IN (:year,:previousYear)
                GROUP BY stat.data_year
            )
            SELECT target.code AS region_code,target.name AS region_name,
                   target.administrative_level,:year AS data_year,:productCode AS product_code,
                   current_year.row_count AS current_count,
                   current_year.planted_area_mu,current_year.yield_per_mu_kg,
                   current_year.total_output_kg,previous_year.row_count AS previous_count,
                   previous_year.planted_area_mu AS previous_area_mu
            FROM target
            LEFT JOIN annual current_year ON current_year.data_year=:year
            LEFT JOIN annual previous_year ON previous_year.data_year=:previousYear
            WHERE EXISTS(SELECT 1 FROM target_counties)
            """;

    private final JdbcClient jdbc;

    public JdbcRegionalCropSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RegionalCropSummary> summarize(
            int year, String productCode, String regionCode, Set<String> authorizedRegions) {
        return jdbc.sql(SUMMARY_SQL).param("regionCode", regionCode)
                .param("authorizedRegions", authorizedRegions).param("productCode", productCode)
                .param("year", year).param("previousYear", year - 1)
                .query((rs, rowNum) -> {
                    boolean currentAvailable = rs.getObject("current_count") != null;
                    boolean previousAvailable = rs.getObject("previous_count") != null;
                    BigDecimal currentArea = rs.getBigDecimal("planted_area_mu");
                    BigDecimal previousArea = rs.getBigDecimal("previous_area_mu");
                    boolean comparisonAvailable = currentAvailable && previousAvailable;
                    BigDecimal change = comparisonAvailable
                            ? currentArea.subtract(previousArea).divide(new BigDecimal("10000"), 4, RoundingMode.HALF_UP)
                            : null;
                    boolean rateAvailable = comparisonAvailable && previousArea.signum() != 0;
                    BigDecimal rate = rateAvailable
                            ? currentArea.subtract(previousArea).multiply(new BigDecimal("100"))
                                    .divide(previousArea, 4, RoundingMode.HALF_UP)
                            : null;
                    return new RegionalCropSummary(
                            rs.getString("region_code"), rs.getString("region_name"),
                            rs.getString("administrative_level"), year, productCode,
                            currentArea, rs.getBigDecimal("yield_per_mu_kg"),
                            rs.getBigDecimal("total_output_kg"), change, rate,
                            currentAvailable, comparisonAvailable, rateAvailable,
                            message(currentAvailable, previousAvailable, rateAvailable));
                }).optional();
    }

    private static String message(
            boolean currentAvailable, boolean previousAvailable, boolean rateAvailable) {
        if (!currentAvailable) return "缺少本年度数据";
        if (!previousAvailable) return "缺少对比年度数据";
        if (!rateAvailable) return "上年播种面积为0，增减比率不可计算";
        return null;
    }
}
