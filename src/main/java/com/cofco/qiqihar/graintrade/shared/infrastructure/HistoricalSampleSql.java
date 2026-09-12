package com.cofco.qiqihar.graintrade.shared.infrastructure;

/** One retired population for map, hierarchy counts and the paginated business ledger. */
public final class HistoricalSampleSql {
    private HistoricalSampleSql() {}
    public static final String CTE = """
            WITH RECURSIVE retired_points AS MATERIALIZED (
              SELECT * FROM registry.sample_point WHERE deletion_state='RETIRED'
                AND approval_state='APPROVED' AND location_state='VALID'
                AND (:retirementYear=-1 OR EXTRACT(YEAR FROM retired_at AT TIME ZONE 'Asia/Shanghai')=:retirementYear)
            ), display_path(sample_point_id,code,depth) AS (
              SELECT sample_point_id,region_code::text,0 FROM retired_points
              UNION ALL
              SELECT path.sample_point_id,child.code::text,path.depth+1
              FROM display_path path JOIN retired_points point ON point.sample_point_id=path.sample_point_id
              JOIN LATERAL (
                SELECT min(region.code) code FROM platform.region region
                JOIN overview.administrative_boundary_render boundary ON boundary.region_code=region.code
                WHERE region.parent_code=path.code
                  AND ST_Covers(boundary.geometry,COALESCE(point.display_point,point.governed_point))
                HAVING count(*)=1
              ) child ON child.code IS NOT NULL
              WHERE path.depth<6
            ), display_region AS (
              SELECT DISTINCT ON (sample_point_id) sample_point_id,code
              FROM display_path ORDER BY sample_point_id,depth DESC,code
            ), descendants(code) AS (
              SELECT code FROM platform.region WHERE :region='' OR code=:region
              UNION
              SELECT child.code FROM platform.region child JOIN descendants parent ON child.parent_code=parent.code
            ), business_association AS (
              SELECT record.sample_point_id,'PRODUCTION'::varchar category_code,
                     record.object_type_code type_code,record.record_id::text source_record_id,
                     record.product_code,record.survey_date occurrence_date,record.reported_at observed_at,
                     record.updated_at maintained_at,record.version source_version
              FROM production.production_record record WHERE record.status_code='APPROVED' AND record.product_code=:product
              UNION ALL
              SELECT record.sample_point_id,'MARKET',record.object_type_code,record.record_id::text,
                     record.product_code,record.trade_date,record.reported_at,record.updated_at,record.version
              FROM market.market_record record WHERE record.status_code='APPROVED' AND record.product_code=:product
              UNION ALL
              SELECT event.sample_point_id,'LOGISTICS',CASE event.transport_mode_code WHEN 'RAIL' THEN 'RAIL_NODE' WHEN 'ROAD' THEN 'ROAD_NODE' END,event.event_id::text,
                     event.product_code,event.collection_date,event.reported_at,event.updated_at,event.version
              FROM logistics.route_event event
              WHERE event.status_code='APPROVED' AND event.product_code=:product AND event.sample_point_id IS NOT NULL
              UNION ALL
              SELECT node.sample_point_id,'LOGISTICS',node.node_type_code,event.event_id::text,
                     event.product_code,event.collection_date,event.reported_at,event.updated_at,event.version
              FROM logistics.logistics_node node JOIN logistics.route_event event
                ON event.origin_node_code=node.node_code OR event.destination_node_code=node.node_code
              WHERE event.status_code='APPROVED' AND event.product_code=:product AND node.sample_point_id IS NOT NULL
                AND node.sample_point_id IS DISTINCT FROM event.sample_point_id
            ), ranked AS (
              SELECT point.sample_point_id,point.canonical_name,point.region_code,region.name region_name,
                     display.code display_region_code,
                     profile.address,association.category_code,association.type_code,
                     object_type.name type_name,COALESCE(object_type.overview_icon_key,lower(association.category_code)) overview_icon_key,
                     association.source_record_id,association.category_code source_role,
                     association.product_code,product.name product_name,association.occurrence_date,
                     association.observed_at,association.maintained_at,association.source_version,
                     point.retired_at,EXTRACT(YEAR FROM point.retired_at AT TIME ZONE 'Asia/Shanghai')::integer retirement_year,
                     point.retired_reason,point.retired_by,point.location_mode,
                     ST_X(COALESCE(point.display_point,point.governed_point)) longitude,
                     ST_Y(COALESCE(point.display_point,point.governed_point)) latitude,
                     ROW_NUMBER() OVER (PARTITION BY point.sample_point_id,association.category_code,association.product_code
                       ORDER BY association.maintained_at DESC NULLS LAST,association.observed_at DESC NULLS LAST,
                                association.source_version DESC,association.source_record_id DESC) latest_rank
              FROM retired_points point
              JOIN display_region display ON display.sample_point_id=point.sample_point_id
              JOIN business_association association ON association.sample_point_id=point.sample_point_id
              LEFT JOIN registry.formal_sample_point_profile profile ON profile.sample_point_id=point.sample_point_id
              LEFT JOIN platform.object_type object_type ON object_type.code=association.type_code
                AND object_type.business_domain=association.category_code
              JOIN platform.product product ON product.code=association.product_code
              JOIN platform.region region ON region.code=point.region_code
              WHERE point.deletion_state='RETIRED' AND point.approval_state='APPROVED' AND point.location_state='VALID'
                AND (:retirementYear=-1 OR EXTRACT(YEAR FROM point.retired_at AT TIME ZONE 'Asia/Shanghai')=:retirementYear)
                AND (point.effective_to IS NULL OR association.occurrence_date<=point.effective_to)
                AND display.code IN (SELECT code FROM descendants)
                AND (:category='' OR association.category_code=:category)
                AND (:query='' OR point.canonical_name ILIKE :queryPattern ESCAPE '\\'
                  OR region.name ILIKE :queryPattern ESCAPE '\\' OR profile.address ILIKE :queryPattern ESCAPE '\\')
                AND (:unrestricted OR point.region_code IN (SELECT unnest(string_to_array(:authorizedRegionList,','))))
            ), historical AS (
              SELECT * FROM ranked WHERE latest_rank=1 AND (:type='' OR type_code=:type)
            )
            """;
    public static java.util.Map<String,Object> parameters(Integer year, String product, String region,
            String category, String type, String query, java.util.Set<String> authorizedRegions) {
        String keyword = query == null ? "" : query;
        return java.util.Map.of("retirementYear", year == null ? -1 : year, "product", product,
                "region", region == null ? "" : region, "category", category == null ? "" : category,
                "type", type == null ? "" : type, "query", keyword,
                "queryPattern", "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%",
                "unrestricted", authorizedRegions.contains("*"), "authorizedRegionList", String.join(",", authorizedRegions));
    }
}
