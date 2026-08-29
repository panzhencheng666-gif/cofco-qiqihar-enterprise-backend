package com.cofco.qiqihar.graintrade.supplybalance.infrastructure;

import com.cofco.qiqihar.graintrade.supplybalance.application.SupplyBalanceCalculator.RegionalProductionSource;
import com.cofco.qiqihar.graintrade.supplybalance.application.SupplyBalanceRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcSupplyBalanceRepository implements SupplyBalanceRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSupplyBalanceRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CountySource> countySources(
            String regionCode, int surveyYear, String productCode, Set<String> authorizedRegions) {
        return jdbc.sql("""
                WITH target AS MATERIALIZED (
                    SELECT code,administrative_level FROM platform.region WHERE code=:regionCode
                )
                SELECT county.code AS region_code,county.name AS region_name,
                       county.parent_code AS prefecture_code,
                       stat.planted_area_mu,stat.yield_per_mu_kg,stat.total_output_kg,
                       balance.manual_values::text,balance.notes::text,
                       (balance.region_code IS NOT NULL) AS balance_present,
                       COALESCE(balance.version,0) AS version,balance.updated_at
                FROM target
                JOIN platform.region county
                  ON (target.administrative_level='COUNTY' AND county.code=target.code)
                  OR (target.administrative_level='PREFECTURE'
                      AND county.parent_code=target.code AND county.administrative_level='COUNTY')
                LEFT JOIN production.regional_crop_annual_stat stat
                  ON stat.region_code=county.code AND stat.data_year=:surveyYear
                 AND stat.product_code=:productCode
                LEFT JOIN production.supply_demand_balance balance
                  ON balance.region_code=county.code AND balance.survey_year=:surveyYear
                 AND balance.product_code=:productCode
                WHERE county.code IN (:authorizedRegions)
                ORDER BY county.sort_order,county.code
                """).param("regionCode", regionCode).param("surveyYear", surveyYear)
                .param("productCode", productCode).param("authorizedRegions", authorizedRegions)
                .query((rs, rowNum) -> {
                    BigDecimal area = rs.getBigDecimal("planted_area_mu");
                    RegionalProductionSource production = area == null ? null : new RegionalProductionSource(
                            area, rs.getBigDecimal("yield_per_mu_kg"), rs.getBigDecimal("total_output_kg"));
                    Timestamp updatedAt = rs.getTimestamp("updated_at");
                    return new CountySource(
                            rs.getString("region_code"), rs.getString("region_name"),
                            rs.getString("prefecture_code"), production,
                            decimals(rs.getString("manual_values")), strings(rs.getString("notes")),
                            rs.getBoolean("balance_present"), rs.getLong("version"),
                            updatedAt == null ? null : updatedAt.toInstant());
                }).list();
    }

    @Override
    public Optional<SavedBalance> upsert(
            String regionCode, int surveyYear, String productCode,
            Map<String, BigDecimal> manualValues, Map<String, String> notes,
            long expectedVersion, String actor, Instant now) {
        return jdbc.sql("""
                WITH current_row AS MATERIALIZED (
                    SELECT * FROM production.supply_demand_balance
                    WHERE region_code=:regionCode AND survey_year=:surveyYear
                      AND product_code=:productCode FOR UPDATE
                ), archived AS (
                    INSERT INTO production.supply_demand_balance_history(
                      region_code,survey_year,product_code,manual_values,notes,
                      source_version,replaced_by,replaced_at)
                    SELECT region_code,survey_year,product_code,manual_values,notes,
                           version,:actor,:now FROM current_row WHERE version=:expectedVersion
                    RETURNING history_id
                ), updated AS (
                    UPDATE production.supply_demand_balance balance
                    SET manual_values=CAST(:manualValues AS jsonb),notes=CAST(:notes AS jsonb),
                        version=balance.version+1,updated_by=:actor,updated_at=:now
                    FROM archived
                    WHERE balance.region_code=:regionCode AND balance.survey_year=:surveyYear
                      AND balance.product_code=:productCode AND balance.version=:expectedVersion
                    RETURNING balance.version,balance.updated_at
                ), inserted AS (
                    INSERT INTO production.supply_demand_balance(
                      region_code,survey_year,product_code,manual_values,notes,version,
                      created_by,created_at,updated_by,updated_at)
                    SELECT :regionCode,:surveyYear,:productCode,CAST(:manualValues AS jsonb),CAST(:notes AS jsonb),
                           0,:actor,:now,:actor,:now
                    WHERE :expectedVersion=0 AND NOT EXISTS(SELECT 1 FROM current_row)
                    ON CONFLICT DO NOTHING RETURNING version,updated_at
                )
                SELECT * FROM updated UNION ALL SELECT * FROM inserted
                """).param("regionCode", regionCode).param("surveyYear", surveyYear)
                .param("productCode", productCode).param("manualValues", json(manualValues))
                .param("notes", json(notes)).param("expectedVersion", expectedVersion)
                .param("actor", actor).param("now", Timestamp.from(now))
                .query((rs, rowNum) -> new SavedBalance(
                        rs.getLong("version"), rs.getTimestamp("updated_at").toInstant())).optional();
    }

    @Override
    public List<HistoryEntry> history(String regionCode, int surveyYear, String productCode) {
        return jdbc.sql("""
                SELECT source_version,manual_values::text,notes::text,replaced_by,replaced_at
                FROM production.supply_demand_balance_history
                WHERE region_code=:regionCode AND survey_year=:surveyYear AND product_code=:productCode
                ORDER BY replaced_at DESC,history_id DESC
                """).param("regionCode", regionCode).param("surveyYear", surveyYear)
                .param("productCode", productCode).query((rs, rowNum) -> new HistoryEntry(
                        rs.getLong("source_version"), decimals(rs.getString("manual_values")),
                        strings(rs.getString("notes")), rs.getString("replaced_by"),
                        rs.getTimestamp("replaced_at").toInstant())).list();
    }

    private Map<String, BigDecimal> decimals(String json) {
        if (json == null) return Map.of();
        try {
            Map<?, ?> raw = objectMapper.readValue(json, Map.class);
            Map<String, BigDecimal> values = new LinkedHashMap<>();
            raw.forEach((key, value) -> values.put(key.toString(), new BigDecimal(value.toString())));
            return Map.copyOf(values);
        } catch (Exception exception) {
            throw new IllegalStateException("Supply balance values cannot be read", exception);
        }
    }

    private Map<String, String> strings(String json) {
        if (json == null) return Map.of();
        try {
            Map<?, ?> raw = objectMapper.readValue(json, Map.class);
            Map<String, String> values = new LinkedHashMap<>();
            raw.forEach((key, value) -> values.put(key.toString(), value == null ? null : value.toString()));
            return Map.copyOf(values);
        } catch (Exception exception) {
            throw new IllegalStateException("Supply balance notes cannot be read", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Supply balance values cannot be serialized", exception);
        }
    }
}
