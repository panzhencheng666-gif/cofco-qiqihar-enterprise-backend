package com.cofco.qiqihar.graintrade.market.infrastructure;

import com.cofco.qiqihar.graintrade.market.application.MarketCoreFieldDefinition;
import com.cofco.qiqihar.graintrade.market.application.MarketFactCategory;
import com.cofco.qiqihar.graintrade.market.application.MarketFactDefinition;
import com.cofco.qiqihar.graintrade.market.application.MarketFieldOption;
import com.cofco.qiqihar.graintrade.market.application.MarketListRow;
import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringRepository;
import com.cofco.qiqihar.graintrade.market.domain.MarketMonitoringRecord;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.market.domain.MarketStatus;
import com.cofco.qiqihar.graintrade.market.domain.MarketTradeDirection;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateGuard;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ServerContractException;
import java.math.BigDecimal;
import java.sql.Types;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
public class JdbcMarketMonitoringRepository implements MarketMonitoringRepository {
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
                 AND r.trade_date>=point.effective_from
                 AND (point.effective_to IS NULL OR r.trade_date<=point.effective_to))
            """;
    private final JdbcClient jdbc;
    private final SamplePointCoordinateGuard coordinateGuard;

    public JdbcMarketMonitoringRepository(
            DataSource dataSource, SamplePointCoordinateGuard coordinateGuard) {
        jdbc = JdbcClient.create(dataSource);
        this.coordinateGuard = coordinateGuard;
    }

    @Override
    public PagedResult<MarketListRow> findPage(MarketRecordQuery query) {
        return findPage(query, true);
    }

    @Override
    public PagedResult<MarketListRow> findLifecyclePage(MarketRecordQuery query) {
        return findPage(query, false);
    }

    private PagedResult<MarketListRow> findPage(
            MarketRecordQuery query, boolean currentFormalOnly) {
        SqlFilter filter = filter(query.productCode(), query.filters(), query.authorizedRegionCodes());
        String selectedRecords = currentFormalOnly ? """
                WITH current_formal_record AS (
                  SELECT r.record_id,row_number() OVER (
                    PARTITION BY r.sample_point_id
                    ORDER BY r.trade_date DESC,r.version DESC,r.record_id DESC) sample_rank
                  FROM market.market_record r
                """ + filter.sql() + CURRENT_SAMPLE_FILTER + ") " : """
                WITH current_formal_record AS (
                  SELECT r.record_id,1 sample_rank
                  FROM market.market_record r
                """ + filter.sql() + ") ";
        long total = jdbc.sql(selectedRecords
                        + "SELECT count(*) FROM current_formal_record WHERE sample_rank=1")
                .params(filter.parameters()).query(Long.class).single();
        long offset = Math.multiplyExact((long) query.pageNumber(), query.pageSize());
        List<ListHeader> headers = jdbc.sql(selectedRecords + """
                        SELECT r.record_id, region.name region_name, object_type.name object_type_name,
                               r.trade_date, r.reported_at, r.survey_year, r.survey_month,
                               r.survey_period_precision, r.survey_period_governance_state,
                               r.created_at, r.submitted_at,
                               r.purchase_base_price, r.sale_base_price, r.carriage_board_amount,
                               packaging.label packaging_label, r.packaging_amount, r.freight_amount,
                               r.status_code, status.label status_label, r.version
                        FROM current_formal_record current
                        JOIN market.market_record r ON r.record_id=current.record_id
                        JOIN platform.region region ON region.code = r.region_code
                        JOIN platform.object_type object_type ON object_type.code = r.object_type_code
                        LEFT JOIN platform.market_core_field_option packaging
                          ON packaging.field_code = 'MKT_PACKAGING_FORM' AND packaging.value = r.packaging_form
                        LEFT JOIN platform.page_filter_option status
                          ON status.product_code = r.product_code AND status.business_domain = 'MARKET'
                         AND status.page_kind = 'MONITORING' AND status.filter_code = 'status'
                         AND status.value = r.status_code
                        WHERE current.sample_rank=1
                        ORDER BY r.trade_date DESC,r.record_id
                        LIMIT :limit OFFSET :offset
                        """)
                .params(filter.parameters()).param("limit", query.pageSize()).param("offset", offset)
                .query((row, ignored) -> new ListHeader(
                        row.getString("record_id"), row.getString("region_name"),
                        row.getString("object_type_name"), row.getObject("trade_date", LocalDate.class),
                        row.getObject("reported_at", OffsetDateTime.class), row.getInt("survey_year"),
                        (Integer) row.getObject("survey_month"), row.getString("survey_period_precision"),
                        row.getString("survey_period_governance_state"),
                        row.getObject("created_at", OffsetDateTime.class),
                        row.getObject("submitted_at", OffsetDateTime.class),
                        row.getBigDecimal("purchase_base_price"), row.getBigDecimal("sale_base_price"),
                        row.getBigDecimal("carriage_board_amount"), row.getString("packaging_label"),
                        row.getBigDecimal("packaging_amount"), row.getBigDecimal("freight_amount"),
                        MarketStatus.valueOf(row.getString("status_code")), row.getString("status_label"),
                        row.getLong("version"))).list();
        List<String> ids = headers.stream().map(ListHeader::id).toList();
        Map<String, Map<String, BigDecimal>> facts = facts(ids);
        Map<String, Map<String, String>> extensions = extensionCoreValues(ids);
        Set<String> configuredActions = configuredActions(query.productCode());
        List<MarketListRow> rows = headers.stream()
                .map(header -> item(
                        header,
                        facts.getOrDefault(header.id(), Map.of()),
                        extensions.getOrDefault(header.id(), Map.of()),
                        configuredActions)).toList();
        return new PagedResult<>(rows, query.pageNumber(), query.pageSize(), total);
    }

    @Override
    public Optional<MarketMonitoringRecord> findById(String id) {
        return jdbc.sql("""
                        SELECT record_id, product_code, object_type_code, region_code, trade_date, reported_at,
                               trade_direction, purchase_base_price, sale_base_price, carriage_board_amount,
                               packaging_amount, freight_amount, packaging_form, actual_trade_price,
                               status_code, return_reason, version
                        FROM market.market_record WHERE record_id = :id
                        """).param("id", id).query((row, ignored) -> new Header(
                        row.getString("record_id"), row.getString("product_code"),
                        row.getString("object_type_code"), row.getString("region_code"),
                        row.getObject("trade_date", LocalDate.class),
                        row.getObject("reported_at", OffsetDateTime.class),
                        MarketTradeDirection.valueOf(row.getString("trade_direction")),
                        row.getBigDecimal("purchase_base_price"), row.getBigDecimal("sale_base_price"),
                        row.getBigDecimal("carriage_board_amount"), row.getBigDecimal("packaging_amount"),
                        row.getBigDecimal("freight_amount"), row.getString("packaging_form"),
                        row.getBigDecimal("actual_trade_price"), MarketStatus.valueOf(row.getString("status_code")),
                        row.getString("return_reason"), row.getLong("version"))).optional()
                .map(header -> record(header, facts(List.of(id)).getOrDefault(id, Map.of())));
    }

    @Override
    public boolean isKnownRegion(String regionCode) {
        return exists("SELECT EXISTS(SELECT 1 FROM platform.region WHERE code = :value)", regionCode);
    }

    @Override
    public boolean isPointWithinRegion(
            String regionCode, BigDecimal latitude, BigDecimal longitude) {
        return containingRegionCode(regionCode, latitude, longitude).isPresent();
    }

    private Optional<String> containingRegionCode(
            String regionCode, BigDecimal latitude, BigDecimal longitude) {
        return jdbc.sql("""
                WITH RECURSIVE selected_region_scope(code, depth) AS (
                  SELECT code, 0 FROM platform.region WHERE code=:regionCode
                  UNION ALL
                  SELECT child.code, parent.depth + 1
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
                ORDER BY COALESCE(formal.included,false) DESC, scope.depth DESC, scope.code
                LIMIT 1
                """).param("regionCode", regionCode).param("longitude", longitude)
                .param("latitude", latitude).query(String.class).optional();
    }

    private boolean hasBoundaryInRegionScope(String regionCode) {
        return jdbc.sql("""
                WITH RECURSIVE selected_region_scope(code) AS (
                  SELECT code FROM platform.region WHERE code=:regionCode
                  UNION ALL
                  SELECT child.code
                  FROM platform.region child
                  JOIN selected_region_scope parent ON child.parent_code=parent.code
                )
                SELECT EXISTS(
                  SELECT 1
                  FROM selected_region_scope scope
                  JOIN overview.administrative_boundary boundary
                    ON boundary.region_code=scope.code)
                """).param("regionCode", regionCode).query(Boolean.class).single();
    }

    @Override
    public boolean isApplicableObjectType(String productCode, String objectTypeCode) {
        if (objectTypeCode == null || objectTypeCode.isBlank()) return false;
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM platform.product_object_type applicability
                            JOIN platform.object_type object_type ON object_type.code = applicability.object_type_code
                            WHERE applicability.product_code = :productCode
                              AND applicability.object_type_code = :objectTypeCode
                              AND object_type.business_domain = 'MARKET')
                        """).param("productCode", productCode).param("objectTypeCode", objectTypeCode)
                .query(Boolean.class).single());
    }

    @Override
    public boolean areApplicableFacts(String productCode, String objectTypeCode, Set<String> codes) {
        if (codes.isEmpty()) return true;
        long count = jdbc.sql("""
                        SELECT count(*) FROM platform.market_fact_applicability
                        WHERE product_code = :productCode AND object_type_code = :objectTypeCode
                          AND fact_code IN (:codes)
                        """).param("productCode", productCode).param("objectTypeCode", objectTypeCode)
                .param("codes", codes).query(Long.class).single();
        return count == codes.size();
    }

    @Override
    public List<MarketFactCategory> findFactCategories() {
        return jdbc.sql("""
                        SELECT code, label, sort_order FROM platform.market_fact_category
                        ORDER BY sort_order, code
                        """).query((row, ignored) -> new MarketFactCategory(
                        row.getString("code"), row.getString("label"), row.getInt("sort_order"))).list();
    }

    @Override
    public List<MarketFactDefinition> findFactDefinitions(String productCode, String objectTypeCode) {
        if (objectTypeCode == null) return List.of();
        return jdbc.sql("""
                        SELECT definition.code, definition.category, definition.label, definition.unit,
                               definition.decimal_precision, definition.decimal_scale, applicability.sort_order
                        FROM platform.market_fact_definition definition
                        JOIN platform.market_fact_applicability applicability
                          ON applicability.fact_code = definition.code
                        WHERE applicability.product_code = :productCode
                          AND applicability.object_type_code = :objectTypeCode
                        ORDER BY applicability.sort_order, definition.code
                        """).param("productCode", productCode).param("objectTypeCode", objectTypeCode)
                .query((row, ignored) -> new MarketFactDefinition(
                        row.getString("code"), row.getString("category"), row.getString("label"), "DECIMAL",
                        row.getString("unit"), null, row.getInt("decimal_precision"),
                        row.getInt("decimal_scale"), row.getInt("sort_order"))).list();
    }

    @Override
    public List<MarketCoreFieldDefinition> findCoreFields(String productCode, String objectTypeCode) {
        List<CoreRow> fields = jdbc.sql("""
                        WITH mounted AS (
                            SELECT page_field.field_code
                            FROM platform.page_definition page
                            JOIN platform.page_definition_field page_field
                              ON page_field.product_code = page.product_code
                             AND page_field.business_domain = page.business_domain
                             AND page_field.page_kind = page.page_kind
                            WHERE page.product_code = :productCode
                              AND page.business_domain = 'MARKET'
                              AND page.page_kind = 'MONITORING'
                        ), mapped AS (
                            SELECT applicability.field_code
                            FROM platform.market_core_field_applicability applicability
                            WHERE applicability.product_code = :productCode
                              AND applicability.business_domain = 'MARKET'
                              AND applicability.page_kind = 'MONITORING'
                        )
                        SELECT definition.code, definition.label, definition.control_type,
                               definition.unit, definition.description,
                               definition.domain_binding, definition.capability, definition.required,
                               definition.decimal_precision, definition.decimal_scale,
                               definition.sort_order,
                               mounted.field_code IS NOT NULL mounted,
                               mapped.field_code IS NOT NULL mapped
                        FROM mounted
                        FULL OUTER JOIN mapped ON mapped.field_code = mounted.field_code
                        JOIN platform.market_core_field_definition definition
                          ON definition.code = coalesce(mounted.field_code, mapped.field_code)
                        WHERE NOT EXISTS (
                            SELECT 1
                            FROM platform.market_core_field_object_exclusion exclusion
                            WHERE exclusion.product_code=:productCode
                              AND exclusion.object_type_code=:objectTypeCode
                              AND exclusion.field_code=definition.code
                        )
                        ORDER BY definition.sort_order, definition.code
                        """).param("productCode", productCode)
                .param("objectTypeCode", objectTypeCode, java.sql.Types.VARCHAR)
                .query((row, ignored) -> new CoreRow(
                        row.getString("code"), row.getString("label"), row.getString("control_type"),
                        row.getString("unit"), row.getString("description"),
                        row.getString("domain_binding"), row.getString("capability"),
                        row.getBoolean("required"),
                        (Integer) row.getObject("decimal_precision"),
                        (Integer) row.getObject("decimal_scale"), row.getInt("sort_order"),
                        row.getBoolean("mounted"), row.getBoolean("mapped"))).list();
        fields.forEach(field -> {
            boolean extension = "EXTENSION".equals(field.domainBinding());
            if (!field.mounted() || extension != field.mapped()) throw invalidDefinition();
        });
        Map<String, List<MarketFieldOption>> options = coreOptions(productCode);
        return fields.stream()
                .map(row -> new MarketCoreFieldDefinition(
                        row.code(), row.label(), row.controlType(), row.unit(), row.description(),
                        row.domainBinding(), row.capability(), row.required(),
                        row.precision(), row.scale(), row.sortOrder(),
                        options.getOrDefault(row.code(), List.of()))).toList();
    }

    @Override
    public Map<String, String> findExtensionCoreValues(String id) {
        return extensionCoreValues(List.of(id)).getOrDefault(id, Map.of());
    }

    @Override
    public MarketMonitoringRecord insert(
            MarketMonitoringRecord record, String actorId,
            Map<String, String> extensionCoreValues) {
        jdbc.sql("""
                        INSERT INTO market.market_record(
                            record_id, product_code, object_type_code, region_code, trade_date, reported_at,
                            trade_direction, purchase_base_price, sale_base_price, carriage_board_amount,
                            packaging_amount, freight_amount, packaging_form, status_code, return_reason,
                            last_modified_by, version)
                        VALUES(:id, :product, :object, :region, :date, :reported, :direction, :purchase,
                            :sale, :carriage, :packagingAmount, :freight, :packaging, :status, :reason, :actor, 0)
                        """).params(header(record, actorId)).update();
        replaceFacts(record);
        replaceExtensionCoreValues(record, extensionCoreValues);
        return record;
    }

    @Override
    public MarketMonitoringRecord insertOfficialObservation(
            MarketMonitoringRecord record, Map<String, String> extensionCoreValues,
            UUID samplePointId, String actorId, Instant officialSavedAt) {
        MarketMonitoringRecord persisted = insert(record, actorId, extensionCoreValues);
        int linked = jdbc.sql("""
                UPDATE market.market_record
                SET sample_point_id=:samplePointId,submitted_at=:savedAt,updated_at=:savedAt,
                    survey_year=extract(year from trade_date)::integer,
                    survey_month=extract(month from trade_date)::integer,
                    survey_period_precision='YEAR_MONTH',survey_period_governance_state='CONFIRMED'
                WHERE record_id=:recordId AND status_code='APPROVED' AND sample_point_id IS NULL
                """).param("samplePointId", samplePointId)
                .param("savedAt", OffsetDateTime.ofInstant(officialSavedAt, ZoneOffset.UTC))
                .param("recordId", record.id()).update();
        requireUpdated(linked);
        return persisted;
    }

    @Override
    public MarketMonitoringRecord updateFacts(
            MarketMonitoringRecord record, long expectedVersion, String actorId,
            Map<String, String> extensionCoreValues) {
        int updated = jdbc.sql("""
                        UPDATE market.market_record SET
                            object_type_code = :object, region_code = :region, trade_date = :date,
                            reported_at = :reported, trade_direction = :direction,
                            purchase_base_price = :purchase, sale_base_price = :sale,
                            carriage_board_amount = :carriage, packaging_amount = :packagingAmount,
                            freight_amount = :freight, packaging_form = :packaging,
                            status_code = :status, return_reason = :reason, last_modified_by = :actor,
                            updated_at = :reported, version = version + 1
                        WHERE record_id = :id AND version = :expectedVersion
                        """).params(header(record, actorId)).param("expectedVersion", expectedVersion).update();
        requireUpdated(updated);
        replaceFacts(record);
        replaceExtensionCoreValues(record, extensionCoreValues);
        return record.savedAsVersion(expectedVersion + 1);
    }

    @Override
    public MarketMonitoringRecord updateState(
            MarketMonitoringRecord record, long expectedVersion, String actorId, Instant updatedAt) {
        int updated = jdbc.sql("""
                        UPDATE market.market_record SET status_code = :status, return_reason = :reason,
                            last_modified_by = :actor, updated_at = :updatedAt, version = version + 1
                        WHERE record_id = :id AND version = :expectedVersion
                        """).param("status", record.status().name()).param("reason", record.returnReason())
                .param("actor", actorId)
                .param("updatedAt", OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC))
                .param("id", record.id()).param("expectedVersion", expectedVersion).update();
        requireUpdated(updated);
        return record.savedAsVersion(expectedVersion + 1);
    }

    @Override
    public void linkApprovedSamplePoint(MarketMonitoringRecord record,
            Map<String, String> extensionCoreValues, String approvingActorId, Instant approvedAt) {
        ReviewedIdentityDecision reviewedIdentity = reviewedIdentity(record.id()).orElse(null);
        if (reviewedIdentity != null && "SAMPLE_IDENTITY_LINK_EXISTING".equals(reviewedIdentity.actionCode())) {
            linkReviewedSamplePoint(record, extensionCoreValues,
                    reviewedIdentity.targetSamplePointId(), approvingActorId, approvedAt);
            return;
        }
        boolean confirmedDistinct = reviewedIdentity != null
                && "SAMPLE_IDENTITY_CONFIRM_DISTINCT".equals(reviewedIdentity.actionCode());
        List<ResolvedSamplePoint> resolved = confirmedDistinct ? List.of() : jdbc.sql("""
                        SELECT point.sample_point_id,point.owner_party_id,resolution.actor,
                               ST_X(point.governed_point) longitude,
                               ST_Y(point.governed_point) latitude
                        FROM registry.current_sample_subject_resolution resolution
                        JOIN registry.sample_point point
                          ON point.sample_point_id=resolution.target_sample_point_id
                        WHERE resolution.source_domain='MARKET'
                          AND resolution.source_record_id=:recordId
                          AND resolution.resolution_action='LINK'
                          AND resolution.source_version=:sourceVersion
                          AND point.owner_party_id IS NOT NULL
                          AND point.approval_state='APPROVED'
                          AND point.location_state='VALID'
                        """).param("recordId", record.id())
                .param("sourceVersion", Math.max(0, record.version() - 1))
                .query((row, ignored) -> new ResolvedSamplePoint(
                        row.getObject("sample_point_id", UUID.class),
                        row.getObject("owner_party_id", UUID.class), row.getString("actor"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude")))
                .list();
        if (resolved.size() > 1) {
            throw new ConflictException("MARKET_SAMPLE_POINT_RESOLUTION_CONFLICT",
                    "该市场记录存在多个样本点关联，请核对后再审核");
        }
        if (resolved.size() == 1) {
            ResolvedSamplePoint point = resolved.getFirst();
            requireMatchingCoordinate(point.longitude(), point.latitude(),
                    new BigDecimal(extensionCoreValues.get("MKT_SAMPLE_LONGITUDE")),
                    new BigDecimal(extensionCoreValues.get("MKT_SAMPLE_LATITUDE")));
            int linked = jdbc.sql("""
                            UPDATE market.market_record
                            SET party_id=:partyId,sample_point_id=:samplePointId
                            WHERE record_id=:recordId AND status_code='APPROVED'
                              AND ((party_id IS NULL AND sample_point_id IS NULL)
                                OR (party_id=:partyId AND sample_point_id=:samplePointId))
                            """).param("partyId", point.partyId())
                    .param("samplePointId", point.samplePointId())
                    .param("recordId", record.id()).update();
            requireUpdated(linked);
            return;
        }
        linkVisibleSamplePoint(record, extensionCoreValues,
                approvingActorId, approvedAt, reviewedIdentity);
    }

    private void linkVisibleSamplePoint(MarketMonitoringRecord record,
            Map<String, String> extensionCoreValues, String approvingActorId,
            Instant approvedAt, ReviewedIdentityDecision reviewedIdentity) {
        boolean confirmedDistinct = reviewedIdentity != null
                && "SAMPLE_IDENTITY_CONFIRM_DISTINCT".equals(reviewedIdentity.actionCode());
        String canonicalName = extensionCoreValues.get("MKT_SAMPLE_NAME");
        String contact = extensionCoreValues.get("MKT_SAMPLE_CONTACT");
        String latitudeValue = extensionCoreValues.get("MKT_SAMPLE_LATITUDE");
        String longitudeValue = extensionCoreValues.get("MKT_SAMPLE_LONGITUDE");
        if (canonicalName == null || canonicalName.isBlank() || contact == null || contact.isBlank()
                || latitudeValue == null || latitudeValue.isBlank()
                || longitudeValue == null || longitudeValue.isBlank()) return;
        BigDecimal latitude = new BigDecimal(latitudeValue);
        BigDecimal longitude = new BigDecimal(longitudeValue);
        String governedRegionCode = containingRegionCode(record.regionCode(), latitude, longitude)
                .orElseThrow(() -> new ConflictException(
                        "MARKET_SAMPLE_POINT_OUTSIDE_REGION",
                        "样本点经纬度不在所选地区范围内，请核对后再审核"));

        String nameKey = normalizedName(canonicalName);
        String contactKey = normalizedContact(contact);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:identity,0))")
                .param("identity", "VISIBLE_MARKET_SAMPLE|" + nameKey + "|" + contactKey)
                .query((row, index) -> Boolean.TRUE).single();
        List<ExistingSamplePoint> existing = confirmedDistinct ? List.of() : jdbc.sql("""
                SELECT DISTINCT point.sample_point_id,
                       ST_X(point.governed_point) longitude,
                       ST_Y(point.governed_point) latitude
                FROM market.market_record previous
                JOIN market.market_record_core_value sample_name
                  ON sample_name.record_id=previous.record_id AND sample_name.field_code='MKT_SAMPLE_NAME'
                JOIN market.market_record_core_value sample_contact
                  ON sample_contact.record_id=previous.record_id AND sample_contact.field_code='MKT_SAMPLE_CONTACT'
                JOIN registry.sample_point point ON point.sample_point_id=previous.sample_point_id
                WHERE previous.status_code='APPROVED' AND previous.sample_point_id IS NOT NULL
                  AND regexp_replace(lower(btrim(sample_name.value)),'[[:space:]]+','','g')=:nameKey
                  AND regexp_replace(lower(btrim(sample_contact.value)),'[[:space:]()（）-]+','','g')=:contactKey
                  AND point.kind_code='SURVEY_SITE' AND point.approval_state='APPROVED'
                  AND point.location_state='VALID'
                """).param("nameKey", nameKey).param("contactKey", contactKey)
                .query((row, index) -> new ExistingSamplePoint(
                        row.getObject("sample_point_id", UUID.class),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude"))).list();
        if (existing.size() > 1) {
            throw new ConflictException("MARKET_SAMPLE_IDENTITY_CONFLICT",
                    "同一姓名和联系方式已关联多个样本点，请核对后再审核");
        }

        UUID samplePointId;
        if (existing.size() == 1) {
            ExistingSamplePoint point = existing.getFirst();
            requireMatchingCoordinate(point.longitude(), point.latitude(), longitude, latitude);
            samplePointId = point.samplePointId();
            jdbc.sql("""
                    UPDATE registry.sample_point
                    SET effective_from=least(effective_from,:effectiveFrom),updated_by=:actor,updated_at=:updatedAt
                    WHERE sample_point_id=:samplePointId
                    """).param("effectiveFrom", record.tradeDate()).param("actor", approvingActorId)
                    .param("updatedAt", OffsetDateTime.ofInstant(approvedAt, ZoneOffset.UTC))
                    .param("samplePointId", samplePointId).update();
        } else {
            String submittingActorId = jdbc.sql("""
                    SELECT actor_subject_id FROM platform.business_event_outbox
                    WHERE aggregate_type='MARKET_RECORD' AND aggregate_id=:recordId
                      AND action_code='MARKET_RECORD_SUBMITTED'
                    ORDER BY event_sequence DESC LIMIT 1
                    """).param("recordId", record.id()).query(String.class).single();
            samplePointId = UUID.randomUUID();
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
                    """).param("samplePointId", samplePointId).param("canonicalName", canonicalName.strip())
                    .param("regionCode", governedRegionCode).param("longitude", longitude)
                    .param("latitude", latitude).param("shared", reviewedSharing)
                    .param("effectiveFrom", record.tradeDate())
                    .param("submittingActorId", submittingActorId).param("approvedAt", approvedTime)
                    .param("approvingActorId", approvingActorId).update();
        }
        int linked = jdbc.sql("""
                UPDATE market.market_record SET sample_point_id=:samplePointId
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
                WHERE aggregate_type='MARKET_RECORD' AND aggregate_id=:recordId
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
            MarketMonitoringRecord record, Map<String, String> extensionCoreValues,
            UUID targetSamplePointId, String approvingActorId, Instant approvedAt) {
        if (targetSamplePointId == null) {
            throw new ConflictException("MARKET_SAMPLE_IDENTITY_DECISION_INVALID",
                    "身份核验结论缺少规范样本点");
        }
        ReviewedTarget target = jdbc.sql("""
                SELECT sample_point_id,owner_party_id,region_code,
                       ST_X(governed_point) longitude,ST_Y(governed_point) latitude,effective_from
                FROM registry.sample_point
                WHERE sample_point_id=:samplePointId AND kind_code='SURVEY_SITE'
                  AND approval_state='APPROVED' AND location_state='VALID'
                  AND governed_point IS NOT NULL
                """).param("samplePointId", targetSamplePointId)
                .query((row, ignored) -> new ReviewedTarget(
                        row.getObject("sample_point_id", UUID.class),
                        row.getObject("owner_party_id", UUID.class), row.getString("region_code"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude"),
                        row.getObject("effective_from", LocalDate.class)))
                .optional().orElseThrow(() -> new ConflictException(
                        "MARKET_SAMPLE_IDENTITY_TARGET_INVALID",
                        "身份核验选择的规范样本点已失效"));
        requireMatchingCoordinate(
                target.longitude(), target.latitude(),
                new BigDecimal(extensionCoreValues.get("MKT_SAMPLE_LONGITUDE")),
                new BigDecimal(extensionCoreValues.get("MKT_SAMPLE_LATITUDE")));
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:identity,0))")
                .param("identity", "REVIEWED_SAMPLE_IDENTITY:" + targetSamplePointId)
                .query((row, ignored) -> Boolean.TRUE).single();
        int linked = jdbc.sql("""
                UPDATE market.market_record
                SET party_id=:partyId,sample_point_id=:samplePointId
                WHERE record_id=:recordId AND status_code='APPROVED'
                  AND party_id IS NULL AND sample_point_id IS NULL
                """).param("partyId", target.ownerPartyId(), Types.OTHER)
                .param("samplePointId", targetSamplePointId)
                .param("recordId", record.id()).update();
        requireUpdated(linked);
        jdbc.sql("""
                UPDATE registry.sample_point
                SET effective_from=least(effective_from,:effectiveFrom),updated_by=:actor,updated_at=:updatedAt
                WHERE sample_point_id=:samplePointId
                """).param("effectiveFrom", record.tradeDate()).param("actor", approvingActorId)
                .param("updatedAt", OffsetDateTime.ofInstant(approvedAt, ZoneOffset.UTC))
                .param("samplePointId", targetSamplePointId).update();
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

    private record ResolvedSamplePoint(
            UUID samplePointId, UUID partyId, String resolutionActor,
            BigDecimal longitude, BigDecimal latitude) {}
    private record ExistingSamplePoint(
            UUID samplePointId, BigDecimal longitude, BigDecimal latitude) {}
    private record ReviewedIdentityDecision(
            String actionCode, UUID targetSamplePointId,
            boolean coordinateShared, Set<UUID> reviewedOccupantIds) {}
    private record ReviewedTarget(
            UUID samplePointId, UUID ownerPartyId, String regionCode,
            BigDecimal longitude, BigDecimal latitude, LocalDate effectiveFrom) {}

    private Map<String, List<MarketFieldOption>> coreOptions(String productCode) {
        Map<String, List<MarketFieldOption>> options = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT field_code, value, label, sort_order FROM (
                            SELECT option.field_code, option.value, option.label, option.sort_order
                            FROM platform.market_core_field_option option
                            UNION ALL
                            SELECT 'MKT_OBJECT_TYPE', object_type.code, object_type.name, object_type.sort_order
                            FROM platform.product_object_type applicability
                            JOIN platform.object_type object_type
                              ON object_type.code = applicability.object_type_code
                            WHERE applicability.product_code = :productCode
                              AND object_type.business_domain = 'MARKET'
                        ) available_options
                        ORDER BY field_code, sort_order, value
                        """).param("productCode", productCode).query((row, ignored) -> new OptionRow(
                        row.getString("field_code"), row.getString("value"),
                        row.getString("label"), row.getInt("sort_order"))).list()
                .forEach(option -> options.computeIfAbsent(
                        option.fieldCode(), ignored -> new java.util.ArrayList<>()).add(
                                new MarketFieldOption(option.value(), option.label(), option.sortOrder())));
        return options;
    }

    private Set<String> configuredActions(String productCode) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT code FROM platform.page_action
                        WHERE product_code = :productCode AND business_domain = 'MARKET'
                          AND page_kind = 'MONITORING' AND action_scope = 'ROW'
                        ORDER BY sort_order
                        """).param("productCode", productCode).query(String.class).list());
    }

    private MarketListRow item(
            ListHeader row, Map<String, BigDecimal> facts, Map<String, String> extensions,
            Set<String> configuredActions) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MKT_REGION", row.regionName());
        values.put("MKT_OBJECT_TYPE", row.objectTypeName());
        values.put("MKT_TRADE_DATE", row.tradeDate().toString());
        values.put("MKT_REPORTED_AT", row.reportedAt().toString());
        values.put("MKT_SURVEY_YEAR", Integer.toString(row.surveyYear()));
        if (row.surveyMonth() != null) values.put("MKT_SURVEY_MONTH", Integer.toString(row.surveyMonth()));
        values.put("MKT_SURVEY_PERIOD_PRECISION", row.surveyPeriodPrecision());
        values.put("MKT_SURVEY_PERIOD_GOVERNANCE_STATE", row.surveyPeriodGovernanceState());
        OffsetDateTime fillingAt = row.submittedAt() == null ? row.createdAt() : row.submittedAt();
        values.put("MKT_FILLING_AT", fillingAt.toString());
        values.put("MKT_FILLING_TIME_BASIS", fillingTimeBasis(row.status(), row.submittedAt()));
        if (row.purchaseBasePrice() != null) values.put("MKT_PURCHASE_BASE_PRICE", decimal(row.purchaseBasePrice()));
        if (row.saleBasePrice() != null) values.put("MKT_SALE_BASE_PRICE", decimal(row.saleBasePrice()));
        if (row.carriageBoardAmount() != null) values.put("MKT_CARRIAGE_BOARD_AMOUNT", decimal(row.carriageBoardAmount()));
        if (row.packagingLabel() != null) values.put("MKT_PACKAGING_FORM", row.packagingLabel());
        if (row.packagingAmount() != null) values.put("MKT_PACKAGING_AMOUNT", decimal(row.packagingAmount()));
        if (row.freightAmount() != null) values.put("MKT_FREIGHT_AMOUNT", decimal(row.freightAmount()));
        values.put("MKT_STATUS", row.statusLabel() == null ? row.status().name() : row.statusLabel());
        extensions.forEach((code, value) -> putDistinct(values, code, value));
        facts.forEach((code, value) -> putDistinct(values, code, decimal(value)));
        return new MarketListRow(row.id(), values, row.status(), configuredActions, row.version());
    }

    private Map<String, Map<String, BigDecimal>> facts(List<String> ids) {
        Map<String, Map<String, BigDecimal>> values = new LinkedHashMap<>();
        if (ids.isEmpty()) return values;
        jdbc.sql("""
                        SELECT fact.record_id, fact.fact_code, fact.value,
                               applicability.fact_code IS NOT NULL applicable
                        FROM market.market_record_fact fact
                        JOIN market.market_record record ON record.record_id = fact.record_id
                        LEFT JOIN platform.market_fact_applicability applicability
                          ON applicability.product_code = record.product_code
                         AND applicability.object_type_code = record.object_type_code
                         AND applicability.fact_code = fact.fact_code
                        WHERE fact.record_id IN (:ids)
                        ORDER BY fact.record_id, fact.fact_code
                        """).param("ids", ids).query((row, ignored) -> new FactRow(
                        row.getString("record_id"), row.getString("fact_code"), row.getBigDecimal("value"),
                        row.getBoolean("applicable")))
                .list().forEach(fact -> {
                    if (!fact.applicable()) throw invalidData();
                    values.computeIfAbsent(fact.recordId(), ignored -> new LinkedHashMap<>())
                            .put(fact.code(), fact.value());
                });
        return values;
    }

    private Map<String, Map<String, String>> extensionCoreValues(List<String> ids) {
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        if (ids.isEmpty()) return values;
        jdbc.sql("""
                        SELECT value.record_id, value.field_code, value.value,
                               value.product_code = record.product_code
                                 AND value.domain_binding = 'EXTENSION'
                                 AND definition.code IS NOT NULL
                                 AND page_field.field_code IS NOT NULL
                                 AND applicability.field_code IS NOT NULL applicable
                        FROM market.market_record_core_value value
                        JOIN market.market_record record
                          ON record.record_id = value.record_id
                        LEFT JOIN platform.market_core_field_definition definition
                          ON definition.code = value.field_code
                         AND definition.domain_binding = 'EXTENSION'
                        LEFT JOIN platform.page_definition_field page_field
                          ON page_field.product_code = record.product_code
                         AND page_field.business_domain = 'MARKET'
                         AND page_field.page_kind = 'MONITORING'
                         AND page_field.field_code = value.field_code
                        LEFT JOIN platform.market_core_field_applicability applicability
                          ON applicability.product_code = record.product_code
                         AND applicability.business_domain = 'MARKET'
                         AND applicability.page_kind = 'MONITORING'
                         AND applicability.field_code = value.field_code
                         AND applicability.domain_binding = 'EXTENSION'
                        WHERE value.record_id IN (:ids)
                        ORDER BY value.record_id, value.field_code
                        """).param("ids", ids).query((row, ignored) -> new ExtensionRow(
                        row.getString("record_id"), row.getString("field_code"), row.getString("value"),
                        row.getBoolean("applicable")))
                .list().forEach(value -> {
                    if (!value.applicable()) throw invalidData();
                    values.computeIfAbsent(value.recordId(), ignored -> new LinkedHashMap<>())
                            .put(value.code(), value.value());
                });
        return values;
    }

    private void replaceFacts(MarketMonitoringRecord record) {
        jdbc.sql("DELETE FROM market.market_record_fact WHERE record_id = :id")
                .param("id", record.id()).update();
        record.facts().forEach((code, value) -> jdbc.sql("""
                        INSERT INTO market.market_record_fact(
                            record_id, fact_code, value, product_code, object_type_code)
                        VALUES(:id, :code, :value, :productCode, :objectTypeCode)
                        """).param("id", record.id()).param("code", code).param("value", value)
                .param("productCode", record.productCode())
                .param("objectTypeCode", record.objectTypeCode()).update());
    }

    private void replaceExtensionCoreValues(
            MarketMonitoringRecord record, Map<String, String> values) {
        jdbc.sql("DELETE FROM market.market_record_core_value WHERE record_id = :id")
                .param("id", record.id()).update();
        values.forEach((code, value) -> jdbc.sql("""
                        INSERT INTO market.market_record_core_value(
                            record_id, product_code, field_code, domain_binding, value)
                        VALUES(:id, :productCode, :code, 'EXTENSION', :value)
                        """).param("id", record.id()).param("productCode", record.productCode())
                .param("code", code).param("value", value).update());
    }

    private static void putDistinct(Map<String, String> values, String code, String value) {
        if (values.containsKey(code)) throw invalidData();
        values.put(code, value);
    }

    private static ServerContractException invalidData() {
        return new ServerContractException(
                "MARKET_DATA_INTEGRITY", "Market record data is inconsistent");
    }

    private static ServerContractException invalidDefinition() {
        return new ServerContractException(
                "MARKET_DEFINITION_INVALID", "Market definition is invalid");
    }

    private static MarketMonitoringRecord record(Header row, Map<String, BigDecimal> facts) {
        return new MarketMonitoringRecord(
                row.id(), row.product(), row.objectType(), row.region(), row.tradeDate(), row.reportedAt(),
                row.direction(), row.purchaseBasePrice(), row.saleBasePrice(), row.carriageBoardAmount(),
                row.freightAmount(), row.packagingAmount(), row.packagingForm(), row.actualTradePrice(),
                row.status(), row.returnReason(), facts, row.version());
    }

    private static Map<String, Object> header(MarketMonitoringRecord record, String actorId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", record.id());
        values.put("product", record.productCode());
        values.put("object", record.objectTypeCode());
        values.put("region", record.regionCode());
        values.put("date", record.tradeDate());
        values.put("reported", record.reportedAt());
        values.put("direction", record.direction().name());
        values.put("purchase", record.purchaseBasePrice());
        values.put("sale", record.saleBasePrice());
        values.put("carriage", record.carriageBoardAmount());
        values.put("packagingAmount", record.packagingAmount());
        values.put("freight", record.freightAmount());
        values.put("packaging", record.packagingForm());
        values.put("status", record.status().name());
        values.put("reason", record.returnReason());
        values.put("actor", actorId);
        return values;
    }

    private boolean exists(String sql, String value) {
        return Boolean.TRUE.equals(jdbc.sql(sql).param("value", value).query(Boolean.class).single());
    }

    private static void requireUpdated(int updated) {
        if (updated == 0) {
            throw new ConflictException(
                    "MARKET_RECORD_VERSION_CONFLICT", "Market record has changed");
        }
    }

    private static SqlFilter filter(
            String productCode, Map<String, String> filters, Set<String> authorizedRegionCodes) {
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
                case "status" -> {
                    sql.append(" AND r.status_code = :status");
                    parameters.put("status", value);
                }
                case "objectTypeCode" -> {
                    sql.append(" AND r.object_type_code = :objectTypeCode");
                    parameters.put("objectTypeCode", value);
                }
                case "regionCode" -> {
                    sql.append(" AND r.region_code = :regionCode");
                    parameters.put("regionCode", value);
                }
                case "tradeDate" -> {
                    sql.append(" AND r.trade_date = :tradeDate");
                    parameters.put("tradeDate", LocalDate.parse(value));
                }
                case "surveyYear" -> {
                    sql.append(" AND r.survey_year = :surveyYear");
                    parameters.put("surveyYear", year(value));
                }
                case "surveyMonth" -> {
                    sql.append(" AND r.survey_month = :surveyMonth");
                    parameters.put("surveyMonth", month(value));
                }
                case "fillingDateFrom" -> {
                    sql.append(" AND COALESCE(r.submitted_at,r.created_at) >= :fillingDateFrom");
                    parameters.put("fillingDateFrom", startOfDay(value));
                }
                case "fillingDateTo" -> {
                    sql.append(" AND COALESCE(r.submitted_at,r.created_at) < :fillingDateToExclusive");
                    parameters.put("fillingDateToExclusive", startOfDay(value).plusDays(1));
                }
                default -> throw new IllegalArgumentException("Unsupported market filter");
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

    private static String fillingTimeBasis(MarketStatus status, OffsetDateTime submittedAt) {
        if (submittedAt != null) return "SUBMITTED_AT";
        return status == MarketStatus.DRAFT ? "DRAFT_CREATED_AT" : "CREATED_AT_NO_SUBMISSION_AUDIT";
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private record SqlFilter(String sql, Map<String, Object> parameters) { }
    private record CoreRow(String code, String label, String controlType, String unit, String description,
                           String domainBinding, String capability, boolean required,
                           Integer precision, Integer scale, int sortOrder,
                           boolean mounted, boolean mapped) { }
    private record OptionRow(String fieldCode, String value, String label, int sortOrder) { }
    private record FactRow(String recordId, String code, BigDecimal value, boolean applicable) { }
    private record ExtensionRow(String recordId, String code, String value, boolean applicable) { }
    private record Header(String id, String product, String objectType, String region, LocalDate tradeDate,
                          OffsetDateTime reportedAt, MarketTradeDirection direction,
                          BigDecimal purchaseBasePrice, BigDecimal saleBasePrice,
                          BigDecimal carriageBoardAmount, BigDecimal packagingAmount, BigDecimal freightAmount,
                          String packagingForm, BigDecimal actualTradePrice, MarketStatus status,
                          String returnReason, long version) { }
    private record ListHeader(String id, String regionName, String objectTypeName, LocalDate tradeDate,
                              OffsetDateTime reportedAt, int surveyYear, Integer surveyMonth,
                              String surveyPeriodPrecision, String surveyPeriodGovernanceState,
                              OffsetDateTime createdAt, OffsetDateTime submittedAt, BigDecimal purchaseBasePrice,
                              BigDecimal saleBasePrice, BigDecimal carriageBoardAmount, String packagingLabel,
                              BigDecimal packagingAmount, BigDecimal freightAmount, MarketStatus status,
                              String statusLabel, long version) { }
}
