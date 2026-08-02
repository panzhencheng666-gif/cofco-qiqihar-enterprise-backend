package com.cofco.qiqihar.graintrade.production.infrastructure;

import com.cofco.qiqihar.graintrade.production.application.ProductionRecordRepository;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.production.domain.ProductionStatus;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductionRecordRepository implements ProductionRecordRepository {
    private final JdbcClient jdbc;

    public JdbcProductionRecordRepository(DataSource dataSource) { this.jdbc = JdbcClient.create(dataSource); }

    @Override
    public PagedResult<ProductionRecord> findPage(ProductionRecordQuery query) {
        SqlFilter filter = filter(query.productCode(), query.filters());
        long total = jdbc.sql("SELECT count(*) FROM production.production_record r " + filter.sql())
                .params(filter.parameters()).query(Long.class).single();
        long offset = (long) query.pageNumber() * query.pageSize();
        List<ProductionRecord> items = jdbc.sql("""
                        SELECT r.record_id, r.product_code, r.object_type_code, r.region_code, r.cultivar_code,
                               r.survey_date, r.reported_at, r.cultivated_area_mu, r.yield_per_mu_kg,
                               r.estimated_output_kg, r.status_code, r.return_reason
                        FROM production.production_record r
                        """ + filter.sql() + " ORDER BY r.survey_date DESC, r.record_id LIMIT :limit OFFSET :offset")
                .params(filter.parameters()).param("limit", query.pageSize()).param("offset", offset)
                .query((row, ignored) -> record(row.getString("record_id"), row.getString("product_code"),
                        row.getString("object_type_code"), row.getString("region_code"), row.getString("cultivar_code"),
                        row.getObject("survey_date", LocalDate.class), row.getObject("reported_at", OffsetDateTime.class),
                        row.getBigDecimal("cultivated_area_mu"), row.getBigDecimal("yield_per_mu_kg"),
                        row.getBigDecimal("estimated_output_kg"), ProductionStatus.valueOf(row.getString("status_code")),
                        row.getString("return_reason")))
                .list();
        return new PagedResult<>(items, query.pageNumber(), query.pageSize(), total);
    }

    @Override
    public Optional<ProductionRecord> findById(String id) {
        return jdbc.sql("""
                        SELECT record_id, product_code, object_type_code, region_code, cultivar_code,
                               survey_date, reported_at, cultivated_area_mu, yield_per_mu_kg,
                               estimated_output_kg, status_code, return_reason
                        FROM production.production_record WHERE record_id = :id
                        """).param("id", id).query((row, ignored) -> record(row.getString("record_id"),
                row.getString("product_code"), row.getString("object_type_code"), row.getString("region_code"),
                row.getString("cultivar_code"), row.getObject("survey_date", LocalDate.class),
                row.getObject("reported_at", OffsetDateTime.class), row.getBigDecimal("cultivated_area_mu"),
                row.getBigDecimal("yield_per_mu_kg"), row.getBigDecimal("estimated_output_kg"),
                ProductionStatus.valueOf(row.getString("status_code")), row.getString("return_reason"))).optional();
    }

    @Override
    public boolean isApplicableObjectType(String productCode, String objectTypeCode) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM platform.product_object_type applicability
                            JOIN platform.object_type type ON type.code = applicability.object_type_code
                            WHERE applicability.product_code = :productCode
                              AND applicability.object_type_code = :objectTypeCode
                              AND type.business_domain = 'PRODUCTION')
                        """).param("productCode", productCode).param("objectTypeCode", objectTypeCode)
                .query(Boolean.class).single();
    }

    @Override
    public void save(ProductionRecord record, Map<String, BigDecimal> costs, Map<String, BigDecimal> insurance,
            Map<String, BigDecimal> subsidies, String actorId) {
        jdbc.sql("""
                        INSERT INTO production.production_record
                            (record_id, product_code, object_type_code, region_code, cultivar_code, survey_date,
                             reported_at, cultivated_area_mu, yield_per_mu_kg, status_code, return_reason,
                             last_modified_by)
                        VALUES (:id, :productCode, :objectTypeCode, :regionCode, :cultivarCode, :surveyDate,
                                :reportedAt, :area, :yield, :status, :returnReason, :actorId)
                        ON CONFLICT (record_id) DO UPDATE SET
                            product_code = EXCLUDED.product_code, object_type_code = EXCLUDED.object_type_code,
                            region_code = EXCLUDED.region_code, cultivar_code = EXCLUDED.cultivar_code,
                            survey_date = EXCLUDED.survey_date, reported_at = EXCLUDED.reported_at,
                            cultivated_area_mu = EXCLUDED.cultivated_area_mu, yield_per_mu_kg = EXCLUDED.yield_per_mu_kg,
                            status_code = EXCLUDED.status_code, return_reason = EXCLUDED.return_reason,
                            last_modified_by = EXCLUDED.last_modified_by, updated_at = now()
                        """).param("id", record.id()).param("productCode", record.productCode())
                .param("objectTypeCode", record.objectTypeCode()).param("regionCode", record.regionCode())
                .param("cultivarCode", record.cultivarCode()).param("surveyDate", record.surveyDate())
                .param("reportedAt", record.reportedAt()).param("area", record.cultivatedAreaMu())
                .param("yield", record.yieldPerMuKilograms()).param("status", record.status().name())
                .param("returnReason", record.returnReason()).param("actorId", actorId).update();
        replaceValues("production.production_record_quality", "quality_code", record.id(), record.quality());
        replaceValues("production.production_record_cost", "cost_code", record.id(), costs);
        replaceValues("production.production_record_insurance", "insurance_code", record.id(), insurance);
        replaceValues("production.production_record_subsidy", "subsidy_code", record.id(), subsidies);
    }

    private ProductionRecord record(String id, String product, String objectType, String region, String cultivar,
            LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal area, BigDecimal yield, BigDecimal output,
            ProductionStatus status, String returnReason) {
        Map<String, BigDecimal> quality = jdbc.sql("SELECT quality_code, value FROM production.production_record_quality WHERE record_id = :id")
                .param("id", id).query((row, ignored) -> Map.entry(row.getString("quality_code"), row.getBigDecimal("value")))
                .list().stream().collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll);
        return new ProductionRecord(id, product, objectType, region, cultivar, surveyDate, reportedAt, area, yield,
                output, status, returnReason, quality);
    }

    private void replaceValues(String table, String codeColumn, String recordId, Map<String, BigDecimal> values) {
        jdbc.sql("DELETE FROM " + table + " WHERE record_id = :recordId").param("recordId", recordId).update();
        values.forEach((code, value) -> jdbc.sql("INSERT INTO " + table + " (record_id, " + codeColumn + ", value) VALUES (:recordId, :code, :value)")
                .param("recordId", recordId).param("code", code).param("value", value).update());
    }

    private SqlFilter filter(String productCode, Map<String, String> filters) {
        StringBuilder sql = new StringBuilder("WHERE r.product_code = :productCode");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("productCode", productCode);
        filters.forEach((code, value) -> {
            switch (code) {
                case "status" -> { sql.append(" AND r.status_code = :status"); parameters.put("status", value); }
                case "objectTypeCode" -> { sql.append(" AND r.object_type_code = :objectTypeCode"); parameters.put("objectTypeCode", value); }
                case "regionCode" -> { sql.append(" AND r.region_code = :regionCode"); parameters.put("regionCode", value); }
                case "surveyDate" -> { sql.append(" AND r.survey_date = :surveyDate"); parameters.put("surveyDate", LocalDate.parse(value)); }
                default -> { }
            }
        });
        return new SqlFilter(sql.toString(), parameters);
    }

    private record SqlFilter(String sql, Map<String, Object> parameters) { }
}
