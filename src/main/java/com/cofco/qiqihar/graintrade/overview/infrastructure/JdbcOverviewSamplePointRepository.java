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
            int year, String parentCode, Set<String> authorizedRegionCodes) {
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
            EntityProjection projection = projection(year, region.code(), null, null, null,
                    authorizedRegionCodes);
            long quality = projection.entities().stream()
                    .filter(entity -> entity.dataQualityReason() != null).count();
            long valid = projection.entities().size() - quality;
            long corrections = projection.corrections().size();
            return new OverviewSamplePointAggregate(region.code(), region.name(), region.level(),
                    projection.entities().size(), valid, quality, corrections, quality + corrections);
        }).toList();
    }

    @Override
    public OverviewSamplePointList list(int year, String regionCode, String categoryCode, String typeCode,
            String query, Set<String> authorizedRegionCodes) {
        EntityProjection projection = projection(year, regionCode, categoryCode, typeCode, query,
                authorizedRegionCodes);
        List<OverviewSamplePointList.Item> items = projection.entities().stream()
                .map(this::listItem)
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
        return new OverviewSamplePointList(regionCode, items.size(), valid, quality, corrections.size(),
                quality + corrections.size(), categories(projection.entities()), items, corrections);
    }

    @Override
    public List<OverviewSamplePointIcon> icons(int year, String regionCode, String categoryCode, String typeCode,
            String query, Set<String> authorizedRegionCodes) {
        return projection(year, regionCode, categoryCode, typeCode, query, authorizedRegionCodes)
                .entities().stream()
                .filter(entity -> entity.dataQualityReason() == null)
                .map(this::icon)
                .sorted(Comparator.comparing(OverviewSamplePointIcon::name)
                        .thenComparing(icon -> icon.samplePointId().toString()))
                .toList();
    }

    @Override
    public Optional<OverviewSamplePointDetail> detail(int year, UUID samplePointId, String regionCode,
            String categoryCode, String typeCode, Set<String> authorizedRegionCodes) {
        Optional<Entity> selected = projection(year, regionCode, categoryCode, typeCode, null,
                authorizedRegionCodes).entities().stream()
                .filter(entity -> entity.samplePointId().equals(samplePointId)).findFirst();
        if (selected.isEmpty()) return Optional.empty();
        Entity entity = selected.get();
        List<SourceRow> rows = entity.rows().stream()
                .filter(row -> matchesFilter(row, categoryCode, typeCode, null)).toList();
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
                identity.governedRegionCode(), identity.governedRegionName(), identity.locationState(),
                entity.dataQualityReason(), associations));
    }

    private List<SourceRow> sourceRows(int year, String regionCode, Set<String> authorizedRegionCodes) {
        return jdbc.sql("""
                WITH RECURSIVE descendants(code) AS (
                  SELECT code FROM platform.region WHERE code=:region
                  UNION ALL
                  SELECT child.code FROM platform.region child
                  JOIN descendants parent ON child.parent_code=parent.code
                )
                SELECT CASE WHEN source.point_approval_state='APPROVED'
                         THEN source.sample_point_id END sample_point_id,
                       source.category_code,
                       source.category_name,
                       source.source_record_id,
                       source.source_role,
                       source.product_code,
                       source.product_name,
                       source.occurrence_date,
                       source.source_version,
                       source.source_region_code,
                       CASE WHEN source.point_approval_state='APPROVED'
                         THEN source.governed_region_code END governed_region_code,
                       CASE WHEN source.point_approval_state='APPROVED'
                         THEN source.governed_region_name END governed_region_name,
                       source.type_code,
                       source.type_name,
                       source.type_sort_order,
                       visible_type.overview_icon_key,
                       CASE WHEN source.point_approval_state='APPROVED'
                         THEN source.canonical_name END canonical_name,
                       CASE WHEN source.point_approval_state='APPROVED'
                         THEN source.point_approval_state END point_approval_state,
                       CASE WHEN source.point_approval_state='APPROVED'
                         THEN source.location_state END location_state,
                       source.unresolved_reason,
                       ST_X(source.point_geometry) longitude,
                       ST_Y(source.point_geometry) latitude,
                       COALESCE(point.coordinate_shared_verified,false) coordinate_shared_verified
                FROM overview.sample_point_query_source source
                JOIN platform.object_type visible_type
                  ON visible_type.business_domain=source.category_code
                 AND visible_type.code=source.type_code
                 AND visible_type.overview_enabled
                LEFT JOIN registry.sample_point point ON point.sample_point_id=source.sample_point_id
                WHERE (
                    source.category_code='PRODUCTION' AND EXISTS(
                      SELECT 1 FROM production.production_record record
                      WHERE record.record_id=source.source_record_id
                        AND record.survey_year=:year
                        AND record.survey_period_governance_state='CONFIRMED')
                    OR source.category_code='MARKET' AND EXISTS(
                      SELECT 1 FROM market.market_record record
                      WHERE record.record_id=source.source_record_id
                        AND record.survey_year=:year
                        AND record.survey_period_governance_state='CONFIRMED'))
                  AND COALESCE(
                        CASE WHEN source.point_approval_state='APPROVED'
                          THEN source.governed_region_code END,
                        source.source_region_code) IN (SELECT code FROM descendants)
                  AND (:unrestricted OR (
                    source.source_region_code IN (:authorizedRegions)
                    AND (source.point_approval_state IS DISTINCT FROM 'APPROVED'
                      OR source.governed_region_code IS NULL
                      OR source.governed_region_code IN (:authorizedRegions))))
                ORDER BY source.type_sort_order,source.canonical_name,source.sample_point_id,
                         source.product_code,source.source_role
                """).param("year", year).param("region", regionCode)
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

    private static void put(Map<String, OverviewSamplePointDetail.BusinessValue> values,
            String code, String label, String value, String unitCode) {
        if (value != null && !value.isBlank()) {
            values.put(code, new OverviewSamplePointDetail.BusinessValue(label, value, unitCode));
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private EntityProjection projection(int year, String regionCode, String categoryCode,
            String typeCode, String query, Set<String> authorizedRegionCodes) {
        List<SourceRow> rows = sourceRows(year, regionCode, authorizedRegionCodes);
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

    private OverviewSamplePointList.Item listItem(Entity entity) {
        List<SourceRow> rows = entity.rows();
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
        return new OverviewSamplePointList.Item(identity.samplePointId(), identity.canonicalName(),
                identity.governedRegionCode(), identity.governedRegionName(), identity.locationState(),
                entity.dataQualityReason(), categories, types, products);
    }

    private OverviewSamplePointIcon icon(Entity entity) {
        List<SourceRow> rows = entity.rows();
        SourceRow identity = rows.getFirst();
        List<OverviewSamplePointIcon.TypeRef> types = distinct(rows,
                SourceRow::typeCode,
                row -> new OverviewSamplePointIcon.TypeRef(row.typeCode(), row.typeName(), row.iconKey()));
        return new OverviewSamplePointIcon(identity.samplePointId(), identity.canonicalName(),
                entity.iconKey(), types,
                identity.longitude(), identity.latitude());
    }

    private static boolean matchesFilter(SourceRow row, String categoryCode, String typeCode, String query) {
        if (categoryCode != null && !row.categoryCode().equals(categoryCode)) return false;
        if (typeCode != null && !row.typeCode().equals(typeCode)) return false;
        if (query == null) return true;
        return row.canonicalName() != null
                && row.canonicalName().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
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
            Double longitude,
            Double latitude,
            boolean coordinateSharedVerified) {
        boolean approvedPoint() {
            return samplePointId != null && "APPROVED".equals(pointApprovalState);
        }
    }
}
