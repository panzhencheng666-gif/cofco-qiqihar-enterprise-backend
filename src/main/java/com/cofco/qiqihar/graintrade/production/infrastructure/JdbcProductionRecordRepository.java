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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
        SqlFilter filter = filter(query.productCode(), query.filters(), query.authorizedRegionCodes());
        long total = jdbc.sql("SELECT count(*) FROM production.production_record r " + filter.sql())
                .params(filter.parameters()).query(Long.class).single();
        long offset = Math.multiplyExact((long) query.pageNumber(), query.pageSize());
        List<ListRow> rows = jdbc.sql("""
                        SELECT r.record_id, r.product_code, r.object_type_code, object_type.name AS object_type_name,
                               r.region_code, region.name AS region_name, r.cultivar_code, cultivar.name AS cultivar_name,
                               r.survey_date, r.reported_at, r.survey_year, r.survey_month,
                               r.survey_period_precision, r.survey_period_governance_state,
                               r.created_at, r.submitted_at, r.cultivated_area_mu, r.yield_per_mu_kg,
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
                        row.getObject("reported_at", OffsetDateTime.class), row.getInt("survey_year"),
                        (Integer) row.getObject("survey_month"), row.getString("survey_period_precision"),
                        row.getString("survey_period_governance_state"),
                        row.getObject("created_at", OffsetDateTime.class),
                        row.getObject("submitted_at", OffsetDateTime.class), row.getBigDecimal("cultivated_area_mu"),
                        row.getBigDecimal("yield_per_mu_kg"), row.getBigDecimal("estimated_output_kg"),
                        ProductionStatus.valueOf(row.getString("status_code")), row.getString("status_label"),
                        row.getLong("version"))).list();
        List<String> ids = rows.stream().map(ListRow::id).toList();
        Map<String, Map<String, BigDecimal>> facts = facts(ids);
        Map<String, Map<String, String>> submissionMetadata = submissionMetadata(ids);
        Set<String> configuredActions = new LinkedHashSet<>(jdbc.sql("""
                        SELECT code FROM platform.page_action
                        WHERE product_code = :productCode AND business_domain = 'PRODUCTION'
                          AND page_kind = :pageKind AND action_scope = 'ROW'
                        ORDER BY sort_order
                        """).param("productCode", query.productCode()).param("pageKind", query.pageKind())
                .query(String.class).list());
        List<ProductionListRow> items = rows.stream()
                .map(row -> item(row, facts.getOrDefault(row.id(), Map.of()),
                        submissionMetadata.getOrDefault(row.id(), Map.of()), configuredActions)).toList();
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
                            submissionMetadata(id), row.getLong("version"));
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
        replaceSubmissionMetadata(record);
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
        replaceSubmissionMetadata(record);
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

    private ProductionListRow item(ListRow row, Map<String, BigDecimal> facts,
            Map<String, String> submissionMetadata, Set<String> configuredActions) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("PROD_REGION", row.regionName());
        values.put("PROD_OBJECT_TYPE", row.objectTypeName());
        values.put("PROD_SURVEY_DATE", row.surveyDate().toString());
        values.put("PROD_REPORTED_AT", row.reportedAt().toString());
        values.put("PROD_SURVEY_YEAR", Integer.toString(row.surveyYear()));
        if (row.surveyMonth() != null) values.put("PROD_SURVEY_MONTH", Integer.toString(row.surveyMonth()));
        values.put("PROD_SURVEY_PERIOD_PRECISION", row.surveyPeriodPrecision());
        values.put("PROD_SURVEY_PERIOD_GOVERNANCE_STATE", row.surveyPeriodGovernanceState());
        OffsetDateTime fillingAt = row.submittedAt() == null ? row.createdAt() : row.submittedAt();
        values.put("PROD_FILLING_AT", fillingAt.toString());
        values.put("PROD_FILLING_TIME_BASIS", fillingTimeBasis(row.status(), row.submittedAt()));
        values.put("PROD_CULTIVAR", submissionMetadata.getOrDefault(
                "PROD_CULTIVAR_NAME", row.cultivarName()));
        values.put("PROD_AREA_MU", decimal(row.area()));
        values.put("PROD_YIELD_PER_MU", decimal(row.yield()));
        values.put("PROD_ESTIMATED_OUTPUT", decimal(row.output()));
        values.put("PROD_STATUS", row.statusLabel() == null ? row.status().name() : row.statusLabel());
        facts.forEach((code, value) -> values.put(code, decimal(value)));
        submissionMetadata.forEach(values::put);
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

    private Map<String, String> submissionMetadata(String recordId) {
        return jdbc.sql("""
                        SELECT field_code, value
                        FROM production.production_record_submission_metadata
                        WHERE record_id = :recordId
                        ORDER BY field_code
                        """).param("recordId", recordId)
                .query((row, ignored) -> Map.entry(row.getString("field_code"), row.getString("value")))
                .list().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (left, right) -> right, LinkedHashMap::new));
    }

    private Map<String, Map<String, String>> submissionMetadata(List<String> recordIds) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (recordIds.isEmpty()) return result;
        jdbc.sql("""
                        SELECT record_id, field_code, value
                        FROM production.production_record_submission_metadata
                        WHERE record_id IN (:recordIds)
                        ORDER BY record_id, field_code
                        """).param("recordIds", recordIds)
                .query((row, ignored) -> new SubmissionMetadataRow(
                        row.getString("record_id"), row.getString("field_code"), row.getString("value")))
                .list().forEach(value -> result.computeIfAbsent(
                        value.recordId(), ignored -> new LinkedHashMap<>()).put(value.code(), value.value()));
        return result;
    }

    private void replaceSubmissionMetadata(ProductionRecord record) {
        jdbc.sql("DELETE FROM production.production_record_submission_metadata WHERE record_id = :id")
                .param("id", record.id()).update();
        record.submissionMetadata().forEach((code, value) -> jdbc.sql("""
                        INSERT INTO production.production_record_submission_metadata(record_id, field_code, value)
                        VALUES (:id, :code, :value)
                        """).param("id", record.id()).param("code", code).param("value", value).update());
    }

    @Override
    public void linkApprovedSamplePoint(
            ProductionRecord record, String approvingActorId, Instant approvedAt) {
        String subjectId = record.submissionMetadata().get("PROD_SAMPLE_SUBJECT_CODE");
        String canonicalName = record.submissionMetadata().get("PROD_SAMPLE_NAME");
        String latitudeValue = record.submissionMetadata().get("PROD_SAMPLE_LATITUDE");
        String longitudeValue = record.submissionMetadata().get("PROD_SAMPLE_LONGITUDE");
        if (subjectId == null || subjectId.isBlank()
                || canonicalName == null || canonicalName.isBlank()
                || latitudeValue == null || longitudeValue == null) return;
        subjectId = subjectId.trim();
        BigDecimal latitude = new BigDecimal(latitudeValue);
        BigDecimal longitude = new BigDecimal(longitudeValue);
        boolean contained = jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM overview.administrative_boundary
                  WHERE region_code=:regionCode
                    AND ST_Covers(geometry,ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)))
                """).param("regionCode", record.regionCode()).param("longitude", longitude)
                .param("latitude", latitude).query(Boolean.class).single();
        if (!contained) return;

        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:identity,0))")
                .param("identity", "PRODUCTION:" + subjectId)
                .query((row, index) -> Boolean.TRUE).single();
        Optional<ExistingSamplePoint> existing = jdbc.sql("""
                SELECT point.sample_point_id,point.region_code,
                       ST_X(point.governed_point) longitude,ST_Y(point.governed_point) latitude,
                       (SELECT count(DISTINCT linked.object_type_code)=1
                                  AND min(linked.object_type_code)=:objectTypeCode
                        FROM production.production_record linked
                        WHERE linked.sample_point_id=point.sample_point_id) object_type_matches
                FROM registry.sample_point_subject_identity identity
                JOIN registry.sample_point point ON point.sample_point_id=identity.sample_point_id
                WHERE identity.business_domain='PRODUCTION' AND identity.subject_id=:subjectId
                """).param("subjectId", subjectId)
                .param("objectTypeCode", record.objectTypeCode())
                .query((row, index) -> new ExistingSamplePoint(
                        row.getObject("sample_point_id", UUID.class), row.getString("region_code"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude"),
                        row.getBoolean("object_type_matches")))
                .optional();
        if (existing.isPresent()) {
            ExistingSamplePoint point = existing.get();
            if (!point.objectTypeMatches() || !point.regionCode().equals(record.regionCode())
                    || point.longitude().compareTo(longitude) != 0
                    || point.latitude().compareTo(latitude) != 0) return;
            int linked = jdbc.sql("""
                    UPDATE production.production_record SET sample_point_id=:samplePointId
                    WHERE record_id=:recordId AND status_code='APPROVED' AND sample_point_id IS NULL
                    """).param("samplePointId", point.samplePointId())
                    .param("recordId", record.id()).update();
            requireUpdated(linked);
            return;
        }

        String submittingActorId = jdbc.sql("""
                SELECT actor_subject_id FROM platform.business_event_outbox
                WHERE aggregate_type='PRODUCTION_RECORD' AND aggregate_id=:recordId
                  AND action_code='PRODUCTION_RECORD_SUBMITTED'
                ORDER BY event_sequence DESC LIMIT 1
                """).param("recordId", record.id()).query(String.class).single();
        UUID samplePointId = UUID.randomUUID();
        OffsetDateTime approvedTime = OffsetDateTime.ofInstant(approvedAt, ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,created_at,updated_by,updated_at)
                VALUES(:samplePointId,'SURVEY_SITE',:canonicalName,:regionCode,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326),:effectiveFrom,0,
                  :submittingActorId,:approvedAt,:approvingActorId,:approvedAt)
                """).param("samplePointId", samplePointId).param("canonicalName", canonicalName)
                .param("regionCode", record.regionCode()).param("longitude", longitude)
                .param("latitude", latitude).param("effectiveFrom", record.surveyDate())
                .param("submittingActorId", submittingActorId).param("approvedAt", approvedTime)
                .param("approvingActorId", approvingActorId).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point_subject_identity(
                  business_domain,subject_id,sample_point_id,created_at,created_by)
                VALUES('PRODUCTION',:subjectId,:samplePointId,:approvedAt,:approvingActorId)
                """).param("subjectId", subjectId).param("samplePointId", samplePointId)
                .param("approvedAt", approvedTime).param("approvingActorId", approvingActorId).update();
        int linked = jdbc.sql("""
                UPDATE production.production_record SET sample_point_id=:samplePointId
                WHERE record_id=:recordId AND status_code='APPROVED' AND sample_point_id IS NULL
                """).param("samplePointId", samplePointId).param("recordId", record.id()).update();
        requireUpdated(linked);
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

    private SqlFilter filter(String productCode, Map<String, String> filters, Set<String> authorizedRegionCodes) {
        requireValidTemporalFilters(filters);
        StringBuilder sql = new StringBuilder("WHERE r.product_code = :productCode");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("productCode", productCode);
        if (!authorizedRegionCodes.contains("*")) {
            if (authorizedRegionCodes.isEmpty()) sql.append(" AND 1=0");
            else {
                sql.append(" AND r.region_code IN (:authorizedRegionCodes)");
                parameters.put("authorizedRegionCodes", authorizedRegionCodes);
            }
        }
        filters.forEach((code, value) -> {
            switch (code) {
                case "status" -> { sql.append(" AND r.status_code = :status"); parameters.put("status", value); }
                case "objectTypeCode" -> { sql.append(" AND r.object_type_code = :objectTypeCode"); parameters.put("objectTypeCode", value); }
                case "regionCode" -> { sql.append(" AND r.region_code = :regionCode"); parameters.put("regionCode", value); }
                case "surveyDate" -> { sql.append(" AND r.survey_date = :surveyDate"); parameters.put("surveyDate", LocalDate.parse(value)); }
                case "surveyYear" -> { sql.append(" AND r.survey_year = :surveyYear"); parameters.put("surveyYear", year(value)); }
                case "surveyMonth" -> { sql.append(" AND r.survey_month = :surveyMonth"); parameters.put("surveyMonth", month(value)); }
                case "fillingDateFrom" -> {
                    sql.append(" AND COALESCE(r.submitted_at,r.created_at) >= :fillingDateFrom");
                    parameters.put("fillingDateFrom", startOfDay(value));
                }
                case "fillingDateTo" -> {
                    sql.append(" AND COALESCE(r.submitted_at,r.created_at) < :fillingDateToExclusive");
                    parameters.put("fillingDateToExclusive", startOfDay(value).plusDays(1));
                }
                default -> throw new IllegalArgumentException("Unsupported production filter");
            }
        });
        return new SqlFilter(sql.toString(), parameters);
    }

    private static void requireValidTemporalFilters(Map<String, String> filters) {
        if (filters.containsKey("surveyMonth") && !filters.containsKey("surveyYear")) {
            throw new IllegalArgumentException("Survey month requires survey year");
        }
        if (filters.containsKey("fillingDateFrom") && filters.containsKey("fillingDateTo")
                && LocalDate.parse(filters.get("fillingDateFrom")).isAfter(LocalDate.parse(filters.get("fillingDateTo")))) {
            throw new IllegalArgumentException("Filling date range is reversed");
        }
    }

    private static int year(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1900 || parsed > 2200) throw new IllegalArgumentException("Invalid survey year");
        return parsed;
    }

    private static int month(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > 12) throw new IllegalArgumentException("Invalid survey month");
        return parsed;
    }

    private static OffsetDateTime startOfDay(String value) {
        return LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
    }

    private static String fillingTimeBasis(ProductionStatus status, OffsetDateTime submittedAt) {
        if (submittedAt != null) return "SUBMITTED_AT";
        return status == ProductionStatus.DRAFT ? "DRAFT_CREATED_AT" : "CREATED_AT_NO_SUBMISSION_AUDIT";
    }

    private static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private record SqlFilter(String sql, Map<String, Object> parameters) { }
    private record FactRow(String category, String code, BigDecimal value) { }
    private record PageFactRow(String recordId, String code, BigDecimal value) { }
    private record SubmissionMetadataRow(String recordId, String code, String value) { }
    private record ExistingSamplePoint(
            UUID samplePointId, String regionCode, BigDecimal longitude, BigDecimal latitude,
            boolean objectTypeMatches) { }
    private record ListRow(String id, String productCode, String objectTypeName, String regionName,
            String cultivarName, LocalDate surveyDate, OffsetDateTime reportedAt, int surveyYear, Integer surveyMonth,
            String surveyPeriodPrecision, String surveyPeriodGovernanceState,
            OffsetDateTime createdAt, OffsetDateTime submittedAt, BigDecimal area, BigDecimal yield,
            BigDecimal output, ProductionStatus status, String statusLabel, long version) { }
}
