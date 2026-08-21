package com.cofco.qiqihar.graintrade.overview.infrastructure;

import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointAggregate;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointDetail;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointIcon;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointList;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOverviewSamplePointRepository implements OverviewSamplePointRepository {
    private static final List<CategoryDefinition> CATEGORIES = List.of(
            new CategoryDefinition("PRODUCTION", "产情类"),
            new CategoryDefinition("MARKET", "市场类"));
    private static final Map<String, List<String>> SUMMARY_FIELDS = Map.of(
            "PRODUCTION", List.of(
                    "SAMPLE_CONTACT", "SURVEYOR_NAME", "SURVEYOR_PHONE",
                    "CULTIVATED_AREA_MU", "ESTIMATED_OUTPUT_KG", "YIELD_PER_MU_KG"),
            "MARKET", List.of(
                    "SAMPLE_CONTACT", "SURVEYOR_NAME", "SURVEYOR_PHONE",
                    "PURCHASE_PRICE", "SALE_PRICE", "PURCHASE_VOLUME", "SALES_VOLUME"));

    private final JdbcClient jdbc;

    public JdbcOverviewSamplePointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String regionLevel(String regionCode) {
        return jdbc.sql("SELECT administrative_level FROM platform.region WHERE code=:code")
                .param("code", regionCode).query(String.class).optional().orElse(null);
    }

    @Override
    public boolean knownCategory(String categoryCode) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM platform.object_type object_type
                  WHERE object_type.business_domain=:category AND object_type.overview_enabled)
                """).param("category", categoryCode).query(Boolean.class).single());
    }

    @Override
    public boolean knownType(String categoryCode, String typeCode) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM platform.object_type object_type
                  WHERE object_type.business_domain=:category AND object_type.code=:type
                    AND object_type.overview_enabled)
                """).param("category", categoryCode).param("type", typeCode)
                .query(Boolean.class).single());
    }

    @Override
    public List<OverviewSamplePointAggregate> aggregates(
            int year, String productCode, String parentCode, Set<String> authorizedRegionCodes) {
        List<AggregateRegion> regions = jdbc.sql("""
                WITH RECURSIVE children AS (
                  SELECT code,name,administrative_level,sort_order
                  FROM platform.region
                  WHERE parent_code IS NOT DISTINCT FROM CAST(:parent AS varchar)
                ), descendants(child_code,code) AS (
                  SELECT code,code FROM children
                  UNION ALL
                  SELECT descendants.child_code,region.code
                  FROM platform.region region
                  JOIN descendants ON region.parent_code=descendants.code
                )
                SELECT child.code,child.name,child.administrative_level
                FROM children child
                WHERE :unrestricted OR EXISTS(
                  SELECT 1 FROM descendants
                  WHERE descendants.child_code=child.code
                    AND descendants.code IN (:authorizedRegions))
                ORDER BY child.sort_order,child.code
                """).param("parent", parentCode)
                .param("unrestricted", unrestricted(authorizedRegionCodes))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new AggregateRegion(
                        row.getString("code"), row.getString("name"),
                        row.getString("administrative_level")))
                .list();
        return regions.stream().map(region -> {
            EntityProjection projection = projection(year, productCode, region.code(), null, null, null,
                    authorizedRegionCodes);
            long quality = projection.entities().stream()
                    .filter(entity -> entity.dataQualityReason() != null).count();
            long valid = projection.entities().size() - quality;
            long corrections = projection.corrections().size();
            long productionCount = categoryPointCount(projection.entities(), "PRODUCTION");
            long marketCount = categoryPointCount(projection.entities(), "MARKET");
            return new OverviewSamplePointAggregate(region.code(), region.name(), region.level(),
                    projection.entities().size(), productionCount, marketCount,
                    valid, quality, corrections, quality + corrections);
        }).toList();
    }

    private static long categoryPointCount(List<Entity> entities, String categoryCode) {
        return entities.stream().filter(entity -> entity.rows().stream()
                .anyMatch(row -> row.categoryCode().equals(categoryCode))).count();
    }

    @Override
    public OverviewSamplePointList list(int year, String productCode, String regionCode,
            String categoryCode, String typeCode, String query, Set<String> authorizedRegionCodes) {
        EntityProjection projection = projection(year, productCode, regionCode, categoryCode, typeCode, query,
                authorizedRegionCodes);
        EntityProjection catalogProjection = categoryCode == null && typeCode == null && query == null
                ? projection
                : projection(year, productCode, regionCode, null, null, null, authorizedRegionCodes);
        List<OverviewSamplePointList.Item> items = projection.entities().stream()
                .map(entity -> listItem(entity, categoryCode, typeCode, query))
                .sorted(Comparator.comparing(OverviewSamplePointList.Item::name)
                        .thenComparing(item -> item.samplePointId().toString()))
                .toList();
        long quality = projection.entities().stream()
                .filter(entity -> entity.dataQualityReason() != null).count();
        long valid = projection.entities().size() - quality;
        List<OverviewSamplePointList.CorrectionSource> corrections = projection.corrections().stream()
                .map(row -> new OverviewSamplePointList.CorrectionSource(
                        row.categoryCode(), row.sourceRecordId(), row.sourceRole(), row.unresolvedReason()))
                .toList();
        long catalogQuality = catalogProjection.entities().stream()
                .filter(entity -> entity.dataQualityReason() != null).count();
        long catalogUnresolved = catalogQuality + catalogProjection.corrections().size();
        return new OverviewSamplePointList(regionCode, items.size(), valid, quality, corrections.size(),
                catalogUnresolved, categories(projection.entities()), items, corrections);
    }

    @Override
    public List<OverviewSamplePointIcon> icons(int year, String productCode, String regionCode,
            String categoryCode, String typeCode, String query, Set<String> authorizedRegionCodes) {
        return projection(year, productCode, regionCode, categoryCode, typeCode, query, authorizedRegionCodes)
                .entities().stream()
                .filter(entity -> entity.dataQualityReason() == null
                        || "DUPLICATE_COORDINATE_UNVERIFIED".equals(entity.dataQualityReason()))
                .map(this::icon)
                .sorted(Comparator.comparing(OverviewSamplePointIcon::name)
                        .thenComparing(icon -> icon.samplePointId().toString()))
                .toList();
    }

    @Override
    public Optional<OverviewSamplePointDetail> detail(int year, String productCode, UUID samplePointId,
            String regionCode, String categoryCode, String typeCode, Set<String> authorizedRegionCodes) {
        Optional<Entity> selected = projection(year, productCode, regionCode, categoryCode, typeCode, null,
                authorizedRegionCodes).entities().stream()
                .filter(entity -> entity.samplePointId().equals(samplePointId)).findFirst();
        if (selected.isEmpty()) return Optional.empty();
        Entity entity = selected.get();
        List<SourceRow> rows = latestRowsPerMonth(sourceRows(
                year, productCode, regionCode, authorizedRegionCodes, true).stream()
                .filter(SourceRow::approvedPoint)
                .filter(row -> samplePointId.equals(row.samplePointId()))
                .filter(row -> matchesFilter(row, categoryCode, typeCode, null))
                .toList());
        SourceRow identity = entity.rows().getFirst();
        List<OverviewSamplePointDetail.Association> associations = rows.stream()
                .map(row -> new OverviewSamplePointDetail.Association(
                        row.categoryCode(), row.categoryName(), row.sourceRole(),
                        row.typeCode(), row.typeName(), row.productCode(), row.productName(),
                        row.occurrenceDate(), row.sourceVersion(), businessValues(row)))
                .distinct()
                .sorted(Comparator.comparing(OverviewSamplePointDetail.Association::categoryCode)
                        .thenComparing(OverviewSamplePointDetail.Association::typeCode)
                        .thenComparing(OverviewSamplePointDetail.Association::productCode)
                        .thenComparing(OverviewSamplePointDetail.Association::occurrenceDate,
                                Comparator.reverseOrder())
                        .thenComparing(OverviewSamplePointDetail.Association::sourceRole))
                .toList();
        return Optional.of(new OverviewSamplePointDetail(samplePointId, identity.canonicalName(),
                identity.governedRegionCode(), identity.governedRegionName(), identity.locationState(),
                entity.dataQualityReason(), associations));
    }

    private List<SourceRow> sourceRows(
            int year, String productCode, String regionCode, Set<String> authorizedRegionCodes,
            boolean includePeriodHistory) {
        return jdbc.sql("""
                WITH RECURSIVE descendants(code) AS (
                  SELECT code FROM platform.region WHERE code=:region
                  UNION ALL
                  SELECT child.code FROM platform.region child
                  JOIN descendants parent ON child.parent_code=parent.code
                )
                SELECT CASE WHEN point.approval_state='APPROVED'
                         THEN point.sample_point_id END sample_point_id,
                       source.category_code,
                       source.category_name,
                       source.source_record_id,
                       source.source_role,
                       source.product_code,
                       source.product_name,
                       source.occurrence_date,
                       source.source_version,
                       source.source_region_code,
                       CASE WHEN point.approval_state='APPROVED'
                         THEN point.region_code END governed_region_code,
                       CASE WHEN point.approval_state='APPROVED'
                         THEN governed_region.name END governed_region_name,
                       source.type_code,
                       source.type_name,
                       source.type_sort_order,
                       visible_type.overview_icon_key,
                       CASE WHEN point.approval_state='APPROVED'
                         THEN point.canonical_name END canonical_name,
                       CASE WHEN point.approval_state='APPROVED'
                         THEN point.approval_state END point_approval_state,
                       CASE WHEN point.approval_state='APPROVED'
                         THEN point.location_state END location_state,
                       CASE WHEN resolution.resolution_action='LINK' THEN NULL
                         ELSE source.unresolved_reason END unresolved_reason,
                       CASE source.category_code
                         WHEN 'PRODUCTION' THEN (
                           SELECT metadata.value
                           FROM production.production_record_submission_metadata metadata
                           WHERE metadata.record_id=source.source_record_id
                             AND metadata.field_code='PROD_SAMPLE_CONTACT')
                         WHEN 'MARKET' THEN (
                           SELECT value.value
                           FROM market.market_record_core_value value
                           WHERE value.record_id=source.source_record_id
                             AND value.field_code='MKT_SAMPLE_CONTACT')
                       END sample_contact,
                       ST_X(point.governed_point) longitude,
                       ST_Y(point.governed_point) latitude,
                       COALESCE(point.coordinate_shared_verified,false) coordinate_shared_verified
                FROM overview.sample_point_query_source source
                JOIN platform.object_type visible_type
                  ON visible_type.business_domain=source.category_code
                 AND visible_type.code=source.type_code
                 AND visible_type.overview_enabled
                LEFT JOIN registry.current_sample_subject_resolution resolution
                  ON resolution.source_domain=source.category_code
                 AND resolution.source_record_id=source.source_record_id
                LEFT JOIN registry.sample_point point
                  ON point.sample_point_id=COALESCE(
                    resolution.target_sample_point_id,source.sample_point_id)
                LEFT JOIN platform.region governed_region ON governed_region.code=point.region_code
                WHERE (
                    source.category_code='PRODUCTION' AND EXISTS(
                      SELECT 1 FROM production.production_record record
                      WHERE record.record_id=source.source_record_id
                        AND record.survey_year=:year
                        AND record.survey_period_governance_state='CONFIRMED'
                        AND (:includePeriodHistory OR EXISTS(
                          SELECT 1 FROM production.effective_approved_production_record effective
                          WHERE effective.record_id=record.record_id)))
                    OR source.category_code='MARKET' AND EXISTS(
                      SELECT 1 FROM market.market_record record
                      WHERE record.record_id=source.source_record_id
                        AND record.survey_year=:year
                        AND record.survey_period_governance_state='CONFIRMED'
                        AND (:includePeriodHistory OR EXISTS(
                          SELECT 1 FROM market.effective_approved_market_record effective
                          WHERE effective.record_id=record.record_id))))
                  AND resolution.resolution_action IS DISTINCT FROM 'VOID'
                  AND source.product_code=:productCode
                  AND COALESCE(
                        CASE WHEN point.approval_state='APPROVED'
                          THEN point.region_code END,
                        source.source_region_code) IN (SELECT code FROM descendants)
                  AND (:unrestricted OR (
                    source.source_region_code IN (:authorizedRegions)
                    AND (point.approval_state IS DISTINCT FROM 'APPROVED'
                      OR point.region_code IS NULL
                      OR point.region_code IN (:authorizedRegions))))
                ORDER BY source.type_sort_order,source.canonical_name,source.sample_point_id,
                         source.product_code,source.source_role
                """).param("year", year).param("productCode", productCode).param("region", regionCode)
                .param("includePeriodHistory", includePeriodHistory)
                .param("unrestricted", unrestricted(authorizedRegionCodes))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new SourceRow(
                        row.getObject("sample_point_id", UUID.class),
                        row.getString("category_code"),
                        row.getString("category_name"),
                        row.getString("source_record_id"),
                        row.getString("source_role"),
                        row.getString("product_code"),
                        row.getString("product_name"),
                        row.getObject("occurrence_date", LocalDate.class),
                        row.getLong("source_version"),
                        row.getString("source_region_code"),
                        row.getString("governed_region_code"),
                        row.getString("governed_region_name"),
                        row.getString("type_code"),
                        row.getString("type_name"),
                        row.getInt("type_sort_order"),
                        row.getString("overview_icon_key"),
                        row.getString("canonical_name"),
                        row.getString("point_approval_state"),
                        row.getString("location_state"),
                        row.getString("unresolved_reason"),
                        row.getString("sample_contact"),
                        row.getObject("longitude", Double.class),
                        row.getObject("latitude", Double.class),
                        row.getBoolean("coordinate_shared_verified")))
                .list();
    }

    private Map<String, OverviewSamplePointDetail.BusinessValue> businessValues(SourceRow row) {
        return switch (row.categoryCode()) {
            case "PRODUCTION" -> productionValues(row.sourceRecordId());
            case "MARKET" -> marketValues(row.sourceRecordId());
            default -> Map.of();
        };
    }

    private Map<String, OverviewSamplePointDetail.BusinessValue> productionValues(String recordId) {
        Map<String, OverviewSamplePointDetail.BusinessValue> values = jdbc.sql("""
                SELECT record.cultivated_area_mu,
                       record.estimated_output_kg,
                       record.yield_per_mu_kg,
                       (SELECT value FROM production.production_record_submission_metadata metadata
                        WHERE metadata.record_id=record.record_id
                          AND metadata.field_code='PROD_SAMPLE_CONTACT') sample_contact,
                       (SELECT value FROM production.production_record_submission_metadata metadata
                        WHERE metadata.record_id=record.record_id
                          AND metadata.field_code='PROD_SURVEYOR_NAME') surveyor_name,
                       (SELECT value FROM production.production_record_submission_metadata metadata
                        WHERE metadata.record_id=record.record_id
                          AND metadata.field_code='PROD_SURVEYOR_PHONE') surveyor_phone
                FROM production.production_record record
                WHERE record.record_id=:recordId
                """).param("recordId", recordId).query((row, index) -> {
                    Map<String, OverviewSamplePointDetail.BusinessValue> initialValues = new LinkedHashMap<>();
                    put(initialValues, "SAMPLE_CONTACT", "样本点联系方式",
                            row.getString("sample_contact"), null);
                    put(initialValues, "SURVEYOR_NAME", "调研人", row.getString("surveyor_name"), null);
                    put(initialValues, "SURVEYOR_PHONE", "调研人联系方式",
                            row.getString("surveyor_phone"), null);
                    put(initialValues, "CULTIVATED_AREA_MU", "种植面积",
                            decimal(row.getBigDecimal("cultivated_area_mu")), "亩");
                    put(initialValues, "ESTIMATED_OUTPUT_KG", "总产量",
                            decimal(row.getBigDecimal("estimated_output_kg")), "千克");
                    put(initialValues, "YIELD_PER_MU_KG", "单产",
                            decimal(row.getBigDecimal("yield_per_mu_kg")), "千克/亩");
                    return initialValues;
                }).single();
        jdbc.sql("""
                WITH facts AS (
                  SELECT quality_code code,value FROM production.production_record_quality
                  WHERE record_id=:recordId
                  UNION ALL
                  SELECT cost_code,value FROM production.production_record_cost
                  WHERE record_id=:recordId
                  UNION ALL
                  SELECT insurance_code,value FROM production.production_record_insurance
                  WHERE record_id=:recordId
                  UNION ALL
                  SELECT subsidy_code,value FROM production.production_record_subsidy
                  WHERE record_id=:recordId
                )
                SELECT facts.code,definition.label,definition.unit,facts.value
                FROM facts
                JOIN platform.production_fact_definition definition ON definition.code=facts.code
                JOIN platform.production_fact_category category ON category.code=definition.category
                JOIN production.production_record record ON record.record_id=:recordId
                WHERE EXISTS(
                  SELECT 1 FROM platform.production_fact_applicability applicability
                  WHERE applicability.fact_code=facts.code
                    AND applicability.product_code=record.product_code
                    AND applicability.business_domain='PRODUCTION'
                    AND applicability.page_kind='MONITORING'
                    AND (applicability.object_type_code IS NULL
                      OR applicability.object_type_code=record.object_type_code))
                ORDER BY category.sort_order,
                  (SELECT min(applicability.sort_order)
                   FROM platform.production_fact_applicability applicability
                   WHERE applicability.fact_code=facts.code
                     AND applicability.product_code=record.product_code
                     AND applicability.business_domain='PRODUCTION'
                     AND applicability.page_kind='MONITORING'
                     AND (applicability.object_type_code IS NULL
                       OR applicability.object_type_code=record.object_type_code)),
                  facts.code
                """).param("recordId", recordId).query((row, index) -> new DirectoryValue(
                        row.getString("code"), row.getString("label"), row.getString("unit"),
                        decimal(row.getBigDecimal("value"))))
                .list().forEach(value -> put(values, value.code(), value.label(), value.value(), value.unit()));
        return values;
    }

    private Map<String, OverviewSamplePointDetail.BusinessValue> marketValues(String recordId) {
        Map<String, OverviewSamplePointDetail.BusinessValue> values = jdbc.sql("""
                SELECT record.purchase_base_price,
                       record.sale_base_price,
                       record.trade_direction,
                       record.carriage_board_amount,
                       record.packaging_amount,
                       record.freight_amount,
                       record.packaging_form,
                       record.actual_trade_price,
                       (SELECT value FROM market.market_record_core_value value
                        WHERE value.record_id=record.record_id
                          AND value.field_code='MKT_SAMPLE_CONTACT') sample_contact,
                       (SELECT value FROM market.market_record_core_value value
                        WHERE value.record_id=record.record_id
                          AND value.field_code='MKT_SURVEYOR_NAME') surveyor_name,
                       (SELECT value FROM market.market_record_core_value value
                        WHERE value.record_id=record.record_id
                          AND value.field_code='MKT_SURVEYOR_PHONE') surveyor_phone,
                       (SELECT option.label FROM platform.market_core_field_option option
                        WHERE option.field_code='MKT_TRADE_DIRECTION'
                          AND option.value=record.trade_direction) trade_direction_label,
                       (SELECT option.label FROM platform.market_core_field_option option
                        WHERE option.field_code='MKT_PACKAGING_FORM'
                          AND option.value=record.packaging_form) packaging_form_label
                FROM market.market_record record
                WHERE record.record_id=:recordId
                """).param("recordId", recordId).query((row, index) -> {
                    Map<String, OverviewSamplePointDetail.BusinessValue> initialValues = new LinkedHashMap<>();
                    put(initialValues, "SAMPLE_CONTACT", "样本点联系方式",
                            row.getString("sample_contact"), null);
                    put(initialValues, "SURVEYOR_NAME", "调研人", row.getString("surveyor_name"), null);
                    put(initialValues, "SURVEYOR_PHONE", "调研人联系方式",
                            row.getString("surveyor_phone"), null);
                    put(initialValues, "PURCHASE_PRICE", "对象采购价格",
                            decimal(row.getBigDecimal("purchase_base_price")), "元/吨");
                    put(initialValues, "SALE_PRICE", "对象销售价格",
                            decimal(row.getBigDecimal("sale_base_price")), "元/吨");
                    put(initialValues, "TRADE_DIRECTION", "买卖方向",
                            row.getString("trade_direction_label"), null);
                    put(initialValues, "CARRIAGE_BOARD_AMOUNT", "车板组成",
                            decimal(row.getBigDecimal("carriage_board_amount")), "元/吨");
                    put(initialValues, "PACKAGING_FORM", "包装形态",
                            row.getString("packaging_form_label"), null);
                    put(initialValues, "PACKAGING_AMOUNT", "包装组成",
                            decimal(row.getBigDecimal("packaging_amount")), "元/吨");
                    put(initialValues, "FREIGHT_AMOUNT", "运费组成",
                            decimal(row.getBigDecimal("freight_amount")), "元/吨");
                    put(initialValues, "ACTUAL_TRADE_PRICE", "实际成交价",
                            decimal(row.getBigDecimal("actual_trade_price")), "元/吨");
                    return initialValues;
                }).single();
        jdbc.sql("""
                SELECT fact.fact_code code,definition.label,definition.unit,fact.value
                FROM market.market_record_fact fact
                JOIN platform.market_fact_definition definition ON definition.code=fact.fact_code
                JOIN platform.market_fact_category category ON category.code=definition.category
                JOIN platform.market_fact_applicability applicability
                  ON applicability.fact_code=fact.fact_code
                 AND applicability.product_code=fact.product_code
                 AND applicability.object_type_code=fact.object_type_code
                WHERE fact.record_id=:recordId
                ORDER BY category.sort_order,applicability.sort_order,fact.fact_code
                """).param("recordId", recordId).query((row, index) -> new DirectoryValue(
                        row.getString("code"), row.getString("label"), row.getString("unit"),
                        decimal(row.getBigDecimal("value"))))
                .list().forEach(value -> put(values, value.code(), value.label(), value.value(), value.unit()));
        jdbc.sql("""
                SELECT value.field_code code,definition.label,definition.unit,value.value
                FROM market.market_record_core_value value
                JOIN platform.market_core_field_definition definition ON definition.code=value.field_code
                JOIN platform.page_definition_field mounted
                  ON mounted.product_code=value.product_code
                 AND mounted.business_domain='MARKET'
                 AND mounted.page_kind='MONITORING'
                 AND mounted.field_code=value.field_code
                WHERE value.record_id=:recordId
                  AND value.field_code NOT IN (
                    'MKT_SAMPLE_NAME','MKT_SAMPLE_CONTACT',
                    'MKT_SURVEYOR_NAME','MKT_SURVEYOR_PHONE',
                    'MKT_REPORTER_NAME','MKT_REPORTER_PHONE','MKT_CULTIVAR_NAME')
                ORDER BY mounted.sort_order,definition.sort_order,value.field_code
                """).param("recordId", recordId).query((row, index) -> new DirectoryValue(
                        row.getString("code"), row.getString("label"), row.getString("unit"),
                        row.getString("value")))
                .list().forEach(value -> put(values, value.code(), value.label(), value.value(), value.unit()));
        return values;
    }

    private static void put(Map<String, OverviewSamplePointDetail.BusinessValue> values,
            String code, String label, String value, String unitCode) {
        if (value != null && !value.isBlank()) {
            values.put(code, new OverviewSamplePointDetail.BusinessValue(label, value, unitCode));
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private record DirectoryValue(String code, String label, String unit, String value) {}

    private EntityProjection projection(int year, String productCode, String regionCode, String categoryCode,
            String typeCode, String query, Set<String> authorizedRegionCodes) {
        List<SourceRow> rows = sourceRows(year, productCode, regionCode, authorizedRegionCodes, false);
        Map<UUID, List<SourceRow>> byPoint = rows.stream()
                .filter(SourceRow::approvedPoint)
                .collect(Collectors.groupingBy(SourceRow::samplePointId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<UUID, String> reasons = new LinkedHashMap<>();
        byPoint.forEach((id, pointRows) -> reasons.put(id, coordinateReason(pointRows)));

        Map<Coordinate, List<UUID>> pointsByCoordinate = byPoint.entrySet().stream()
                .filter(entry -> reasons.get(entry.getKey()) == null)
                .collect(Collectors.groupingBy(entry -> Coordinate.of(entry.getValue().getFirst()),
                        LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        pointsByCoordinate.values().stream().filter(ids -> ids.size() > 1).forEach(ids -> {
            boolean verified = ids.stream().allMatch(id -> byPoint.get(id).getFirst().coordinateSharedVerified());
            if (!verified) ids.forEach(id -> reasons.put(id, "DUPLICATE_COORDINATE_UNVERIFIED"));
        });

        List<Entity> entities = byPoint.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .anyMatch(row -> matchesFilter(row, categoryCode, typeCode, query)))
                .map(entry -> {
                    SourceRow semantic = entry.getValue().stream()
                            .filter(row -> matchesFilter(row, categoryCode, typeCode, query))
                            .findFirst().orElseThrow();
                    return new Entity(entry.getKey(), entry.getValue(), reasons.get(entry.getKey()),
                            semantic.iconKey());
                })
                .toList();
        List<SourceRow> corrections = rows.stream()
                .filter(row -> !row.approvedPoint() && row.unresolvedReason() != null)
                .filter(row -> matchesCorrectionFilter(row, categoryCode, typeCode, query))
                .distinct()
                .toList();
        return new EntityProjection(entities, corrections);
    }

    private static List<SourceRow> latestRowsPerMonth(List<SourceRow> rows) {
        Comparator<SourceRow> latestFirst = Comparator
                .comparing(SourceRow::occurrenceDate, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingLong(SourceRow::sourceVersion).reversed())
                .thenComparing(SourceRow::sourceRecordId, Comparator.reverseOrder());
        Map<String, SourceRow> latest = new LinkedHashMap<>();
        rows.stream().sorted(latestFirst).forEach(row -> latest.putIfAbsent(
                String.join("|", row.categoryCode(), row.sourceRole(), row.typeCode(), row.productCode(),
                        row.occurrenceDate().getYear() + "-" + row.occurrenceDate().getMonthValue()),
                row));
        return List.copyOf(latest.values());
    }

    private static String coordinateReason(List<SourceRow> rows) {
        Optional<String> sourceReason = rows.stream().map(SourceRow::unresolvedReason)
                .filter(java.util.Objects::nonNull).sorted().findFirst();
        if (sourceReason.isPresent()) return sourceReason.get();
        SourceRow identity = rows.getFirst();
        if (identity.governedRegionCode() == null) return "REGION_MISSING";
        if (identity.longitude() == null || identity.latitude() == null) return "LOCATION_MISSING";
        if (identity.longitude() < -180 || identity.longitude() > 180
                || identity.latitude() < -90 || identity.latitude() > 90) {
            return "COORDINATE_OUT_OF_RANGE";
        }
        return null;
    }

    private List<OverviewSamplePointList.Category> categories(List<Entity> entities) {
        Map<String, List<TypeDefinition>> formalTypes = formalTypes().stream()
                .collect(Collectors.groupingBy(TypeDefinition::categoryCode, LinkedHashMap::new, Collectors.toList()));
        List<OverviewSamplePointList.Category> result = new ArrayList<>();
        for (CategoryDefinition category : CATEGORIES) {
            long categoryPoints = entities.stream().filter(entity -> entity.rows().stream()
                    .anyMatch(row -> row.categoryCode().equals(category.code()))).count();
            List<OverviewSamplePointList.Type> types = formalTypes.getOrDefault(category.code(), List.of()).stream()
                    .map(type -> new OverviewSamplePointList.Type(type.code(), type.name(), type.iconKey(),
                            entities.stream().filter(entity -> entity.rows().stream()
                                    .anyMatch(row -> row.categoryCode().equals(category.code())
                                            && row.typeCode().equals(type.code()))).count()))
                    .toList();
            result.add(new OverviewSamplePointList.Category(
                    category.code(), category.name(), categoryPoints, types));
        }
        return result;
    }

    private List<TypeDefinition> formalTypes() {
        return jdbc.sql("""
                SELECT object_type.business_domain,object_type.code,object_type.name,
                       object_type.overview_icon_key,object_type.sort_order
                FROM platform.object_type object_type
                WHERE object_type.overview_enabled
                ORDER BY object_type.sort_order,object_type.code
                """).query((row, index) -> new TypeDefinition(
                        row.getString("business_domain"), row.getString("code"),
                        row.getString("name"), row.getString("overview_icon_key"),
                        row.getInt("sort_order")))
                .list();
    }

    private OverviewSamplePointList.Item listItem(
            Entity entity, String categoryCode, String typeCode, String query) {
        List<SourceRow> rows = entity.rows().stream()
                .filter(row -> matchesFilter(row, categoryCode, typeCode, query)).toList();
        SourceRow identity = rows.getFirst();
        List<OverviewSamplePointList.CategoryRef> categories = distinct(rows,
                SourceRow::categoryCode,
                row -> new OverviewSamplePointList.CategoryRef(row.categoryCode(), row.categoryName()));
        List<OverviewSamplePointList.TypeRef> types = distinct(rows,
                SourceRow::typeCode,
                row -> new OverviewSamplePointList.TypeRef(row.typeCode(), row.typeName(), row.iconKey()));
        List<OverviewSamplePointList.ProductRef> products = distinct(rows,
                SourceRow::productCode,
                row -> new OverviewSamplePointList.ProductRef(row.productCode(), row.productName()));
        SourceRow latest = rows.stream().sorted(
                Comparator.comparing(SourceRow::occurrenceDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Comparator.comparingLong(SourceRow::sourceVersion).reversed())
                        .thenComparing(SourceRow::sourceRecordId))
                .findFirst().orElseThrow();
        Map<String, OverviewSamplePointDetail.BusinessValue> summary = summaryValues(latest);
        return new OverviewSamplePointList.Item(identity.samplePointId(), identity.canonicalName(),
                identity.governedRegionCode(), identity.governedRegionName(), identity.locationState(),
                entity.dataQualityReason(), categories, types, products,
                latest.occurrenceDate(), summary);
    }

    private Map<String, OverviewSamplePointDetail.BusinessValue> summaryValues(SourceRow row) {
        Map<String, OverviewSamplePointDetail.BusinessValue> all = businessValues(row);
        Map<String, OverviewSamplePointDetail.BusinessValue> summary = new LinkedHashMap<>();
        SUMMARY_FIELDS.getOrDefault(row.categoryCode(), List.of()).forEach(code -> {
            OverviewSamplePointDetail.BusinessValue value = all.get(code);
            if (value != null) summary.put(code, value);
        });
        return summary;
    }

    private OverviewSamplePointIcon icon(Entity entity) {
        List<SourceRow> rows = entity.rows();
        SourceRow identity = rows.getFirst();
        List<OverviewSamplePointIcon.TypeRef> types = distinct(rows,
                SourceRow::typeCode,
                row -> new OverviewSamplePointIcon.TypeRef(row.typeCode(), row.typeName(), row.iconKey()));
        return new OverviewSamplePointIcon(identity.samplePointId(), identity.canonicalName(),
                entity.iconKey(), types,
                identity.longitude(), identity.latitude(), entity.dataQualityReason());
    }

    private static boolean matchesFilter(SourceRow row, String categoryCode, String typeCode, String query) {
        if (categoryCode != null && !row.categoryCode().equals(categoryCode)) return false;
        if (typeCode != null && !row.typeCode().equals(typeCode)) return false;
        if (query == null) return true;
        String normalized = query.toLowerCase(Locale.ROOT);
        return contains(row.canonicalName(), normalized)
                || contains(row.governedRegionName(), normalized)
                || contains(row.sampleContact(), normalized);
    }

    private static boolean contains(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static boolean matchesCorrectionFilter(
            SourceRow row, String categoryCode, String typeCode, String query) {
        if (categoryCode != null && !row.categoryCode().equals(categoryCode)) return false;
        if (typeCode != null && !row.typeCode().equals(typeCode)) return false;
        return query == null;
    }

    private static <T> List<T> distinct(List<SourceRow> rows, Function<SourceRow, String> key,
            Function<SourceRow, T> mapper) {
        return new ArrayList<>(rows.stream().collect(Collectors.toMap(
                key, mapper, (left, right) -> left, LinkedHashMap::new)).values());
    }

    private static boolean unrestricted(Set<String> authorizedRegionCodes) {
        return authorizedRegionCodes.contains("*");
    }

    private record CategoryDefinition(String code, String name) {}
    private record TypeDefinition(
            String categoryCode, String code, String name, String iconKey, int sortOrder) {}
    private record AggregateRegion(String code, String name, String level) {}
    private record EntityProjection(List<Entity> entities, List<SourceRow> corrections) {}
    private record Entity(UUID samplePointId, List<SourceRow> rows, String dataQualityReason, String iconKey) {}
    private record Coordinate(double longitude, double latitude) {
        static Coordinate of(SourceRow row) {
            return new Coordinate(row.longitude(), row.latitude());
        }
    }

    private record SourceRow(
            UUID samplePointId,
            String categoryCode,
            String categoryName,
            String sourceRecordId,
            String sourceRole,
            String productCode,
            String productName,
            LocalDate occurrenceDate,
            long sourceVersion,
            String sourceRegionCode,
            String governedRegionCode,
            String governedRegionName,
            String typeCode,
            String typeName,
            int typeSortOrder,
            String iconKey,
            String canonicalName,
            String pointApprovalState,
            String locationState,
            String unresolvedReason,
            String sampleContact,
            Double longitude,
            Double latitude,
            boolean coordinateSharedVerified) {
        boolean approvedPoint() {
            return samplePointId != null && "APPROVED".equals(pointApprovalState);
        }
    }
}
