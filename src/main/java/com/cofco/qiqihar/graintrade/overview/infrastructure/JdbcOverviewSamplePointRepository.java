package com.cofco.qiqihar.graintrade.overview.infrastructure;

import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointAggregate;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointDetail;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointExportRow;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointIcon;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointList;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointRepository;
import com.cofco.qiqihar.graintrade.overview.application.OverviewSamplePointSnapshot;
import com.cofco.qiqihar.graintrade.overview.api.CurrentOverviewSamplePoint;
import com.cofco.qiqihar.graintrade.overview.api.CurrentOverviewSamplePointReader;
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
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOverviewSamplePointRepository
        implements OverviewSamplePointRepository, CurrentOverviewSamplePointReader {
    private static final List<CategoryDefinition> CATEGORIES = List.of(
            new CategoryDefinition("PRODUCTION", "产情类", "production"),
            new CategoryDefinition("MARKET", "市场类", "market"),
            new CategoryDefinition("LOGISTICS", "物流类", "logistics"));
    private static final Map<String, List<String>> SUMMARY_FIELDS = Map.of(
            "PRODUCTION", List.of(
                    "SAMPLE_CONTACT", "SURVEYOR_NAME", "SURVEYOR_PHONE",
                    "CULTIVATED_AREA_MU", "ESTIMATED_OUTPUT_KG", "YIELD_PER_MU_KG"),
            "MARKET", List.of(
                    "SAMPLE_CONTACT", "SURVEYOR_NAME", "SURVEYOR_PHONE",
                    "PURCHASE_PRICE", "SALE_PRICE", "PURCHASE_VOLUME", "SALES_VOLUME"),
            "LOGISTICS", List.of(
                    "SOURCE_ORGANIZATION", "REPORTER", "ORIGIN_NODE", "DESTINATION_NODE",
                    "TRANSPORT_MODE", "DIRECTION", "ROUTE_VOLUME", "FREIGHT_RATE",
                    "TRANSIT_TIME", "BOARD_PRICE"));

    private final JdbcClient jdbc;
    private final Map<ProjectionCacheKey, CompletableFuture<EntityProjection>> baseProjectionInFlight =
            new ConcurrentHashMap<>();

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
    @Transactional(readOnly = true)
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
                    AND descendants.code IN (SELECT unnest(string_to_array(:authorizedRegionList,','))))
                ORDER BY child.sort_order,child.code
                """).param("parent", parentCode)
                .param("unrestricted", unrestricted(authorizedRegionCodes))
                .param("authorizedRegionList", authorizedRegionList(authorizedRegionCodes))
                .query((row, index) -> new AggregateRegion(
                        row.getString("code"), row.getString("name"),
                        row.getString("administrative_level")))
                .list();
        if (parentCode == null) {
            EntityProjection overall = projection(
                    year, productCode, null, null, null, null, authorizedRegionCodes);
            Map<String, String> rootByDescendant = businessRootByDescendant(
                    regions, authorizedRegionCodes);
            Map<String, List<Entity>> entitiesByRoot = regions.stream().collect(Collectors.toMap(
                    AggregateRegion::code, ignored -> new ArrayList<>(),
                    (left, right) -> left, LinkedHashMap::new));
            Map<String, List<SourceRow>> correctionsByRoot = regions.stream().collect(Collectors.toMap(
                    AggregateRegion::code, ignored -> new ArrayList<>(),
                    (left, right) -> left, LinkedHashMap::new));
            overall.entities().forEach(entity -> {
                String root = rootByDescendant.get(
                        entity.rows().getFirst().governedRegionCode());
                if (entitiesByRoot.containsKey(root)) entitiesByRoot.get(root).add(entity);
            });
            overall.corrections().forEach(row -> {
                String region = row.governedRegionCode() == null
                        ? row.sourceRegionCode() : row.governedRegionCode();
                String root = rootByDescendant.get(region);
                if (correctionsByRoot.containsKey(root)) correctionsByRoot.get(root).add(row);
            });
            return regions.stream().map(region -> aggregate(
                    region, "CHILD_REGION", region.code(),
                    new EntityProjection(
                            entitiesByRoot.get(region.code()),
                            correctionsByRoot.get(region.code()))))
                    .toList();
        }

        AggregateRegion parent = jdbc.sql("""
                SELECT code,name,administrative_level
                FROM platform.region WHERE code=:code
                """).param("code", parentCode).query((row, index) -> new AggregateRegion(
                        row.getString("code"), row.getString("name"),
                        row.getString("administrative_level"))).single();
        Map<String, String> childByDescendant = directChildByDescendant(parentCode, regions);
        EntityProjection parentProjection = projection(
                year, productCode, parentCode, null, null, null, authorizedRegionCodes);
        Map<String, List<Entity>> entitiesByChild = regions.stream().collect(Collectors.toMap(
                AggregateRegion::code, ignored -> new ArrayList<>(),
                (left, right) -> left, LinkedHashMap::new));
        Map<String, List<SourceRow>> correctionsByChild = regions.stream().collect(Collectors.toMap(
                AggregateRegion::code, ignored -> new ArrayList<>(),
                (left, right) -> left, LinkedHashMap::new));
        List<Entity> directEntities = new ArrayList<>();
        List<SourceRow> directCorrections = new ArrayList<>();
        Map<UUID, String> childByValidatedCoordinate = uniqueContainingRegionByEntity(
                parentProjection.entities().stream()
                        .filter(entity -> entity.dataQualityReason() == null)
                        .toList(),
                regions.stream().map(AggregateRegion::code).toList());
        Map<UUID, Entity> entityById = parentProjection.entities().stream().collect(Collectors.toMap(
                Entity::samplePointId, Function.identity(), (left, right) -> left));
        childByValidatedCoordinate.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue, LinkedHashMap::new,
                        Collectors.mapping(entry -> entityById.get(entry.getKey()), Collectors.toList())))
                .forEach((childCode, candidateEntities) -> {
                    Set<UUID> assigned = entitiesAssignedAlongPublishedHierarchy(
                            candidateEntities, childCode);
                    candidateEntities.stream()
                            .map(Entity::samplePointId)
                            .filter(id -> !assigned.contains(id))
                            .forEach(childByValidatedCoordinate::remove);
                });
        parentProjection.entities().forEach(entity -> {
            String childCode = childByDescendant.get(entity.rows().getFirst().governedRegionCode());
            if (childCode == null) childCode = childByValidatedCoordinate.get(entity.samplePointId());
            if (childCode == null) directEntities.add(entity);
            else entitiesByChild.get(childCode).add(entity);
        });
        parentProjection.corrections().forEach(row -> {
            String governedOrSourceRegion = row.governedRegionCode() == null
                    ? row.sourceRegionCode() : row.governedRegionCode();
            String childCode = childByDescendant.get(governedOrSourceRegion);
            if (childCode == null) directCorrections.add(row);
            else correctionsByChild.get(childCode).add(row);
        });

        List<OverviewSamplePointAggregate> result = new ArrayList<>();
        regions.forEach(region -> result.add(aggregate(
                region, "CHILD_REGION", region.code(),
                new EntityProjection(entitiesByChild.get(region.code()),
                        correctionsByChild.get(region.code())))));
        if (!currentOverviewEntities(directEntities).isEmpty()) {
            result.add(aggregate(
                    new AggregateRegion(parent.code(), "本级样本", parent.level()),
                    "PARENT_DIRECT", parent.code(),
                    new EntityProjection(directEntities, directCorrections)));
        }
        return List.copyOf(result);
    }

    private Map<String, String> directChildByDescendant(
            String parentCode, List<AggregateRegion> visibleChildren) {
        if (visibleChildren.isEmpty()) return Map.of();
        Set<String> visibleChildCodes = visibleChildren.stream()
                .map(AggregateRegion::code).collect(Collectors.toSet());
        return jdbc.sql("""
                WITH RECURSIVE descendants(child_code,code) AS (
                  SELECT code,code FROM platform.region WHERE parent_code=:parent
                  UNION ALL
                  SELECT descendants.child_code,region.code
                  FROM platform.region region
                  JOIN descendants ON region.parent_code=descendants.code
                )
                SELECT child_code,code FROM descendants
                """).param("parent", parentCode)
                .query((row, index) -> Map.entry(
                        row.getString("code"), row.getString("child_code")))
                .list().stream()
                .filter(entry -> visibleChildCodes.contains(entry.getValue()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, String> businessRootByDescendant(
            List<AggregateRegion> businessRoots, Set<String> authorizedRegionCodes) {
        if (businessRoots.isEmpty()) return Map.of();
        return jdbc.sql("""
                WITH RECURSIVE rooted(region_code,root_code) AS (
                  SELECT code,code FROM platform.region WHERE code IN (:rootCodes)
                  UNION ALL
                  SELECT child.code,parent.root_code
                  FROM platform.region child
                  JOIN rooted parent ON child.parent_code=parent.region_code
                )
                SELECT region_code,root_code FROM rooted
                WHERE :unrestricted OR region_code IN (
                  SELECT unnest(string_to_array(:authorizedRegionList,',')))
                """).param("rootCodes", businessRoots.stream()
                        .map(AggregateRegion::code).toList())
                .param("unrestricted", unrestricted(authorizedRegionCodes))
                .param("authorizedRegionList", authorizedRegionList(authorizedRegionCodes))
                .query((row, index) -> Map.entry(
                        row.getString("region_code"), row.getString("root_code")))
                .list().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
    }

    private Map<UUID, String> uniqueContainingRegionByEntity(
            List<Entity> entities, List<String> regionCodes) {
        if (entities.isEmpty() || regionCodes.isEmpty()) return Map.of();
        StringJoiner candidates = new StringJoiner(",");
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int index = 0; index < entities.size(); index += 1) {
            Entity entity = entities.get(index);
            String suffix = Integer.toString(index);
            candidates.add("(CAST(:id" + suffix + " AS uuid),:longitude" + suffix
                    + ",:latitude" + suffix + ")");
            parameters.put("id" + suffix, entity.samplePointId());
            parameters.put("longitude" + suffix, entity.coordinate().longitude());
            parameters.put("latitude" + suffix, entity.coordinate().latitude());
        }
        return jdbc.sql("""
                WITH candidate(sample_point_id,longitude,latitude) AS (VALUES %s),
                containing_region AS (
                  SELECT candidate.sample_point_id,published_boundary.region_code
                  FROM candidate
                  JOIN overview.administrative_boundary_render published_boundary
                    ON published_boundary.region_code IN (:regionCodes)
                  WHERE ST_Covers(published_boundary.geometry,
                          ST_SetSRID(ST_MakePoint(candidate.longitude,candidate.latitude),4326))
                )
                SELECT sample_point_id,min(region_code) region_code
                FROM containing_region
                GROUP BY sample_point_id
                HAVING count(DISTINCT region_code)=1
                """.formatted(candidates))
                .params(parameters)
                .param("regionCodes", regionCodes)
                .query((row, index) -> Map.entry(
                        row.getObject("sample_point_id", UUID.class),
                        row.getString("region_code")))
                .list().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
    }

    private Set<UUID> entitiesAssignedAlongPublishedHierarchy(
            List<Entity> entities, String requestedRegionCode) {
        if (entities.isEmpty()) return Set.of();
        StringJoiner candidates = new StringJoiner(",");
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int index = 0; index < entities.size(); index += 1) {
            Entity entity = entities.get(index);
            String suffix = Integer.toString(index);
            candidates.add("(CAST(:id" + suffix + " AS uuid),:region" + suffix
                    + ",:longitude" + suffix + ",:latitude" + suffix + ")");
            parameters.put("id" + suffix, entity.samplePointId());
            parameters.put("region" + suffix, entity.rows().getFirst().governedRegionCode());
            parameters.put("longitude" + suffix, entity.coordinate().longitude());
            parameters.put("latitude" + suffix, entity.coordinate().latitude());
        }
        return jdbc.sql("""
                WITH RECURSIVE target_lineage(region_code,parent_code,depth) AS (
                  SELECT code,parent_code,0 FROM platform.region WHERE code=:requestedRegion
                  UNION ALL
                  SELECT parent.code,parent.parent_code,child.depth+1
                  FROM platform.region parent
                  JOIN target_lineage child ON child.parent_code=parent.code
                ), candidate(sample_point_id,governed_region_code,longitude,latitude) AS (
                  VALUES %s
                ), eligible AS (
                  SELECT candidate.*,governed.depth governed_depth
                  FROM candidate
                  JOIN target_lineage governed
                    ON governed.region_code=candidate.governed_region_code
                  WHERE governed.depth>0
                ), required_link AS (
                  SELECT eligible.sample_point_id,eligible.governed_depth,
                         link.region_code,link.parent_code,
                         eligible.longitude,eligible.latitude
                  FROM eligible
                  JOIN target_lineage link ON link.depth<eligible.governed_depth
                ), evaluated_link AS (
                  SELECT required_link.sample_point_id,required_link.governed_depth,
                         required_link.region_code,
                         count(DISTINCT CASE WHEN published_boundary.region_code IS NOT NULL
                           THEN sibling.code END) containing_sibling_count,
                         bool_or(sibling.code=required_link.region_code
                           AND published_boundary.region_code IS NOT NULL) requested_child_contains
                  FROM required_link
                  JOIN platform.region sibling ON sibling.parent_code=required_link.parent_code
                  LEFT JOIN overview.administrative_boundary_render published_boundary
                    ON published_boundary.region_code=sibling.code
                   AND ST_Covers(published_boundary.geometry,
                     ST_SetSRID(ST_MakePoint(
                       required_link.longitude,required_link.latitude),4326))
                  GROUP BY required_link.sample_point_id,required_link.governed_depth,
                           required_link.region_code
                )
                SELECT sample_point_id
                FROM evaluated_link
                GROUP BY sample_point_id,governed_depth
                HAVING count(*) FILTER (WHERE containing_sibling_count=1
                  AND requested_child_contains)=governed_depth
                """.formatted(candidates))
                .params(parameters)
                .param("requestedRegion", requestedRegionCode)
                .query((row, index) -> row.getObject("sample_point_id", UUID.class))
                .list().stream().collect(Collectors.toSet());
    }

    private OverviewSamplePointAggregate aggregate(
            AggregateRegion region, String scopeKind, String anchorRegionCode,
            EntityProjection projection) {
        List<Entity> currentEntities = currentOverviewEntities(projection.entities());
        long productionCount = categoryPointCount(currentEntities, "PRODUCTION");
        long marketCount = categoryPointCount(currentEntities, "MARKET");
        long logisticsCount = categoryPointCount(currentEntities, "LOGISTICS");
        return new OverviewSamplePointAggregate(
                region.code(), region.name(), region.level(), scopeKind, anchorRegionCode,
                currentEntities.size(), productionCount, marketCount, logisticsCount,
                currentEntities.size(), 0, 0, 0);
    }

    private static List<Entity> currentOverviewEntities(List<Entity> entities) {
        return entities.stream().filter(entity -> entity.dataQualityReason() == null).toList();
    }

    private static long categoryPointCount(List<Entity> entities, String categoryCode) {
        return entities.stream().filter(entity -> entity.rows().stream()
                .anyMatch(row -> row.categoryCode().equals(categoryCode))).count();
    }

    @Override
    @Transactional(readOnly = true)
    public OverviewSamplePointList list(int year, String productCode, String regionCode,
            String categoryCode, String typeCode, String query, Set<String> authorizedRegionCodes) {
        CompletableFuture<Map<UUID, StableIdentityRefs>> identityRefs = stableIdentityRefsAsync(year);
        EntityProjection projection = projection(year, productCode, regionCode, categoryCode, typeCode, query,
                authorizedRegionCodes, false);
        EntityProjection catalogProjection = categoryCode == null && typeCode == null && query == null
                ? projection
                : projection(year, productCode, regionCode, null, null, null,
                        authorizedRegionCodes, false);
        Map<UUID, StableIdentityRefs> stableIdentityRefs = identityRefs.join();
        return listFromProjection(
                regionCode, productCode, categoryCode, typeCode, projection, catalogProjection,
                stableIdentityRefs);
    }

    private OverviewSamplePointList listFromProjection(
            String regionCode, String productCode, String categoryCode, String typeCode,
            EntityProjection projection, EntityProjection catalogProjection,
            Map<UUID, StableIdentityRefs> stableIdentityRefs) {
        List<Entity> currentEntities = currentOverviewEntities(projection.entities());
        List<Entity> currentCatalogEntities = currentOverviewEntities(catalogProjection.entities());
        Map<UUID, SourceRow> latestByPoint = currentEntities.stream().collect(Collectors.toMap(
                Entity::samplePointId,
                entity -> latestBusinessRow(entity, productCode, categoryCode, typeCode),
                (left, right) -> left, LinkedHashMap::new));
        Map<SummaryKey, Map<String, OverviewSamplePointDetail.BusinessValue>> summaries =
                summaryValues(latestByPoint.values().stream()
                        .filter(java.util.Objects::nonNull).toList());
        List<OverviewSamplePointList.Item> items = currentEntities.stream()
                .map(entity -> listItem(entity, productCode, categoryCode, typeCode,
                        latestByPoint.get(entity.samplePointId()), summaries,
                        stableIdentityRefs.getOrDefault(
                                entity.samplePointId(), StableIdentityRefs.from(entity.rows()))))
                .sorted(Comparator.comparing(OverviewSamplePointList.Item::name)
                        .thenComparing(item -> item.samplePointId().toString()))
                .toList();
        return new OverviewSamplePointList(regionCode, items.size(), items.size(), 0, 0,
                0, categories(currentCatalogEntities, productCode, stableIdentityRefs), items, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OverviewSamplePointIcon> icons(int year, String productCode, String regionCode,
            String categoryCode, String typeCode, String query, Set<String> authorizedRegionCodes) {
        return iconsFromProjection(projection(
                year, productCode, regionCode, categoryCode, typeCode, query, authorizedRegionCodes),
                productCode);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CurrentOverviewSamplePoint> read(
            int year, String productCode, String regionCode, String categoryCode,
            Set<String> authorizedRegionCodes) {
        return icons(year, productCode, regionCode, categoryCode, null, null, authorizedRegionCodes)
                .stream()
                .map(icon -> new CurrentOverviewSamplePoint(
                        icon.samplePointId(), icon.longitude(), icon.latitude()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OverviewSamplePointSnapshot snapshot(int year, String productCode, String regionCode,
            String categoryCode, String typeCode, String query, Set<String> authorizedRegionCodes) {
        CompletableFuture<Map<UUID, StableIdentityRefs>> identityRefs = stableIdentityRefsAsync(year);
        EntityProjection projection = projection(
                year, productCode, regionCode, categoryCode, typeCode, query,
                authorizedRegionCodes, false);
        EntityProjection catalogProjection = categoryCode == null && typeCode == null && query == null
                ? projection
                : projection(year, productCode, regionCode, null, null, null,
                        authorizedRegionCodes, false);
        Map<UUID, StableIdentityRefs> stableIdentityRefs = identityRefs.join();
        return new OverviewSamplePointSnapshot(
                listFromProjection(regionCode, productCode, categoryCode, typeCode,
                        projection, catalogProjection, stableIdentityRefs),
                iconsFromProjection(projection, productCode));
    }

    private CompletableFuture<Map<UUID, StableIdentityRefs>> stableIdentityRefsAsync(int year) {
        return CompletableFuture.supplyAsync(
                () -> stableIdentityRefs(year),
                command -> Thread.ofVirtual().name("overview-stable-identities").start(command));
    }

    private Map<UUID, StableIdentityRefs> stableIdentityRefs(int year) {
        List<StableIdentityAssociation> associations = jdbc.sql("""
                SELECT DISTINCT point.sample_point_id,source.category_code,
                       source.category_name,source.product_code,source.product_name
                FROM overview.current_sample_point_query_source(
                  :year,''::varchar,true,false) source
                LEFT JOIN registry.current_sample_subject_resolution resolution
                  ON resolution.source_domain=source.category_code
                 AND resolution.source_record_id=source.source_record_id
                JOIN registry.sample_point point ON point.sample_point_id=COALESCE(
                  resolution.target_sample_point_id,source.sample_point_id)
                WHERE source.sample_point_id IS NOT NULL
                  AND resolution.resolution_action IS DISTINCT FROM 'VOID'
                  AND point.approval_state='APPROVED'
                  AND point.location_state='VALID'
                ORDER BY point.sample_point_id,source.category_code,source.product_code
                """).param("year", year)
                .query((row, ignored) -> new StableIdentityAssociation(
                        row.getObject("sample_point_id", UUID.class),
                        new OverviewSamplePointList.CategoryRef(
                                row.getString("category_code"), row.getString("category_name")),
                        new OverviewSamplePointList.ProductRef(
                                row.getString("product_code"), row.getString("product_name"))))
                .list();
        Map<UUID, List<StableIdentityAssociation>> byPoint = associations.stream()
                .collect(Collectors.groupingBy(StableIdentityAssociation::samplePointId,
                        LinkedHashMap::new, Collectors.toList()));
        return byPoint.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new StableIdentityRefs(
                        entry.getValue().stream().map(StableIdentityAssociation::category)
                                .distinct().toList(),
                        entry.getValue().stream().map(StableIdentityAssociation::product)
                                .distinct().toList()),
                (left, right) -> left,
                LinkedHashMap::new));
    }

    private List<OverviewSamplePointIcon> iconsFromProjection(
            EntityProjection projection, String productCode) {
        return projection.entities().stream()
                .filter(entity -> entity.dataQualityReason() == null)
                .map(entity -> icon(entity, productCode))
                .sorted(Comparator.comparing(OverviewSamplePointIcon::name)
                        .thenComparing(icon -> icon.samplePointId().toString()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OverviewSamplePointDetail> detail(int year, String productCode, UUID samplePointId,
            String regionCode, String categoryCode, String typeCode, Set<String> authorizedRegionCodes) {
        Optional<Entity> selected = projection(year, productCode, regionCode, categoryCode, typeCode, null,
                authorizedRegionCodes).entities().stream()
                .filter(entity -> entity.dataQualityReason() == null)
                .filter(entity -> entity.samplePointId().equals(samplePointId)).findFirst();
        if (selected.isEmpty()) return Optional.empty();
        Entity entity = selected.get();
        List<SourceRow> rows = latestRowsPerMonth(sourceRows(
                year, productCode, regionCode, authorizedRegionCodes, true).stream()
                .filter(SourceRow::approvedPoint)
                .filter(row -> samplePointId.equals(row.samplePointId()))
                .filter(row -> row.productCode().equals(productCode))
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
        List<OverviewSamplePointDetail.RoleRef> roles = roles(entity.rows()).stream()
                .map(role -> new OverviewSamplePointDetail.RoleRef(role.code(), role.name(), role.iconKey()))
                .toList();
        return Optional.of(new OverviewSamplePointDetail(samplePointId, identity.canonicalName(),
                identity.governedRegionCode(), identity.governedRegionName(), identity.locationState(),
                entity.dataQualityReason(), roles, associations));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OverviewSamplePointExportRow> exportRows(
            int year, String regionCode, Set<String> authorizedRegionCodes) {
        Map<UUID, List<SourceRow>> byPoint = sourceRows(
                year, null, regionCode, authorizedRegionCodes, false, List.of(), true).stream()
                .filter(SourceRow::approvedPoint)
                .collect(Collectors.groupingBy(
                        SourceRow::samplePointId, LinkedHashMap::new, Collectors.toList()));
        return byPoint.values().stream().map(pointRows -> {
            SourceRow identity = pointRows.getFirst();
            Coordinate coordinate = Coordinate.stable(identity);
            return new OverviewSamplePointExportRow(
                    identity.samplePointId(), identity.canonicalName(),
                    identity.governedRegionCode(), identity.governedRegionName(),
                    distinctText(pointRows, JdbcOverviewSamplePointRepository::exportCategoryName),
                    distinctText(pointRows, SourceRow::typeName),
                    distinctText(pointRows, SourceRow::productName),
                    pointRows.stream().map(SourceRow::sampleContact)
                            .filter(value -> value != null && !value.isBlank()).distinct().toList(),
                    coordinate.longitude(), coordinate.latitude());
        }).sorted(Comparator.comparing(OverviewSamplePointExportRow::regionName)
                .thenComparing(OverviewSamplePointExportRow::name)
                .thenComparing(row -> row.samplePointId().toString())).toList();
    }

    private static List<String> distinctText(
            List<SourceRow> rows, Function<SourceRow, String> mapper) {
        return rows.stream().map(mapper).filter(value -> value != null && !value.isBlank())
                .distinct().toList();
    }

    private static String exportCategoryName(SourceRow row) {
        return switch (row.categoryCode()) {
            case "PRODUCTION" -> "产情类";
            case "MARKET" -> "市场类";
            case "LOGISTICS" -> "物流类";
            default -> row.categoryName();
        };
    }

    private List<SourceRow> sourceRows(
            int year, String productCode, String regionCode, Set<String> authorizedRegionCodes,
            boolean includePeriodHistory) {
        return sourceRows(year, productCode, regionCode, authorizedRegionCodes,
                includePeriodHistory, List.of(), true);
    }

    private List<SourceRow> sourceRows(
            int year, String productCode, String regionCode, Set<String> authorizedRegionCodes,
            boolean includePeriodHistory, List<UUID> samplePointIds,
            boolean includeSampleContact) {
        jdbc.sql("SET LOCAL enable_nestloop=off").update();
        return jdbc.sql("""
                WITH RECURSIVE requested(code) AS (
                  SELECT code FROM platform.region
                  WHERE CAST(:region AS varchar) IS NOT NULL
                    AND code=CAST(:region AS varchar)
                  UNION
                  SELECT region_code FROM platform.monitoring_scope_region
                  WHERE CAST(:region AS varchar) IS NULL
                    AND scope_code='FORMAL_BUSINESS'
                    AND included AND business_root
                ), descendants(code) AS (
                  SELECT code FROM requested
                  UNION
                  SELECT child.code FROM platform.region child
                  JOIN descendants parent ON child.parent_code=parent.code
                ), ancestors(code) AS (
                  SELECT code FROM platform.region
                  WHERE CAST(:region AS varchar) IS NOT NULL
                    AND code=CAST(:region AS varchar)
                  UNION
                  SELECT parent.parent_code FROM platform.region parent
                  JOIN ancestors child ON parent.code=child.code
                  WHERE parent.parent_code IS NOT NULL
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
                       COALESCE(
                         CASE WHEN point.approval_state='APPROVED' THEN point.region_code END,
                         source.source_region_code) IN (SELECT code FROM descendants)
                         region_in_requested_scope,
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
                       CASE WHEN :includeSampleContact THEN CASE source.category_code
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
                       END END sample_contact,
                       ST_X(point.governed_point) longitude,
                       ST_Y(point.governed_point) latitude
                FROM overview.current_sample_point_query_source(
                  :year,:product,:allProducts,:includePeriodHistory) source
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
                WHERE source.sample_point_id IS NOT NULL
                  AND (:allProducts OR source.product_code=:product)
                  AND (:allPoints OR point.sample_point_id IN (:samplePointIds))
                  AND source.unresolved_reason IS NULL
                  AND point.approval_state='APPROVED'
                  AND point.location_state='VALID'
                  AND resolution.resolution_action IS DISTINCT FROM 'VOID'
                  AND (
                    COALESCE(
                      CASE WHEN point.approval_state='APPROVED' THEN point.region_code END,
                      source.source_region_code) IN (SELECT code FROM descendants)
                    OR point.approval_state='APPROVED'
                      AND point.region_code IN (SELECT code FROM ancestors)
                  )
                  AND (:unrestricted OR (
                    source.source_region_code IN (SELECT unnest(string_to_array(:authorizedRegionList,',')))
                    AND (point.approval_state IS DISTINCT FROM 'APPROVED'
                      OR point.region_code IS NULL
                      OR point.region_code IN (SELECT unnest(string_to_array(:authorizedRegionList,','))))))
                ORDER BY source.type_sort_order,source.canonical_name,source.sample_point_id,
                         source.product_code,source.source_role
                """).param("year", year).param("product", productCode == null ? "" : productCode)
                .param("allProducts", productCode == null)
                .param("allPoints", samplePointIds.isEmpty())
                .param("samplePointIds", samplePointIds.isEmpty()
                        ? List.of(new UUID(0L, 0L)) : samplePointIds)
                .param("region", regionCode)
                .param("includePeriodHistory", includePeriodHistory)
                .param("includeSampleContact", includeSampleContact)
                .param("unrestricted", unrestricted(authorizedRegionCodes))
                .param("authorizedRegionList", authorizedRegionList(authorizedRegionCodes))
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
                        row.getBoolean("region_in_requested_scope"),
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

    private Map<String, OverviewSamplePointDetail.BusinessValue> logisticsValues(String recordId) {
        Map<String, OverviewSamplePointDetail.BusinessValue> values = jdbc.sql("""
                SELECT event.source_organization,event.reporter,event.sample_contact,
                       event.sample_longitude,event.sample_latitude,
                       origin.node_name origin_node,destination.node_name destination_node,
                       mode.name transport_mode,
                       CASE event.direction_code WHEN 'INFLOW' THEN '流入'
                         WHEN 'OUTFLOW' THEN '流出' WHEN 'TRANSIT' THEN '中转' END direction_name
                FROM logistics.route_event event
                LEFT JOIN logistics.logistics_node origin ON origin.node_code=event.origin_node_code
                LEFT JOIN logistics.logistics_node destination ON destination.node_code=event.destination_node_code
                JOIN platform.transport_mode mode ON mode.code=event.transport_mode_code
                WHERE event.event_id::text=:recordId AND event.status_code='APPROVED'
                """).param("recordId", recordId).query((row, index) -> {
                    Map<String, OverviewSamplePointDetail.BusinessValue> initialValues = new LinkedHashMap<>();
                    put(initialValues, "SOURCE_ORGANIZATION", "信息来源单位",
                            row.getString("source_organization"), null);
                    put(initialValues, "REPORTER", "填报人", row.getString("reporter"), null);
                    put(initialValues, "SAMPLE_CONTACT", "样本点联系方式",
                            row.getString("sample_contact"), null);
                    put(initialValues, "SAMPLE_LONGITUDE", "样本点经度",
                            degree(row.getBigDecimal("sample_longitude")), null);
                    put(initialValues, "SAMPLE_LATITUDE", "样本点纬度",
                            degree(row.getBigDecimal("sample_latitude")), null);
                    put(initialValues, "ORIGIN_NODE", "起运节点", row.getString("origin_node"), null);
                    put(initialValues, "DESTINATION_NODE", "到达节点",
                            row.getString("destination_node"), null);
                    put(initialValues, "TRANSPORT_MODE", "运输方式",
                            row.getString("transport_mode"), null);
                    put(initialValues, "DIRECTION", "物流方向",
                            row.getString("direction_name"), null);
                    return initialValues;
                }).single();
        Map<String, String> labels = Map.of(
                "ROUTE_VOLUME", "运输数量",
                "FREIGHT_RATE", "物流运价",
                "TRANSIT_TIME", "运输时长",
                "BOARD_PRICE", "车板价");
        jdbc.sql("""
                SELECT fact_code,value,unit_code
                FROM logistics.route_fact
                WHERE event_id::text=:recordId
                ORDER BY fact_code
                """).param("recordId", recordId).query((row, index) -> new DirectoryValue(
                        row.getString("fact_code"), labels.get(row.getString("fact_code")),
                        row.getString("unit_code"), decimal(row.getBigDecimal("value"))))
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

    private static String degree(BigDecimal value) {
        return value == null ? null : decimal(value) + " 度";
    }

    private record DirectoryValue(String code, String label, String unit, String value) {}

    private EntityProjection projection(int year, String productCode, String regionCode, String categoryCode,
            String typeCode, String query, Set<String> authorizedRegionCodes) {
        return projection(year, productCode, regionCode, categoryCode, typeCode, query,
                authorizedRegionCodes, false);
    }

    private EntityProjection projection(int year, String productCode, String regionCode, String categoryCode,
            String typeCode, String query, Set<String> authorizedRegionCodes,
            boolean loadAllProducts) {
        if (categoryCode == null && typeCode == null && query == null) {
            ProjectionCacheKey key = new ProjectionCacheKey(
                    year, productCode, regionCode, loadAllProducts,
                    authorizedRegionCodes.stream().sorted().toList());
            CompletableFuture<EntityProjection> created = new CompletableFuture<>();
            CompletableFuture<EntityProjection> inFlight =
                    baseProjectionInFlight.putIfAbsent(key, created);
            if (inFlight != null) return inFlight.join();
            try {
                EntityProjection loaded = loadProjection(
                        year, productCode, regionCode, null, null, null,
                        authorizedRegionCodes, loadAllProducts);
                created.complete(loaded);
                return loaded;
            } catch (RuntimeException error) {
                created.completeExceptionally(error);
                throw error;
            } finally {
                baseProjectionInFlight.remove(key, created);
            }
        }
        return loadProjection(year, productCode, regionCode, categoryCode, typeCode, query,
                authorizedRegionCodes, loadAllProducts);
    }

    private EntityProjection loadProjection(
            int year, String productCode, String regionCode, String categoryCode,
            String typeCode, String query, Set<String> authorizedRegionCodes,
            boolean loadAllProducts) {
        List<SourceRow> rows = sourceRows(
                year, loadAllProducts ? null : productCode,
                regionCode, authorizedRegionCodes, false, List.of(), query != null);
        Map<UUID, List<SourceRow>> byPoint = rows.stream()
                .filter(SourceRow::approvedPoint)
                .collect(Collectors.groupingBy(SourceRow::samplePointId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<UUID, LocationEvaluation> locations = new LinkedHashMap<>();
        byPoint.forEach((id, pointRows) -> locations.put(id, publishedLocation(pointRows)));

        List<Entity> candidateEntities = byPoint.entrySet().stream()
                .filter(entry -> matchesEntityFilter(
                        entry.getValue(), productCode, categoryCode, typeCode, query))
                .map(entry -> {
                    LocationEvaluation location = locations.get(entry.getKey());
                    return new Entity(entry.getKey(), entry.getValue(), location.coordinate(),
                            location.dataQualityReason());
                })
                .toList();
        Set<UUID> coordinateContainedAncestors = entitiesAssignedAlongPublishedHierarchy(
                candidateEntities.stream()
                        .filter(entity -> !entity.rows().getFirst().regionInRequestedScope())
                        .filter(entity -> entity.dataQualityReason() == null)
                        .toList(),
                regionCode);
        List<Entity> entities = candidateEntities.stream()
                .filter(entity -> entity.rows().getFirst().regionInRequestedScope()
                        || coordinateContainedAncestors.contains(entity.samplePointId()))
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

    private static LocationEvaluation publishedLocation(List<SourceRow> rows) {
        SourceRow identity = rows.getFirst();
        if (identity.governedRegionCode() == null) {
            return new LocationEvaluation(null, "REGION_MISSING");
        }
        if (!"VALID".equals(identity.locationState())) {
            return new LocationEvaluation(null, "LOCATION_MISSING");
        }

        if (identity.longitude() == null || identity.latitude() == null) {
            return new LocationEvaluation(null, "LOCATION_MISSING");
        }
        Coordinate stable = Coordinate.stable(identity);
        if (!stable.inRange()) return new LocationEvaluation(null, "COORDINATE_OUT_OF_RANGE");
        return new LocationEvaluation(stable, null);
    }

    private List<OverviewSamplePointList.Category> categories(
            List<Entity> entities, String productCode,
            Map<UUID, StableIdentityRefs> stableIdentityRefs) {
        List<OverviewSamplePointList.Category> result = new ArrayList<>();
        for (CategoryDefinition category : CATEGORIES) {
            List<SourceRow> actualRows = entities.stream().flatMap(entity -> entity.rows().stream())
                    .filter(row -> row.categoryCode().equals(category.code())).toList();
            long categoryPoints = entities.stream().filter(entity -> stableIdentityRefs
                    .getOrDefault(entity.samplePointId(), StableIdentityRefs.from(entity.rows()))
                    .categories().stream().anyMatch(ref -> ref.code().equals(category.code()))).count();
            if (categoryPoints == 0) continue;
            List<TypeDefinition> actualTypes = new ArrayList<>(actualRows.stream()
                    .filter(row -> row.productCode().equals(productCode)).collect(Collectors.toMap(
                    SourceRow::typeCode,
                    row -> new TypeDefinition(row.categoryCode(), row.typeCode(), row.typeName(),
                            row.iconKey(), row.typeSortOrder()),
                    (left, right) -> left, LinkedHashMap::new)).values());
            actualTypes.sort(Comparator.comparingInt(TypeDefinition::sortOrder)
                    .thenComparing(TypeDefinition::code));
            List<OverviewSamplePointList.Type> types = actualTypes.stream()
                    .map(type -> new OverviewSamplePointList.Type(type.code(), type.name(), type.iconKey(),
                            entities.stream().filter(entity -> entity.rows().stream()
                                    .anyMatch(row -> row.categoryCode().equals(category.code())
                                            && row.productCode().equals(productCode)
                                            && row.typeCode().equals(type.code()))).count()))
                    .toList();
            result.add(new OverviewSamplePointList.Category(
                    category.code(), category.name(), categoryPoints, types));
        }
        return result;
    }

    private OverviewSamplePointList.Item listItem(
            Entity entity, String productCode, String categoryCode, String typeCode,
            SourceRow latest,
            Map<SummaryKey, Map<String, OverviewSamplePointDetail.BusinessValue>> summaries,
            StableIdentityRefs stableIdentityRefs) {
        List<SourceRow> identityRows = entity.rows();
        List<SourceRow> businessRows = identityRows.stream()
                .filter(row -> row.productCode().equals(productCode))
                .filter(row -> matchesFilter(row, categoryCode, typeCode, null)).toList();
        SourceRow identity = identityRows.getFirst();
        List<OverviewSamplePointList.CategoryRef> categories = stableIdentityRefs.categories();
        List<OverviewSamplePointList.TypeRef> types = distinct(businessRows,
                SourceRow::typeCode,
                row -> new OverviewSamplePointList.TypeRef(row.typeCode(), row.typeName(), row.iconKey()));
        List<OverviewSamplePointList.ProductRef> products = stableIdentityRefs.products();
        Map<String, OverviewSamplePointDetail.BusinessValue> summary = latest == null
                ? Map.of() : summaries.getOrDefault(SummaryKey.of(latest), Map.of());
        return new OverviewSamplePointList.Item(identity.samplePointId(), identity.canonicalName(),
                identity.governedRegionCode(), identity.governedRegionName(), identity.locationState(),
                entity.dataQualityReason(), categories, types, products,
                latest == null ? null : latest.occurrenceDate(), summary);
    }

    private static SourceRow latestBusinessRow(
            Entity entity, String productCode, String categoryCode, String typeCode) {
        return entity.rows().stream()
                .filter(row -> row.productCode().equals(productCode))
                .filter(row -> matchesFilter(row, categoryCode, typeCode, null))
                .sorted(Comparator.comparing(SourceRow::occurrenceDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Comparator.comparingLong(SourceRow::sourceVersion).reversed())
                        .thenComparing(SourceRow::sourceRecordId))
                .findFirst().orElse(null);
    }

    private Map<SummaryKey, Map<String, OverviewSamplePointDetail.BusinessValue>> summaryValues(
            List<SourceRow> rows) {
        Map<SummaryKey, Map<String, OverviewSamplePointDetail.BusinessValue>> result =
                new LinkedHashMap<>();
        collectProductionSummary(rows, result);
        collectMarketSummary(rows, result);
        collectLogisticsSummary(rows, result);
        return result;
    }

    private void collectProductionSummary(
            List<SourceRow> rows,
            Map<SummaryKey, Map<String, OverviewSamplePointDetail.BusinessValue>> result) {
        List<String> recordIds = sourceRecordIds(rows, "PRODUCTION");
        if (recordIds.isEmpty()) return;
        addSummaryRows(result, jdbc.sql("""
                SELECT record.record_id source_record_id,field.code,field.label,
                       field.text_value,field.numeric_value,field.unit_code
                FROM production.production_record record
                CROSS JOIN LATERAL (VALUES
                  ('SAMPLE_CONTACT','样本点联系方式',(
                    SELECT metadata.value
                    FROM production.production_record_submission_metadata metadata
                    WHERE metadata.record_id=record.record_id
                      AND metadata.field_code='PROD_SAMPLE_CONTACT'),NULL::numeric,NULL::varchar),
                  ('SURVEYOR_NAME','调研人',(
                    SELECT metadata.value
                    FROM production.production_record_submission_metadata metadata
                    WHERE metadata.record_id=record.record_id
                      AND metadata.field_code='PROD_SURVEYOR_NAME'),NULL::numeric,NULL::varchar),
                  ('SURVEYOR_PHONE','调研人联系方式',(
                    SELECT metadata.value
                    FROM production.production_record_submission_metadata metadata
                    WHERE metadata.record_id=record.record_id
                      AND metadata.field_code='PROD_SURVEYOR_PHONE'),NULL::numeric,NULL::varchar),
                  ('CULTIVATED_AREA_MU','种植面积',NULL::varchar,
                    record.cultivated_area_mu,'亩'),
                  ('ESTIMATED_OUTPUT_KG','总产量',NULL::varchar,
                    record.estimated_output_kg,'千克'),
                  ('YIELD_PER_MU_KG','单产',NULL::varchar,
                    record.yield_per_mu_kg,'千克/亩')
                ) field(code,label,text_value,numeric_value,unit_code)
                WHERE record.record_id IN (:recordIds)
                  AND (field.numeric_value IS NOT NULL
                    OR nullif(btrim(field.text_value),'') IS NOT NULL)
                """).param("recordIds", recordIds)
                .query(this::summaryValueRow).list(), "PRODUCTION");
    }

    private void collectMarketSummary(
            List<SourceRow> rows,
            Map<SummaryKey, Map<String, OverviewSamplePointDetail.BusinessValue>> result) {
        List<String> recordIds = sourceRecordIds(rows, "MARKET");
        if (recordIds.isEmpty()) return;
        addSummaryRows(result, jdbc.sql("""
                SELECT record.record_id source_record_id,field.code,field.label,
                       field.text_value,field.numeric_value,field.unit_code
                FROM market.market_record record
                CROSS JOIN LATERAL (VALUES
                  ('SAMPLE_CONTACT','样本点联系方式',(
                    SELECT value.value FROM market.market_record_core_value value
                    WHERE value.record_id=record.record_id
                      AND value.field_code='MKT_SAMPLE_CONTACT'),NULL::numeric,NULL::varchar),
                  ('SURVEYOR_NAME','调研人',(
                    SELECT value.value FROM market.market_record_core_value value
                    WHERE value.record_id=record.record_id
                      AND value.field_code='MKT_SURVEYOR_NAME'),NULL::numeric,NULL::varchar),
                  ('SURVEYOR_PHONE','调研人联系方式',(
                    SELECT value.value FROM market.market_record_core_value value
                    WHERE value.record_id=record.record_id
                      AND value.field_code='MKT_SURVEYOR_PHONE'),NULL::numeric,NULL::varchar),
                  ('PURCHASE_PRICE','对象采购价格',NULL::varchar,
                    record.purchase_base_price,'元/吨'),
                  ('SALE_PRICE','对象销售价格',NULL::varchar,
                    record.sale_base_price,'元/吨')
                ) field(code,label,text_value,numeric_value,unit_code)
                WHERE record.record_id IN (:recordIds)
                  AND (field.numeric_value IS NOT NULL
                    OR nullif(btrim(field.text_value),'') IS NOT NULL)
                UNION ALL
                SELECT fact.record_id source_record_id,fact.fact_code code,
                       definition.label,NULL::varchar text_value,
                       fact.value numeric_value,definition.unit unit_code
                FROM market.market_record_fact fact
                JOIN platform.market_fact_definition definition
                  ON definition.code=fact.fact_code
                WHERE fact.record_id IN (:recordIds)
                  AND fact.fact_code IN ('PURCHASE_VOLUME','SALES_VOLUME')
                """).param("recordIds", recordIds)
                .query(this::summaryValueRow).list(), "MARKET");
    }

    private void collectLogisticsSummary(
            List<SourceRow> rows,
            Map<SummaryKey, Map<String, OverviewSamplePointDetail.BusinessValue>> result) {
        List<String> recordIds = sourceRecordIds(rows, "LOGISTICS");
        if (recordIds.isEmpty()) return;
        addSummaryRows(result, jdbc.sql("""
                SELECT event.event_id::text source_record_id,field.code,field.label,
                       field.text_value,field.numeric_value,field.unit_code
                FROM logistics.route_event event
                LEFT JOIN logistics.logistics_node origin
                  ON origin.node_code=event.origin_node_code
                LEFT JOIN logistics.logistics_node destination
                  ON destination.node_code=event.destination_node_code
                JOIN platform.transport_mode mode ON mode.code=event.transport_mode_code
                CROSS JOIN LATERAL (VALUES
                  ('SOURCE_ORGANIZATION','信息来源单位',event.source_organization,
                    NULL::numeric,NULL::varchar),
                  ('REPORTER','填报人',event.reporter,NULL::numeric,NULL::varchar),
                  ('ORIGIN_NODE','起运节点',origin.node_name,NULL::numeric,NULL::varchar),
                  ('DESTINATION_NODE','到达节点',destination.node_name,NULL::numeric,NULL::varchar),
                  ('TRANSPORT_MODE','运输方式',mode.name,NULL::numeric,NULL::varchar),
                  ('DIRECTION','物流方向',CASE event.direction_code
                    WHEN 'INFLOW' THEN '流入' WHEN 'OUTFLOW' THEN '流出'
                    WHEN 'TRANSIT' THEN '中转' END,NULL::numeric,NULL::varchar)
                ) field(code,label,text_value,numeric_value,unit_code)
                WHERE event.event_id::text IN (:recordIds)
                  AND nullif(btrim(field.text_value),'') IS NOT NULL
                UNION ALL
                SELECT fact.event_id::text source_record_id,fact.fact_code code,
                       CASE fact.fact_code WHEN 'ROUTE_VOLUME' THEN '运输数量'
                         WHEN 'FREIGHT_RATE' THEN '物流运价'
                         WHEN 'TRANSIT_TIME' THEN '运输时长'
                         WHEN 'BOARD_PRICE' THEN '车板价' END label,
                       NULL::varchar text_value,fact.value numeric_value,
                       fact.unit_code
                FROM logistics.route_fact fact
                WHERE fact.event_id::text IN (:recordIds)
                  AND fact.fact_code IN (
                    'ROUTE_VOLUME','FREIGHT_RATE','TRANSIT_TIME','BOARD_PRICE')
                """).param("recordIds", recordIds)
                .query(this::summaryValueRow).list(), "LOGISTICS");
    }

    private SummaryValueRow summaryValueRow(java.sql.ResultSet row, int index)
            throws java.sql.SQLException {
        BigDecimal numeric = row.getBigDecimal("numeric_value");
        return new SummaryValueRow(
                row.getString("source_record_id"), row.getString("code"),
                row.getString("label"),
                numeric == null ? row.getString("text_value") : decimal(numeric),
                row.getString("unit_code"));
    }

    private static List<String> sourceRecordIds(List<SourceRow> rows, String categoryCode) {
        return rows.stream().filter(row -> row.categoryCode().equals(categoryCode))
                .map(SourceRow::sourceRecordId).distinct().toList();
    }

    private static void addSummaryRows(
            Map<SummaryKey, Map<String, OverviewSamplePointDetail.BusinessValue>> result,
            List<SummaryValueRow> rows, String categoryCode) {
        Set<String> allowed = Set.copyOf(SUMMARY_FIELDS.getOrDefault(categoryCode, List.of()));
        rows.stream().filter(row -> allowed.contains(row.code())).forEach(row -> result
                .computeIfAbsent(new SummaryKey(categoryCode, row.sourceRecordId()),
                        ignored -> new LinkedHashMap<>())
                .put(row.code(), new OverviewSamplePointDetail.BusinessValue(
                        row.label(), row.value(), row.unitCode())));
    }

    private OverviewSamplePointIcon icon(Entity entity, String productCode) {
        List<SourceRow> rows = entity.rows();
        SourceRow identity = rows.getFirst();
        List<RoleDefinition> stableRoles = roles(rows);
        List<OverviewSamplePointIcon.RoleRef> roles = stableRoles.stream()
                .map(role -> new OverviewSamplePointIcon.RoleRef(
                        role.code(), role.name(), role.iconKey())).toList();
        List<OverviewSamplePointIcon.TypeRef> types = distinct(rows.stream()
                        .filter(row -> row.productCode().equals(productCode)).toList(),
                SourceRow::typeCode,
                row -> new OverviewSamplePointIcon.TypeRef(row.typeCode(), row.typeName(), row.iconKey()));
        return new OverviewSamplePointIcon(identity.samplePointId(), identity.canonicalName(),
                identity.governedRegionCode(),
                stableRoles.getFirst().iconKey(), roles, types,
                entity.coordinate().longitude(), entity.coordinate().latitude(),
                entity.dataQualityReason());
    }

    private static boolean matchesEntityFilter(List<SourceRow> rows, String productCode,
            String categoryCode, String typeCode, String query) {
        if (rows.stream().noneMatch(row -> row.productCode().equals(productCode))) return false;
        if (categoryCode != null && rows.stream().noneMatch(
                row -> row.productCode().equals(productCode)
                        && row.categoryCode().equals(categoryCode))) return false;
        if (typeCode != null && rows.stream().noneMatch(row -> row.productCode().equals(productCode)
                && row.categoryCode().equals(categoryCode) && row.typeCode().equals(typeCode))) return false;
        if (query == null) return true;
        String normalized = query.toLowerCase(Locale.ROOT);
        return rows.stream().anyMatch(row -> contains(row.canonicalName(), normalized)
                || contains(row.governedRegionName(), normalized)
                || contains(row.sampleContact(), normalized));
    }

    private static List<RoleDefinition> roles(List<SourceRow> rows) {
        Set<String> actual = rows.stream().map(SourceRow::categoryCode).collect(Collectors.toSet());
        return CATEGORIES.stream().filter(category -> actual.contains(category.code()))
                .map(category -> new RoleDefinition(
                        category.code(), category.name(), category.iconKey())).toList();
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

    private static String authorizedRegionList(Set<String> authorizedRegionCodes) {
        return String.join(",", authorizedRegionCodes);
    }

    private record CategoryDefinition(String code, String name, String iconKey) {}
    private record RoleDefinition(String code, String name, String iconKey) {}
    private record TypeDefinition(
            String categoryCode, String code, String name, String iconKey, int sortOrder) {}
    private record SummaryKey(String categoryCode, String sourceRecordId) {
        static SummaryKey of(SourceRow row) {
            return new SummaryKey(row.categoryCode(), row.sourceRecordId());
        }
    }
    private record SummaryValueRow(
            String sourceRecordId, String code, String label, String value, String unitCode) {}
    private record StableIdentityAssociation(
            UUID samplePointId,
            OverviewSamplePointList.CategoryRef category,
            OverviewSamplePointList.ProductRef product) {}
    private record StableIdentityRefs(
            List<OverviewSamplePointList.CategoryRef> categories,
            List<OverviewSamplePointList.ProductRef> products) {
        static StableIdentityRefs from(List<SourceRow> rows) {
            return new StableIdentityRefs(
                    distinct(rows, SourceRow::categoryCode,
                            row -> new OverviewSamplePointList.CategoryRef(
                                    row.categoryCode(), row.categoryName())),
                    distinct(rows, SourceRow::productCode,
                            row -> new OverviewSamplePointList.ProductRef(
                                    row.productCode(), row.productName())));
        }
    }
    private record AggregateRegion(String code, String name, String level) {}
    private record ProjectionCacheKey(
            int year, String productCode, String regionCode, boolean loadAllProducts,
            List<String> authorizedRegionCodes) {}
    private record EntityProjection(List<Entity> entities, List<SourceRow> corrections) {}
    private record Entity(
            UUID samplePointId,
            List<SourceRow> rows,
            Coordinate coordinate,
            String dataQualityReason) {}
    private record LocationEvaluation(Coordinate coordinate, String dataQualityReason) {}
    private record Coordinate(double longitude, double latitude) {
        static Coordinate stable(SourceRow row) {
            return new Coordinate(row.longitude(), row.latitude());
        }
        boolean inRange() {
            return longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90;
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
            boolean regionInRequestedScope,
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
            Double latitude) {
        boolean approvedPoint() {
            return samplePointId != null && "APPROVED".equals(pointApprovalState);
        }
    }
}
