package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalCropAnnualStat;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalCropAnnualStatRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegionalCropAnnualStatRepository implements RegionalCropAnnualStatRepository {
    private final JdbcClient jdbc;

    public JdbcRegionalCropAnnualStatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RegionDescriptor> region(String regionCode) {
        return jdbc.sql("""
                SELECT code,name,parent_code,administrative_level
                FROM platform.region
                WHERE code=:regionCode
                """).param("regionCode", regionCode).query((rs, rowNum) -> new RegionDescriptor(
                        rs.getString("code"), rs.getString("name"), rs.getString("parent_code"),
                        rs.getString("administrative_level"))).optional();
    }

    @Override
    public boolean knownProduct(String productCode) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM platform.product WHERE code=:productCode)
                """).param("productCode", productCode).query(Boolean.class).single());
    }

    @Override
    public List<RegionalCropAnnualStat> findAll(
            int dataYear, String productCode, String prefectureCode, Set<String> authorizedRegions) {
        return jdbc.sql("""
                SELECT county.code AS region_code,county.name AS region_name,
                       county.parent_code AS prefecture_code,:dataYear AS data_year,
                       :productCode AS product_code,stat.planted_area_mu,stat.yield_per_mu_kg,
                       stat.total_output_kg,COALESCE(stat.version,0) AS version,stat.updated_at
                FROM platform.region county
                LEFT JOIN production.regional_crop_annual_stat stat
                  ON stat.region_code=county.code AND stat.data_year=:dataYear
                 AND stat.product_code=:productCode
                WHERE county.parent_code=:prefectureCode
                  AND county.administrative_level='COUNTY'
                  AND county.code IN (:authorizedRegions)
                ORDER BY county.sort_order,county.code
                """).param("dataYear", dataYear).param("productCode", productCode)
                .param("prefectureCode", prefectureCode).param("authorizedRegions", authorizedRegions)
                .query(this::map).list();
    }

    @Override
    public Optional<RegionalCropAnnualStat> upsert(
            String regionCode, int dataYear, String productCode,
            BigDecimal plantedAreaMu, BigDecimal yieldPerMuKg,
            long expectedVersion, String actor, Instant now) {
        return jdbc.sql("""
                WITH current_row AS MATERIALIZED (
                    SELECT * FROM production.regional_crop_annual_stat
                    WHERE region_code=:regionCode AND data_year=:dataYear
                      AND product_code=:productCode
                    FOR UPDATE
                ), archived AS (
                    INSERT INTO production.regional_crop_annual_stat_history(
                        region_code,data_year,product_code,planted_area_mu,yield_per_mu_kg,
                        total_output_kg,source_version,replaced_by,replaced_at)
                    SELECT region_code,data_year,product_code,planted_area_mu,yield_per_mu_kg,
                           total_output_kg,version,:actor,:now
                    FROM current_row WHERE version=:expectedVersion
                    RETURNING history_id
                ), updated AS (
                    UPDATE production.regional_crop_annual_stat stat
                    SET planted_area_mu=:plantedAreaMu,yield_per_mu_kg=:yieldPerMuKg,
                        version=stat.version+1,updated_by=:actor,updated_at=:now
                    FROM archived
                    WHERE stat.region_code=:regionCode AND stat.data_year=:dataYear
                      AND stat.product_code=:productCode AND stat.version=:expectedVersion
                    RETURNING stat.*
                ), inserted AS (
                    INSERT INTO production.regional_crop_annual_stat(
                        region_code,data_year,product_code,planted_area_mu,yield_per_mu_kg,
                        version,created_by,created_at,updated_by,updated_at)
                    SELECT :regionCode,:dataYear,:productCode,:plantedAreaMu,:yieldPerMuKg,
                           0,:actor,:now,:actor,:now
                    WHERE :expectedVersion=0 AND NOT EXISTS(SELECT 1 FROM current_row)
                    ON CONFLICT DO NOTHING
                    RETURNING *
                ), saved AS (
                    SELECT * FROM updated UNION ALL SELECT * FROM inserted
                )
                SELECT saved.region_code,region.name AS region_name,region.parent_code AS prefecture_code,
                       saved.data_year,saved.product_code,saved.planted_area_mu,saved.yield_per_mu_kg,
                       saved.total_output_kg,saved.version,saved.updated_at
                FROM saved JOIN platform.region region ON region.code=saved.region_code
                """).param("regionCode", regionCode).param("dataYear", dataYear)
                .param("productCode", productCode).param("plantedAreaMu", plantedAreaMu)
                .param("yieldPerMuKg", yieldPerMuKg).param("expectedVersion", expectedVersion)
                .param("actor", actor).param("now", Timestamp.from(now))
                .query(this::map).optional();
    }

    private RegionalCropAnnualStat map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new RegionalCropAnnualStat(
                rs.getString("region_code"), rs.getString("region_name"),
                rs.getString("prefecture_code"), rs.getInt("data_year"),
                rs.getString("product_code"), rs.getBigDecimal("planted_area_mu"),
                rs.getBigDecimal("yield_per_mu_kg"), rs.getBigDecimal("total_output_kg"),
                rs.getLong("version"), updatedAt == null ? null : updatedAt.toInstant());
    }
}
