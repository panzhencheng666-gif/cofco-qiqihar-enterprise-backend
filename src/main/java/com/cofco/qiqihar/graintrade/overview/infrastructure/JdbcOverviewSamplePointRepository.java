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
import java.util.LinkedHashSet;
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
            new CategoryDefinition("MARKET", "市场类"),
            new CategoryDefinition("LOGISTICS", "物流节点"));

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
        return CATEGORIES.stream().anyMatch(category -> category.code().equals(categoryCode));
    }

    @Override
    public boolean knownType(String categoryCode, String typeCode) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM platform.object_type
                  WHERE business_domain=:category AND code=:type)
                """).param("category", categoryCode).param("type", typeCode)
                .query(Boolean.class).single());
    }

    @Override
    public List<OverviewSamplePointAggregate> aggregates(
            String parentCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
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
                ), scoped_source AS (
                  SELECT source.*,
                         COALESCE(source.governed_region_code,source.source_region_code) effective_region_code
                  FROM overview.sample_point_query_source source
                  WHERE :unrestricted OR (
                    source.source_region_code IN (:authorizedRegions)
                    AND (source.governed_region_code IS NULL
                      OR source.governed_region_code IN (:authorizedRegions))
                    AND (source.category_code<>'LOGISTICS' OR EXISTS(
                      SELECT 1 FROM logistics.route_event event
                      WHERE event.event_id::text=source.source_record_id
                        AND event.origin_region_code IN (:authorizedRegions)
                        AND event.destination_region_code IN (:authorizedRegions))))
                )
                SELECT child.code region_code,
                       child.name region_name,
                       child.administrative_level region_level,
                       COUNT(DISTINCT source.sample_point_id)
                         FILTER (WHERE source.point_approval_state='APPROVED') sample_point_count,
                       COUNT(*) FILTER (WHERE source.unresolved_reason IS NOT NULL) unresolved_source_count
                FROM children child
                JOIN descendants ON descendants.child_code=child.code
                LEFT JOIN scoped_source source ON source.effective_region_code=descendants.code
                WHERE :unrestricted OR EXISTS(
                  SELECT 1 FROM descendants authorized_descendant
                  WHERE authorized_descendant.child_code=child.code
                    AND authorized_descendant.code IN (:authorizedRegions))
                GROUP BY child.code,child.name,child.administrative_level,child.sort_order
                ORDER BY child.sort_order,child.code
                """).param("parent", parentCode)
                .param("unrestricted", unrestricted(authorizedRegionCodes))
                .param("authorizedRegions", authorizedRegionCodes)
                .query((row, index) -> new OverviewSamplePointAggregate(
                        row.getString("region_code"),
                        row.getString("region_name"),
                        row.getString("region_level"),
                        row.getLong("sample_point_count"),
                        row.getLong("unresolved_source_count")))
                .list();
    }

    @Override
    public OverviewSamplePointList list(String regionCode, String categoryCode, String typeCode,
            String query, Set<String> authorizedRegionCodes) {
        List<SourceRow> rows = sourceRows(regionCode, authorizedRegionCodes);
        List<SourceRow> eligible = rows.stream().filter(SourceRow::approvedPoint).toList();
        long unresolvedSourceCount = rows.stream().filter(row -> row.unresolvedReason() != null).count();
        Set<UUID> matchingIds = eligible.stream()
                .filter(row -> matchesFilter(row, categoryCode, typeCode, query))
                .map(SourceRow::samplePointId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, List<SourceRow>> byPoint = eligible.stream()
                .filter(row -> matchingIds.contains(row.samplePointId()))
                .collect(Collectors.groupingBy(SourceRow::samplePointId, LinkedHashMap::new, Collectors.toList()));
        List<OverviewSamplePointList.Item> items = byPoint.values().stream()
                .map(this::listItem)
                .sorted(Comparator.comparing(OverviewSamplePointList.Item::name)
                        .thenComparing(item -> item.samplePointId().toString()))
                .toList();
        return new OverviewSamplePointList(regionCode, matchingIds.size(), unresolvedSourceCount,
                categories(eligible), items);
    }

    @Override
    public List<OverviewSamplePointIcon> icons(String regionCode, String categoryCode, String typeCode,
            Set<String> authorizedRegionCodes) {
        Map<UUID, List<SourceRow>> byPoint = sourceRows(regionCode, authorizedRegionCodes).stream()
                .filter(SourceRow::approvedPoint)
                .filter(row -> row.unresolvedReason() == null && row.longitude() != null && row.latitude() != null)
                .filter(row -> row.categoryCode().equals(categoryCode))
                .filter(row -> typeCode == null || row.typeCode().equals(typeCode))
                .collect(Collectors.groupingBy(SourceRow::samplePointId, LinkedHashMap::new, Collectors.toList()));
        return byPoint.values().stream()
                .map(this::icon)
                .sorted(Comparator.comparing(OverviewSamplePointIcon::name)
                        .thenComparing(icon -> icon.samplePointId().toString()))
                .toList();
    }

    @Override
    public Optional<OverviewSamplePointDetail> detail(UUID samplePointId, String regionCode,
            Set<String> authorizedRegionCodes) {
        List<SourceRow> rows = sourceRows(regionCode, authorizedRegionCodes).stream()
                .filter(SourceRow::approvedPoint)
                .filter(row -> row.samplePointId().equals(samplePointId))
                .toList();
        if (rows.isEmpty()) return Optional.empty();
        SourceRow identity = rows.getFirst();
        List<OverviewSamplePointDetail.Association> associations = rows.stream()
                .map(row -> new OverviewSamplePointDetail.Association(
                        row.categoryCode(), row.categoryName(), row.sourceRole(),
                        row.typeCode(), row.typeName(), row.productCode(), row.productName(),
                        row.occurrenceDate(), row.sourceVersion(), businessValues(row)))
                .distinct()
                .sorted(Comparator.comparing(OverviewSamplePointDetail.Association::categoryCode)
                        .thenComparing(OverviewSamplePointDetail.Association::typeCode)
                        .thenComparing(OverviewSamplePointDetail.Association::productCode)
                        .thenComparing(OverviewSamplePointDetail.Association::occurrenceDate)
                        .thenComparing(OverviewSamplePointDetail.Association::sourceRole))
                .toList();
        return Optional.of(new OverviewSamplePointDetail(samplePointId, identity.canonicalName(),
                identity.governedRegionCode(), identity.governedRegionName(), identity.locationState(), associations));
    }

    private List<SourceRow> sourceRows(String regionCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE descendants(code) AS (
                  SELECT code FROM platform.region WHERE code=:region
                  UNION ALL
                  SELECT child.code FROM platform.region child
                  JOIN descendants parent ON child.parent_code=parent.code
                )
                SELECT source.sample_point_id,
                       source.category_code,
                       source.category_name,
                       source.source_record_id,
                       source.source_role,
                       source.product_code,
                       source.product_name,
                       source.occurrence_date,
                       source.source_version,
                       source.source_region_code,
                       source.governed_region_code,
                       source.governed_region_name,
                       source.type_code,
                       source.type_name,
                       source.type_sort_order,
                       source.canonical_name,
                       source.point_approval_state,
                       source.location_state,
                       source.unresolved_reason,
                       ST_X(source.point_geometry) longitude,
                       ST_Y(source.point_geometry) latitude
                FROM overview.sample_point_query_source source
                WHERE COALESCE(source.governed_region_code,source.source_region_code) IN (SELECT code FROM descendants)
                  AND (:unrestricted OR (
                    source.source_region_code IN (:authorizedRegions)
                    AND (source.governed_region_code IS NULL
                      OR source.governed_region_code IN (:authorizedRegions))
                    AND (source.category_code<>'LOGISTICS' OR EXISTS(
                      SELECT 1 FROM logistics.route_event event
                      WHERE event.event_id::text=source.source_record_id
                        AND event.origin_region_code IN (:authorizedRegions)
                        AND event.destination_region_code IN (:authorizedRegions)))))
                ORDER BY source.type_sort_order,source.canonical_name,source.sample_point_id,
                         source.product_code,source.source_role
                """).param("region", regionCode)
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
                        row.getString("canonical_name"),
                        row.getString("point_approval_state"),
                        row.getString("location_state"),
                        row.getString("unresolved_reason"),
                        row.getObject("longitude", Double.class),
                        row.getObject("latitude", Double.class)))
                .list();
    }

    private Map<String, OverviewSamplePointDetail.BusinessValue> businessValues(SourceRow row) {
        return switch (row.categoryCode()) {
            case "PRODUCTION" -> productionValues(row.sourceRecordId());
            case "MARKET" -> marketValues(row.sourceRecordId());
            case "LOGISTICS" -> logisticsValues(row.sourceRecordId());
            default -> Map.of();
        };
    }

    private Map<String, OverviewSamplePointDetail.BusinessValue> productionValues(String recordId) {
        return jdbc.sql("""
                SELECT record.cultivated_area_mu,
                       record.estimated_output_kg,
                       record.yield_per_mu_kg,
                       (SELECT value FROM production.production_record_submission_metadata metadata
                        WHERE metadata.record_id=record.record_id
                          AND metadata.field_code='PROD_SAMPLE_CONTACT') contact
                FROM production.production_record record
                WHERE record.record_id=:recordId
                """).param("recordId", recordId).query((row, index) -> {
                    Map<String, OverviewSamplePointDetail.BusinessValue> values = new LinkedHashMap<>();
                    put(values, "CONTACT", "联系方式", row.getString("contact"), null);
                    put(values, "CULTIVATED_AREA_MU", "种植面积",
                            decimal(row.getBigDecimal("cultivated_area_mu")), "亩");
                    put(values, "ESTIMATED_OUTPUT_KG", "总产量",
                            decimal(row.getBigDecimal("estimated_output_kg")), "千克");
                    put(values, "YIELD_PER_MU_KG", "单产",
                            decimal(row.getBigDecimal("yield_per_mu_kg")), "千克/亩");
                    return values;
                }).single();
    }

    private Map<String, OverviewSamplePointDetail.BusinessValue> marketValues(String recordId) {
        return jdbc.sql("""
                SELECT record.purchase_base_price,
                       (SELECT value FROM market.market_record_core_value value
                        WHERE value.record_id=record.record_id
                          AND value.field_code='MKT_SAMPLE_CONTACT') contact,
                       (SELECT value FROM market.market_record_fact fact
                        WHERE fact.record_id=record.record_id
                          AND fact.fact_code='OPENING_INVENTORY') opening_inventory,
                       (SELECT value FROM market.market_record_fact fact
                        WHERE fact.record_id=record.record_id
                          AND fact.fact_code='PURCHASE_VOLUME') purchase_volume
                FROM market.market_record record
                WHERE record.record_id=:recordId
                """).param("recordId", recordId).query((row, index) -> {
                    Map<String, OverviewSamplePointDetail.BusinessValue> values = new LinkedHashMap<>();
                    put(values, "CONTACT", "联系方式", row.getString("contact"), null);
                    put(values, "OPENING_INVENTORY_TONNES", "期初库存",
                            decimal(row.getBigDecimal("opening_inventory")), "吨");
                    put(values, "PURCHASE_PRICE", "收购价格",
                            decimal(row.getBigDecimal("purchase_base_price")), "元/吨");
                    put(values, "PURCHASE_VOLUME_TONNES", "收购量",
                            decimal(row.getBigDecimal("purchase_volume")), "吨");
                    return values;
                }).single();
    }

    private Map<String, OverviewSamplePointDetail.BusinessValue> logisticsValues(String eventId) {
        return jdbc.sql("""
                SELECT event.source_organization,
                       fact.value route_volume,
                       fact.unit_code route_volume_unit
                FROM logistics.route_event event
                LEFT JOIN logistics.route_fact fact
                  ON fact.event_id=event.event_id AND fact.fact_code='ROUTE_VOLUME'
                WHERE event.event_id=CAST(:eventId AS uuid)
                """).param("eventId", eventId).query((row, index) -> {
                    Map<String, OverviewSamplePointDetail.BusinessValue> values = new LinkedHashMap<>();
                    put(values, "SOURCE_ORGANIZATION", "来源单位",
                            row.getString("source_organization"), null);
                    put(values, "THROUGHPUT", "当前统计期间吞吐量",
                            decimal(row.getBigDecimal("route_volume")), row.getString("route_volume_unit"));
                    return values;
                }).single();
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

    private List<OverviewSamplePointList.Category> categories(List<SourceRow> eligible) {
        Map<String, List<TypeDefinition>> formalTypes = formalTypes().stream()
                .collect(Collectors.groupingBy(TypeDefinition::categoryCode, LinkedHashMap::new, Collectors.toList()));
        List<OverviewSamplePointList.Category> result = new ArrayList<>();
        for (CategoryDefinition category : CATEGORIES) {
            Set<UUID> categoryPoints = distinctPointIds(eligible,
                    row -> row.categoryCode().equals(category.code()));
            List<OverviewSamplePointList.Type> types = formalTypes.getOrDefault(category.code(), List.of()).stream()
                    .map(type -> new OverviewSamplePointList.Type(type.code(), type.name(),
                            distinctPointIds(eligible, row -> row.categoryCode().equals(category.code())
                                    && row.typeCode().equals(type.code())).size()))
                    .toList();
            result.add(new OverviewSamplePointList.Category(
                    category.code(), category.name(), categoryPoints.size(), types));
        }
        return result;
    }

    private List<TypeDefinition> formalTypes() {
        return jdbc.sql("""
                SELECT business_domain,code,name,sort_order
                FROM platform.object_type
                WHERE business_domain IN ('PRODUCTION','MARKET','LOGISTICS')
                ORDER BY sort_order,code
                """).query((row, index) -> new TypeDefinition(
                        row.getString("business_domain"), row.getString("code"),
                        row.getString("name"), row.getInt("sort_order")))
                .list();
    }

    private OverviewSamplePointList.Item listItem(List<SourceRow> rows) {
        SourceRow identity = rows.getFirst();
        List<OverviewSamplePointList.CategoryRef> categories = distinct(rows,
                SourceRow::categoryCode,
                row -> new OverviewSamplePointList.CategoryRef(row.categoryCode(), row.categoryName()));
        List<OverviewSamplePointList.TypeRef> types = distinct(rows,
                SourceRow::typeCode,
                row -> new OverviewSamplePointList.TypeRef(row.typeCode(), row.typeName()));
        List<OverviewSamplePointList.ProductRef> products = distinct(rows,
                SourceRow::productCode,
                row -> new OverviewSamplePointList.ProductRef(row.productCode(), row.productName()));
        return new OverviewSamplePointList.Item(identity.samplePointId(), identity.canonicalName(),
                identity.governedRegionCode(), identity.governedRegionName(), identity.locationState(),
                categories, types, products);
    }

    private OverviewSamplePointIcon icon(List<SourceRow> rows) {
        SourceRow identity = rows.getFirst();
        List<OverviewSamplePointIcon.TypeRef> types = distinct(rows,
                SourceRow::typeCode,
                row -> new OverviewSamplePointIcon.TypeRef(row.typeCode(), row.typeName()));
        return new OverviewSamplePointIcon(identity.samplePointId(), identity.canonicalName(), types,
                identity.longitude(), identity.latitude());
    }

    private static boolean matchesFilter(SourceRow row, String categoryCode, String typeCode, String query) {
        if (categoryCode != null && !row.categoryCode().equals(categoryCode)) return false;
        if (typeCode != null && !row.typeCode().equals(typeCode)) return false;
        if (query == null) return true;
        return row.canonicalName().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static Set<UUID> distinctPointIds(List<SourceRow> rows,
            java.util.function.Predicate<SourceRow> predicate) {
        return rows.stream().filter(predicate).map(SourceRow::samplePointId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
    private record TypeDefinition(String categoryCode, String code, String name, int sortOrder) {}

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
            String canonicalName,
            String pointApprovalState,
            String locationState,
            String unresolvedReason,
            Double longitude,
            Double latitude) {
        boolean approvedPoint() {
            return samplePointId != null && "APPROVED".equals(pointApprovalState);
        }
    }
}
