package com.cofco.qiqihar.graintrade.production.infrastructure;

import com.cofco.qiqihar.graintrade.production.application.ProductionFactCategory;
import com.cofco.qiqihar.graintrade.production.application.ProductionFactDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionListRow;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordRepository;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.production.domain.ProductionStatus;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductionRecordRepository implements ProductionRecordRepository {
    private static final String PAGE_KIND = "MONITORING";
    private final JdbcClient jdbc;

    public JdbcProductionRecordRepository(DataSource dataSource) { this.jdbc = JdbcClient.create(dataSource); }

    @Override
    public PagedResult<ProductionListRow> findPage(ProductionRecordQuery query) {
        SqlFilter filter = filter(query.productCode(), query.filters());
        long total = jdbc.sql("SELECT count(*) FROM production.production_record r " + filter.sql())
                .params(filter.parameters()).query(Long.class).single();
        long offset = Math.multiplyExact((long) query.pageNumber(), query.pageSize());
        List<ListRow> rows = jdbc.sql("""
                        SELECT r.record_id, r.product_code, r.object_type_code, object_type.name AS object_type_name,
                               r.region_code, region.name AS region_name, r.cultivar_code, cultivar.name AS cultivar_name,
                               r.survey_date, r.reported_at, r.cultivated_area_mu, r.yield_per_mu_kg,
                               r.estimated_output_kg, r.status_code, status_option.label AS status_label, r.version
                        FROM production.production_record r
                        JOIN platform.region region ON region.code = r.region_code
                        JOIN platform.object_type object_type ON object_type.code = r.object_type_code
                        LEFT JOIN platform.cultivar cultivar
                          ON cultivar.product_code = r.product_code AND cultivar.code = r.cultivar_code
                        LEFT JOIN platform.page_filter_option status_option
                          ON status_option.product_code = r.product_code
                         AND status_option.business_domain = 'PRODUCTION'
                         AND status_option.page_kind = :pageKind
                         AND status_option.filter_code = 'status'
                         AND status_option.value = r.status_code
                        """ + filter.sql() + " ORDER BY r.survey_date DESC, r.record_id LIMIT :limit OFFSET :offset")
                .params(filter.parameters()).param("pageKind", query.pageKind())
                .param("limit", query.pageSize()).param("offset", offset)
                .query((row, ignored) -> new ListRow(
                        row.getString("record_id"), row.getString("product_code"),
                        row.getString("object_type_name"), row.getString("region_name"),
                        row.getString("cultivar_name"), row.getObject("survey_date", LocalDate.class),
                        row.getObject("reported_at", OffsetDateTime.class), row.getBigDecimal("cultivated_area_mu"),
                        row.getBigDecimal("yield_per_mu_kg"), row.getBigDecimal("estimated_output_kg"),
                        ProductionStatus.valueOf(row.getString("status_code")), row.getString("status_label"),
                        row.getLong("version"))).list();
        Map<String, Map<String, BigDecimal>> facts = facts(rows.stream().map(ListRow::id).toList());
        Set<String> configuredActions = new LinkedHashSet<>(jdbc.sql("""
                        SELECT code FROM platform.page_action
                        WHERE product_code = :productCode AND business_domain = 'PRODUCTION'
                          AND page_kind = :pageKind AND action_scope = 'ROW'
                        ORDER BY sort_order
                        """).param("productCode", query.productCode()).param("pageKind", query.pageKind())
                .query(String.class).list());
        List<ProductionListRow> items = rows.stream()
                .map(row -> item(row, facts.getOrDefault(row.id(), Map.of()), configuredActions)).toList();
        return new PagedResult<>(items, query.pageNumber(), query.pageSize(), total);
    }

    @Override
    public List<ProductionFactCategory> findFactCategories() {
        return jdbc.sql("""
                        SELECT code, label, sort_order
                        FROM platform.production_fact_category
                        """).query((row, ignored) -> new ProductionFactCategory(
                        row.getString("code"), row.getString("label"), row.getInt("sort_order"))).list();
    }

    @Override
    public Optional<ProductionRecord> findById(String id) {
        return jdbc.sql("""
                        SELECT record_id, product_code, object_type_code, region_code, cultivar_code,
                               survey_date, reported_at, cultivated_area_mu, yield_per_mu_kg,
                               estimated_output_kg, status_code, return_reason, version
                        FROM production.production_record WHERE record_id = :id
                        """).param("id", id).query((row, ignored) -> {
                    Map<String, Map<String, BigDecimal>> values = categorizedFacts(id);
                    return new ProductionRecord(row.getString("record_id"), row.getString("product_code"),
                            row.getString("object_type_code"), row.getString("region_code"),
                            row.getString("cultivar_code"), row.getObject("survey_date", LocalDate.class),
                            row.getObject("reported_at", OffsetDateTime.class), row.getBigDecimal("cultivated_area_mu"),
                            row.getBigDecimal("yield_per_mu_kg"), row.getBigDecimal("estimated_output_kg"),
                            ProductionStatus.valueOf(row.getString("status_code")), row.getString("return_reason"),
                            values.getOrDefault("QUALITY", Map.of()), values.getOrDefault("COST", Map.of()),
                            values.getOrDefault("INSURANCE", Map.of()), values.getOrDefault("SUBSIDY", Map.of()),
                            row.getLong("version"));
                }).optional();
    }

    @Override
    public boolean isApplicableObjectType(String productCode, String objectTypeCode) {
        return exists("""
                SELECT EXISTS (SELECT 1 FROM platform.product_object_type applicability
                JOIN platform.object_type type ON type.code = applicability.object_type_code
                WHERE applicability.product_code = :first AND applicability.object_type_code = :second
                  AND type.business_domain = 'PRODUCTION')
                """, productCode, objectTypeCode);
    }

    @Override
    public boolean isApplicableCultivar(String productCode, String cultivarCode) {
        return exists("SELECT EXISTS (SELECT 1 FROM platform.cultivar WHERE product_code = :first AND code = :second)",
                productCode, cultivarCode);
    }

    @Override
    public boolean isKnownRegion(String regionCode) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform.region WHERE code = :code)")
                .param("code", regionCode).query(Boolean.class).single());
    }

    @Override
    public boolean areApplicableFacts(String productCode, String objectTypeCode,
            Map<String, Set<String>> factCodes) {
        for (Map.Entry<String, Set<String>> category : factCodes.entrySet()) {
            if (category.getValue().isEmpty()) continue;
            long count = jdbc.sql("""
                            SELECT count(DISTINCT definition.code)
                            FROM platform.production_fact_definition definition
                            JOIN platform.production_fact_applicability applicability
                              ON applicability.fact_code = definition.code
                            WHERE definition.category = :category
                              AND applicability.product_code = :productCode
                              AND applicability.business_domain = 'PRODUCTION'
                              AND applicability.page_kind = :pageKind
                              AND (applicability.object_type_code IS NULL
                                   OR applicability.object_type_code = :objectTypeCode)
                              AND definition.code IN (:codes)
                            """).param("category", category.getKey()).param("productCode", productCode)
                    .param("pageKind", PAGE_KIND).param("objectTypeCode", objectTypeCode)
                    .param("codes", category.getValue()).query(Long.class).single();
            if (count != category.getValue().size()) return false;
        }
        return true;
    }

    @Override
    public List<ProductionFactDefinition> findFactDefinitions(String productCode, String objectTypeCode) {
        return jdbc.sql("""
                        SELECT definition.code, definition.category, definition.label, definition.value_type,
                               definition.unit, definition.description, definition.decimal_precision,
                               definition.decimal_scale, min(applicability.sort_order) AS sort_order
                        FROM platform.production_fact_definition definition
                        JOIN platform.production_fact_applicability applicability
                          ON applicability.fact_code = definition.code
                        WHERE applicability.product_code = :productCode
                          AND applicability.business_domain = 'PRODUCTION'
                          AND applicability.page_kind = 'MONITORING'
                          AND (applicability.object_type_code IS NULL
                               OR applicability.object_type_code = :objectTypeCode)
                        GROUP BY definition.code, definition.category, definition.label, definition.value_type,
                                 definition.unit, definition.description, definition.decimal_precision,
                                 definition.decimal_scale
                        """).param("productCode", productCode)
                .param("objectTypeCode", objectTypeCode, java.sql.Types.VARCHAR)
                .query((row, ignored) -> new ProductionFactDefinition(row.getString("code"),
                        row.getString("category"), row.getString("label"), row.getString("value_type"),
                        row.getString("unit"), row.getString("description"), row.getInt("decimal_precision"),
                        row.getInt("decimal_scale"), row.getInt("sort_order"))).list();
    }

    @Override
    public ProductionRecord insert(ProductionRecord record, String actorId) {
        jdbc.sql("""
                        INSERT INTO production.production_record
                            (record_id, product_code, object_type_code, region_code, cultivar_code, survey_date,
                             reported_at, cultivated_area_mu, yield_per_mu_kg, status_code, return_reason,
                             last_modified_by, version)
                        VALUES (:id, :productCode, :objectTypeCode, :regionCode, :cultivarCode, :surveyDate,
                                :reportedAt, :area, :yield, :status, :returnReason, :actorId, 0)
                        """).params(header(record, actorId)).update();
        replaceFacts(record);
        return record;
    }

    @Override
    public ProductionRecord updateFacts(ProductionRecord record, long expectedVersion, String actorId) {
        int updated = jdbc.sql("""
                        UPDATE production.production_record SET
                            object_type_code = :objectTypeCode, region_code = :regionCode, cultivar_code = :cultivarCode,
                            survey_date = :surveyDate, reported_at = :reportedAt, cultivated_area_mu = :area,
                            yield_per_mu_kg = :yield, status_code = :status, return_reason = :returnReason,
                            last_modified_by = :actorId, updated_at = now(), version = version + 1
                        WHERE record_id = :id AND version = :expectedVersion
                        """).params(header(record, actorId)).param("expectedVersion", expectedVersion).update();
        requireUpdated(updated);
        replaceFacts(record);
        return record.savedAsVersion(expectedVersion + 1);
    }

    @Override
    public ProductionRecord updateState(ProductionRecord record, long expectedVersion, String actorId) {
        int updated = jdbc.sql("""
                        UPDATE production.production_record SET status_code = :status, return_reason = :returnReason,
                            last_modified_by = :actorId, updated_at = now(), version = version + 1
                        WHERE record_id = :id AND version = :expectedVersion
                        """).param("status", record.status().name()).param("returnReason", record.returnReason())
                .param("actorId", actorId).param("id", record.id()).param("expectedVersion", expectedVersion).update();
        requireUpdated(updated);
        return record.savedAsVersion(expectedVersion + 1);
    }

    private ProductionListRow item(ListRow row, Map<String, BigDecimal> facts, Set<String> configuredActions) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("PROD_REGION", row.regionName());
        values.put("PROD_OBJECT_TYPE", row.objectTypeName());
        values.put("PROD_SURVEY_DATE", row.surveyDate().toString());
        values.put("PROD_REPORTED_AT", row.reportedAt().toString());
        values.put("PROD_CULTIVAR", row.cultivarName());
        values.put("PROD_AREA_MU", decimal(row.area()));
        values.put("PROD_YIELD_PER_MU", decimal(row.yield()));
        values.put("PROD_ESTIMATED_OUTPUT", decimal(row.output()));
        values.put("PROD_STATUS", row.statusLabel() == null ? row.status().name() : row.statusLabel());
        facts.forEach((code, value) -> values.put(code, decimal(value)));
        return new ProductionListRow(row.id(), values, row.status(), configuredActions, row.version());
    }

    private Map<String, Map<String, BigDecimal>> categorizedFacts(String id) {
        Map<String, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT category, code, value FROM (
                            SELECT 'QUALITY' category, quality_code code, value FROM production.production_record_quality WHERE record_id = :id
                            UNION ALL SELECT 'COST', cost_code, value FROM production.production_record_cost WHERE record_id = :id
                            UNION ALL SELECT 'INSURANCE', insurance_code, value FROM production.production_record_insurance WHERE record_id = :id
                            UNION ALL SELECT 'SUBSIDY', subsidy_code, value FROM production.production_record_subsidy WHERE record_id = :id
                        ) facts
                        """).param("id", id).query((row, ignored) -> new FactRow(row.getString("category"),
                        row.getString("code"), row.getBigDecimal("value"))).list()
                .forEach(fact -> result.computeIfAbsent(fact.category(), ignored -> new LinkedHashMap<>())
                        .put(fact.code(), fact.value()));
        return result;
    }

    private Map<String, Map<String, BigDecimal>> facts(List<String> ids) {
        Map<String, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        if (ids.isEmpty()) return result;
        jdbc.sql("""
                        SELECT record_id, code, value FROM (
                            SELECT record_id, quality_code code, value FROM production.production_record_quality WHERE record_id IN (:ids)
                            UNION ALL SELECT record_id, cost_code, value FROM production.production_record_cost WHERE record_id IN (:ids)
                            UNION ALL SELECT record_id, insurance_code, value FROM production.production_record_insurance WHERE record_id IN (:ids)
                            UNION ALL SELECT record_id, subsidy_code, value FROM production.production_record_subsidy WHERE record_id IN (:ids)
                        ) facts
                        """).param("ids", ids).query((row, ignored) -> new PageFactRow(row.getString("record_id"),
                        row.getString("code"), row.getBigDecimal("value"))).list()
                .forEach(fact -> result.computeIfAbsent(fact.recordId(), ignored -> new LinkedHashMap<>())
                        .put(fact.code(), fact.value()));
        return result;
    }

    private void replaceFacts(ProductionRecord record) {
        replaceValues("production.production_record_quality", "quality_code", record.id(), record.quality());
        replaceValues("production.production_record_cost", "cost_code", record.id(), record.costs());
        replaceValues("production.production_record_insurance", "insurance_code", record.id(), record.insurance());
        replaceValues("production.production_record_subsidy", "subsidy_code", record.id(), record.subsidies());
    }

    private void replaceValues(String table, String codeColumn, String recordId, Map<String, BigDecimal> values) {
        jdbc.sql("DELETE FROM " + table + " WHERE record_id = :recordId").param("recordId", recordId).update();
        values.forEach((code, value) -> jdbc.sql("INSERT INTO " + table
                        + " (record_id, " + codeColumn + ", value) VALUES (:recordId, :code, :value)")
                .param("recordId", recordId).param("code", code).param("value", value).update());
    }

    private Map<String, Object> header(ProductionRecord record, String actorId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", record.id()); values.put("productCode", record.productCode());
        values.put("objectTypeCode", record.objectTypeCode()); values.put("regionCode", record.regionCode());
        values.put("cultivarCode", record.cultivarCode()); values.put("surveyDate", record.surveyDate());
        values.put("reportedAt", record.reportedAt()); values.put("area", record.cultivatedAreaMu());
        values.put("yield", record.yieldPerMuKilograms()); values.put("status", record.status().name());
        values.put("returnReason", record.returnReason()); values.put("actorId", actorId);
        return values;
    }

    private boolean exists(String sql, String first, String second) {
        return Boolean.TRUE.equals(jdbc.sql(sql).param("first", first).param("second", second)
                .query(Boolean.class).single());
    }

    private static void requireUpdated(int updated) {
        if (updated == 0) throw new ConflictException("PRODUCTION_RECORD_VERSION_CONFLICT", "Production record has changed");
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
                default -> throw new IllegalArgumentException("Unsupported production filter");
            }
        });
        return new SqlFilter(sql.toString(), parameters);
    }

    private static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private record SqlFilter(String sql, Map<String, Object> parameters) { }
    private record FactRow(String category, String code, BigDecimal value) { }
    private record PageFactRow(String recordId, String code, BigDecimal value) { }
    private record ListRow(String id, String productCode, String objectTypeName, String regionName,
            String cultivarName, LocalDate surveyDate, OffsetDateTime reportedAt, BigDecimal area, BigDecimal yield,
            BigDecimal output, ProductionStatus status, String statusLabel, long version) { }
}
