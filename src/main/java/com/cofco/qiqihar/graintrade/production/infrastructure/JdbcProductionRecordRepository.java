package com.cofco.qiqihar.graintrade.production.infrastructure;

import com.cofco.qiqihar.graintrade.production.application.ProductionFactCategory;
import com.cofco.qiqihar.graintrade.production.application.ProductionFactDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionListRow;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordRepository;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.production.domain.ProductionStatus;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateGuard;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private static final String CURRENT_SAMPLE_FILTER = """
             AND r.status_code='APPROVED'
             AND r.survey_period_governance_state='CONFIRMED'
             AND r.sample_point_id IS NOT NULL
             AND EXISTS (
               SELECT 1 FROM registry.sample_point point
               WHERE point.sample_point_id=r.sample_point_id
                 AND point.approval_state='APPROVED'
                 AND point.kind_code='SURVEY_SITE'
                 AND point.location_state='VALID'
                 AND point.governed_point IS NOT NULL
                 AND point.effective_from<=CURRENT_DATE
                 AND (point.effective_to IS NULL OR point.effective_to>=CURRENT_DATE)
                 AND r.survey_date>=point.effective_from
                 AND (point.effective_to IS NULL OR r.survey_date<=point.effective_to))
            """;
    private final JdbcClient jdbc;
    private final SamplePointCoordinateGuard coordinateGuard;

    public JdbcProductionRecordRepository(
            DataSource dataSource, SamplePointCoordinateGuard coordinateGuard) {
        this.jdbc = JdbcClient.create(dataSource);
        this.coordinateGuard = coordinateGuard;
    }

    @Override
    public PagedResult<ProductionListRow> findPage(ProductionRecordQuery query) {
        return findPage(query, true);
    }

    @Override
    public PagedResult<ProductionListRow> findLifecyclePage(ProductionRecordQuery query) {
        return findPage(query, false);
    }

    private PagedResult<ProductionListRow> findPage(
            ProductionRecordQuery query, boolean currentFormalOnly) {
        SqlFilter filter = filter(query.productCode(), query.filters(), query.authorizedRegionCodes());
        String selectedRecords = currentFormalOnly ? """
                WITH current_formal_record AS (
                  SELECT r.record_id,row_number() OVER (
                    PARTITION BY r.sample_point_id
                    ORDER BY r.survey_date DESC,r.version DESC,r.record_id DESC) sample_rank
                  FROM production.production_record r
                """ + filter.sql() + CURRENT_SAMPLE_FILTER + ") " : """
                WITH current_formal_record AS (
                  SELECT r.record_id,1 sample_rank
                  FROM production.production_record r
                """ + filter.sql() + ") ";
        long total = jdbc.sql(selectedRecords
                        + "SELECT count(*) FROM current_formal_record WHERE sample_rank=1")
                .params(filter.parameters()).query(Long.class).single();
        long offset = Math.multiplyExact((long) query.pageNumber(), query.pageSize());
        List<ListRow> rows = jdbc.sql(selectedRecords + """
                        SELECT r.record_id, r.product_code, r.object_type_code, object_type.name AS object_type_name,
                               r.region_code, region.name AS region_name, r.cultivar_code, cultivar.name AS cultivar_name,
                               r.survey_date, r.reported_at, r.survey_year, r.survey_month,
                               r.survey_period_precision, r.survey_period_governance_state,
                               r.created_at, r.submitted_at, r.cultivated_area_mu, r.yield_per_mu_kg,
                               r.estimated_output_kg, r.status_code, status_option.label AS status_label, r.version
                        FROM current_formal_record current
                        JOIN production.production_record r ON r.record_id=current.record_id
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
                        WHERE current.sample_rank=1
                        ORDER BY r.survey_date DESC,r.record_id
                        LIMIT :limit OFFSET :offset
                        """)
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
                               survey_date, survey_year, survey_month, reported_at, cultivated_area_mu, yield_per_mu_kg,
                               estimated_output_kg, status_code, return_reason, version
                        FROM production.production_record WHERE record_id = :id
                        """).param("id", id).query((row, ignored) -> {
                    Map<String, Map<String, BigDecimal>> values = categorizedFacts(id);
                    return new ProductionRecord(row.getString("record_id"), row.getString("product_code"),
                            row.getString("object_type_code"), row.getString("region_code"),
                            row.getString("cultivar_code"), row.getObject("survey_date", LocalDate.class),
                            row.getInt("survey_year"), (Integer) row.getObject("survey_month"),
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
    public boolean isPointWithinRegion(
            String regionCode, BigDecimal latitude, BigDecimal longitude) {
        return containingRegionCode(regionCode, latitude, longitude).isPresent();
    }

    private Optional<String> containingRegionCode(
            String regionCode, BigDecimal latitude, BigDecimal longitude) {
        return jdbc.sql("""
                WITH RECURSIVE selected_region_scope(code,depth) AS (
                  SELECT code,0 FROM platform.region WHERE code=:regionCode
                  UNION ALL
                  SELECT child.code,parent.depth + 1
                  FROM platform.region child
                  JOIN selected_region_scope parent ON child.parent_code=parent.code
                )
                SELECT scope.code
                FROM selected_region_scope scope
                JOIN overview.administrative_boundary boundary
                  ON boundary.region_code=scope.code
                LEFT JOIN platform.monitoring_scope_region formal
                  ON formal.scope_code='FORMAL_BUSINESS'
                 AND formal.region_code=scope.code
                WHERE ST_Covers(boundary.geometry,
                  ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326))
                ORDER BY COALESCE(formal.included,false) DESC,scope.depth DESC,scope.code
                LIMIT 1
                """).param("regionCode", regionCode).param("longitude", longitude)
                .param("latitude", latitude).query(String.class).optional();
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
                             survey_year, survey_month, survey_period_precision, survey_period_governance_state,
                             reported_at, cultivated_area_mu, yield_per_mu_kg, status_code, return_reason,
                             last_modified_by, version)
                        VALUES (:id, :productCode, :objectTypeCode, :regionCode, :cultivarCode, :surveyDate,
                                :surveyYear, :surveyMonth, :surveyPrecision, 'CONFIRMED',
                                :reportedAt, :area, :yield, :status, :returnReason, :actorId, 0)
                        """).params(header(record, actorId)).update();
        replaceFacts(record);
        replaceSubmissionMetadata(record);
        return record;
    }

    @Override
    public ProductionRecord insertOfficialObservation(
            ProductionRecord record, UUID samplePointId, String actorId, Instant officialSavedAt) {
        ProductionRecord persisted = insert(record, actorId);
        int linked = jdbc.sql("""
                UPDATE production.production_record
                SET sample_point_id=:samplePointId,submitted_at=:savedAt,updated_at=:savedAt
                WHERE record_id=:recordId AND status_code='APPROVED'
                  AND survey_period_governance_state='CONFIRMED' AND sample_point_id IS NULL
                """).param("samplePointId", samplePointId)
                .param("savedAt", OffsetDateTime.ofInstant(officialSavedAt, ZoneOffset.UTC))
                .param("recordId", record.id()).update();
        requireUpdated(linked);
        return persisted;
    }

    @Override
    public ProductionRecord updateFacts(ProductionRecord record, long expectedVersion, String actorId) {
        int updated = jdbc.sql("""
                        UPDATE production.production_record SET
                            object_type_code = :objectTypeCode, region_code = :regionCode, cultivar_code = :cultivarCode,
                            survey_date = :surveyDate, survey_year = :surveyYear, survey_month = :surveyMonth,
                            survey_period_precision = :surveyPrecision,
                            survey_period_governance_state = 'CONFIRMED',
                            reported_at = :reportedAt, cultivated_area_mu = :area,
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
        String contact = record.submissionMetadata().get("PROD_SAMPLE_CONTACT");
        String latitudeValue = record.submissionMetadata().get("PROD_SAMPLE_LATITUDE");
        String longitudeValue = record.submissionMetadata().get("PROD_SAMPLE_LONGITUDE");
        if (canonicalName == null || canonicalName.isBlank()
                || latitudeValue == null || longitudeValue == null) return;
        subjectId = subjectId == null || subjectId.isBlank() ? null : subjectId.trim();
        BigDecimal latitude = new BigDecimal(latitudeValue);
        BigDecimal longitude = new BigDecimal(longitudeValue);
        String governedRegionCode = containingRegionCode(record.regionCode(), latitude, longitude)
                .orElseThrow(() -> new ConflictException(
                        "PRODUCTION_SAMPLE_POINT_OUTSIDE_REGION",
                        "样本点经纬度不在所选地区范围内，请核对后再审核"));

        ReviewedIdentityDecision reviewedIdentity = reviewedIdentity(record.id()).orElse(null);
        if (reviewedIdentity != null && "SAMPLE_IDENTITY_LINK_EXISTING".equals(reviewedIdentity.actionCode())) {
            linkReviewedSamplePoint(record, reviewedIdentity.targetSamplePointId(),
                    longitude, latitude, approvingActorId, approvedAt);
            return;
        }
        boolean confirmedDistinct = reviewedIdentity != null
                && "SAMPLE_IDENTITY_CONFIRM_DISTINCT".equals(reviewedIdentity.actionCode());
        boolean useVisibleIdentity = !confirmedDistinct
                && subjectId == null && contact != null && !contact.isBlank();
        String visibleIdentity = useVisibleIdentity
                ? "VISIBLE_SURVEY_SAMPLE|" + normalizedName(canonicalName) + "|" + normalizedContact(contact)
                : null;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:identity,0))")
                .param("identity", subjectId != null ? "PRODUCTION:" + subjectId
                        : visibleIdentity == null ? "PRODUCTION_RECORD:" + record.id() : visibleIdentity)
                .query((row, index) -> Boolean.TRUE).single();
        List<ExistingSamplePoint> existing = confirmedDistinct ? List.of() : subjectId != null
                ? findLegacySubjectSamplePoints(subjectId)
                : useVisibleIdentity
                        ? findVisibleSamplePoints(normalizedName(canonicalName), normalizedContact(contact))
                        : List.of();
        if (existing.size() > 1) {
            throw new ConflictException("PRODUCTION_SAMPLE_IDENTITY_CONFLICT",
                    "同一姓名和联系方式已关联多个样本点，请核对后再审核");
        }
        if (existing.size() == 1) {
            ExistingSamplePoint point = existing.getFirst();
            requireMatchingCoordinate(point.longitude(), point.latitude(), longitude, latitude);
            int linked = jdbc.sql("""
                    UPDATE production.production_record SET sample_point_id=:samplePointId
                    WHERE record_id=:recordId AND status_code='APPROVED' AND sample_point_id IS NULL
                    """).param("samplePointId", point.samplePointId())
                    .param("recordId", record.id()).update();
            requireUpdated(linked);
            jdbc.sql("""
                    UPDATE registry.sample_point
                    SET effective_from=least(effective_from,:effectiveFrom),updated_by=:actor,updated_at=:updatedAt
                    WHERE sample_point_id=:samplePointId
                    """).param("effectiveFrom", record.surveyDate()).param("actor", approvingActorId)
                    .param("updatedAt", OffsetDateTime.ofInstant(approvedAt, ZoneOffset.UTC))
                    .param("samplePointId", point.samplePointId()).update();
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
        boolean reviewedSharing = confirmedDistinct && reviewedIdentity.coordinateShared();
        if (reviewedSharing) {
            coordinateGuard.lockAndRequireReviewedSharing(null, longitude, latitude,
                    reviewedIdentity.reviewedOccupantIds());
            markCoordinateSharingVerified(
                    reviewedIdentity.reviewedOccupantIds(), approvingActorId, approvedTime);
        } else {
            coordinateGuard.lockAndRequireAvailable(null, longitude, latitude);
        }
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,coordinate_shared_verified,effective_from,version,
                  created_by,created_at,updated_by,updated_at)
                VALUES(:samplePointId,'SURVEY_SITE',:canonicalName,:regionCode,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326),:shared,:effectiveFrom,0,
                  :submittingActorId,:approvedAt,:approvingActorId,:approvedAt)
                """).param("samplePointId", samplePointId).param("canonicalName", canonicalName)
                .param("regionCode", governedRegionCode).param("longitude", longitude)
                .param("latitude", latitude).param("shared", reviewedSharing)
                .param("effectiveFrom", record.surveyDate())
                .param("submittingActorId", submittingActorId).param("approvedAt", approvedTime)
                .param("approvingActorId", approvingActorId).update();
        if (subjectId != null) {
            jdbc.sql("""
                    SELECT platform.register_approved_sample_subject(
                      'PRODUCTION',:recordId,:samplePointId)
                    """).param("recordId", record.id()).param("samplePointId", samplePointId)
                    .query(Long.class).single();
        }
        int linked = jdbc.sql("""
                UPDATE production.production_record SET sample_point_id=:samplePointId
                WHERE record_id=:recordId AND status_code='APPROVED' AND sample_point_id IS NULL
                """).param("samplePointId", samplePointId).param("recordId", record.id()).update();
        requireUpdated(linked);
    }

    private Optional<ReviewedIdentityDecision> reviewedIdentity(String recordId) {
        return jdbc.sql("""
                SELECT action_code,
                       nullif(detail->>'targetSamplePointId','')::uuid target_sample_point_id,
                       coalesce((detail->>'coordinateShared')::boolean,false) coordinate_shared,
                       coalesce((SELECT string_agg(item.value,',')
                         FROM jsonb_array_elements_text(coalesce(
                           detail->'coordinateSharedSamplePointIds','[]'::jsonb)) item(value)),'')
                         reviewed_occupant_ids
                FROM platform.business_audit_event
                WHERE aggregate_type='PRODUCTION_RECORD' AND aggregate_id=:recordId
                  AND action_code IN ('SAMPLE_IDENTITY_LINK_EXISTING',
                    'SAMPLE_IDENTITY_CONFIRM_DISTINCT')
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """).param("recordId", recordId)
                .query((row, ignored) -> new ReviewedIdentityDecision(
                        row.getString("action_code"),
                        row.getObject("target_sample_point_id", UUID.class),
                        row.getBoolean("coordinate_shared"),
                        uuidSet(row.getString("reviewed_occupant_ids"))))
                .optional();
    }

    private void markCoordinateSharingVerified(
            Set<UUID> samplePointIds, String actorId, OffsetDateTime updatedAt) {
        for (UUID samplePointId : samplePointIds) {
            int updated = jdbc.sql("""
                    UPDATE registry.sample_point
                    SET coordinate_shared_verified=true,version=version+1,
                        updated_by=:actor,updated_at=:updatedAt
                    WHERE sample_point_id=:samplePointId AND approval_state='APPROVED'
                      AND location_state='VALID'
                    """).param("actor", actorId).param("updatedAt", updatedAt)
                    .param("samplePointId", samplePointId).update();
            if (updated != 1) {
                throw new ConflictException("SAMPLE_POINT_COORDINATE_REVIEW_STALE",
                        "该坐标的占用情况已变化，请重新核验后再审核");
            }
        }
    }

    private static Set<UUID> uuidSet(String value) {
        if (value == null || value.isBlank()) return Set.of();
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (String item : value.split(",")) result.add(UUID.fromString(item));
        return Set.copyOf(result);
    }

    private void linkReviewedSamplePoint(
            ProductionRecord record, UUID targetSamplePointId,
            BigDecimal longitude, BigDecimal latitude,
            String approvingActorId, Instant approvedAt) {
        if (targetSamplePointId == null) {
            throw new ConflictException("PRODUCTION_SAMPLE_IDENTITY_DECISION_INVALID",
                    "身份核验结论缺少规范样本点");
        }
        ReviewedTarget target = jdbc.sql("""
                SELECT sample_point_id,region_code,
                       ST_X(governed_point) longitude,ST_Y(governed_point) latitude,effective_from
                FROM registry.sample_point
                WHERE sample_point_id=:samplePointId AND kind_code='SURVEY_SITE'
                  AND approval_state='APPROVED' AND location_state='VALID'
                  AND governed_point IS NOT NULL
                """).param("samplePointId", targetSamplePointId)
                .query((row, ignored) -> new ReviewedTarget(
                        row.getObject("sample_point_id", UUID.class), row.getString("region_code"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude"),
                        row.getObject("effective_from", LocalDate.class)))
                .optional().orElseThrow(() -> new ConflictException(
                        "PRODUCTION_SAMPLE_IDENTITY_TARGET_INVALID",
                        "身份核验选择的规范样本点已失效"));
        requireMatchingCoordinate(target.longitude(), target.latitude(), longitude, latitude);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:identity,0))")
                .param("identity", "REVIEWED_SAMPLE_IDENTITY:" + targetSamplePointId)
                .query((row, ignored) -> Boolean.TRUE).single();
        int linked = jdbc.sql("""
                UPDATE production.production_record SET sample_point_id=:samplePointId
                WHERE record_id=:recordId AND status_code='APPROVED' AND sample_point_id IS NULL
                """).param("samplePointId", targetSamplePointId)
                .param("recordId", record.id()).update();
        requireUpdated(linked);
        jdbc.sql("""
                UPDATE registry.sample_point
                SET effective_from=least(effective_from,:effectiveFrom),updated_by=:actor,updated_at=:updatedAt
                WHERE sample_point_id=:samplePointId
                """).param("effectiveFrom", record.surveyDate()).param("actor", approvingActorId)
                .param("updatedAt", OffsetDateTime.ofInstant(approvedAt, ZoneOffset.UTC))
                .param("samplePointId", targetSamplePointId).update();
    }

    private List<ExistingSamplePoint> findLegacySubjectSamplePoints(String subjectId) {
        return jdbc.sql("""
                SELECT DISTINCT point.sample_point_id,point.region_code,
                       ST_X(point.governed_point) longitude,ST_Y(point.governed_point) latitude
                FROM registry.sample_point_subject_identity identity
                JOIN registry.sample_point point ON point.sample_point_id=identity.sample_point_id
                WHERE identity.business_domain='PRODUCTION' AND identity.subject_id=:subjectId
                  AND point.kind_code='SURVEY_SITE' AND point.approval_state='APPROVED'
                  AND point.location_state='VALID'
                """).param("subjectId", subjectId)
                .query((row, index) -> new ExistingSamplePoint(
                        row.getObject("sample_point_id", UUID.class), row.getString("region_code"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude"))).list();
    }

    private List<ExistingSamplePoint> findVisibleSamplePoints(String nameKey, String contactKey) {
        return jdbc.sql("""
                WITH candidate AS (
                  SELECT record.sample_point_id
                  FROM production.production_record record
                  JOIN production.production_record_submission_metadata sample_name
                    ON sample_name.record_id=record.record_id AND sample_name.field_code='PROD_SAMPLE_NAME'
                  JOIN production.production_record_submission_metadata sample_contact
                    ON sample_contact.record_id=record.record_id AND sample_contact.field_code='PROD_SAMPLE_CONTACT'
                  WHERE record.status_code='APPROVED' AND record.sample_point_id IS NOT NULL
                    AND regexp_replace(lower(btrim(sample_name.value)),'[[:space:]]+','','g')=:nameKey
                    AND regexp_replace(lower(btrim(sample_contact.value)),'[[:space:]()（）-]+','','g')=:contactKey
                  UNION
                  SELECT record.sample_point_id
                  FROM market.market_record record
                  JOIN market.market_record_core_value sample_name
                    ON sample_name.record_id=record.record_id AND sample_name.field_code='MKT_SAMPLE_NAME'
                  JOIN market.market_record_core_value sample_contact
                    ON sample_contact.record_id=record.record_id AND sample_contact.field_code='MKT_SAMPLE_CONTACT'
                  WHERE record.status_code='APPROVED' AND record.sample_point_id IS NOT NULL
                    AND regexp_replace(lower(btrim(sample_name.value)),'[[:space:]]+','','g')=:nameKey
                    AND regexp_replace(lower(btrim(sample_contact.value)),'[[:space:]()（）-]+','','g')=:contactKey
                )
                SELECT DISTINCT point.sample_point_id,point.region_code,
                       ST_X(point.governed_point) longitude,ST_Y(point.governed_point) latitude
                FROM candidate
                JOIN registry.sample_point point ON point.sample_point_id=candidate.sample_point_id
                WHERE point.kind_code='SURVEY_SITE' AND point.approval_state='APPROVED'
                  AND point.location_state='VALID'
                """).param("nameKey", nameKey).param("contactKey", contactKey)
                .query((row, index) -> new ExistingSamplePoint(
                        row.getObject("sample_point_id", UUID.class), row.getString("region_code"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude"))).list();
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
        values.put("surveyYear", record.surveyYear()); values.put("surveyMonth", record.surveyMonth());
        values.put("surveyPrecision", record.surveyMonth() == null ? "YEAR" : "YEAR_MONTH");
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

    private static String normalizedName(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\u3000]+", "");
    }

    private static String normalizedContact(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\u3000()（）-]+", "");
    }

    private static void requireMatchingCoordinate(
            BigDecimal existingLongitude, BigDecimal existingLatitude,
            BigDecimal submittedLongitude, BigDecimal submittedLatitude) {
        if (existingLongitude == null || existingLatitude == null
                || existingLongitude.compareTo(submittedLongitude) != 0
                || existingLatitude.compareTo(submittedLatitude) != 0) {
            throw new ConflictException("SAMPLE_IDENTITY_COORDINATE_MISMATCH",
                    "同一样本身份的经纬度与已有正式样本点不一致，请按位置变更流程处理");
        }
    }

    private static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private record SqlFilter(String sql, Map<String, Object> parameters) { }
    private record FactRow(String category, String code, BigDecimal value) { }
    private record PageFactRow(String recordId, String code, BigDecimal value) { }
    private record SubmissionMetadataRow(String recordId, String code, String value) { }
    private record ExistingSamplePoint(
            UUID samplePointId, String regionCode, BigDecimal longitude, BigDecimal latitude) { }
    private record ReviewedIdentityDecision(
            String actionCode, UUID targetSamplePointId,
            boolean coordinateShared, Set<UUID> reviewedOccupantIds) { }
    private record ReviewedTarget(
            UUID samplePointId, String regionCode, BigDecimal longitude,
            BigDecimal latitude, LocalDate effectiveFrom) { }
    private record ListRow(String id, String productCode, String objectTypeName, String regionName,
            String cultivarName, LocalDate surveyDate, OffsetDateTime reportedAt, int surveyYear, Integer surveyMonth,
            String surveyPeriodPrecision, String surveyPeriodGovernanceState,
            OffsetDateTime createdAt, OffsetDateTime submittedAt, BigDecimal area, BigDecimal yield,
            BigDecimal output, ProductionStatus status, String statusLabel, long version) { }
}
