CREATE VIEW overview.sample_point_query_source AS
WITH source AS (
    SELECT approved.sample_point_id,
           approved.source_domain,
           approved.source_record_id,
           approved.source_role,
           approved.product_code,
           approved.occurrence_date,
           approved.source_version,
           approved.party_id,
           approved.governed_region_code,
           approved.sample_point_kind_code,
           NULL::varchar(40) unresolved_reason,
           approved.point_geometry
    FROM overview.approved_sample_point_source approved
    UNION ALL
    SELECT unresolved.sample_point_id,
           unresolved.source_domain,
           unresolved.source_record_id,
           unresolved.source_role,
           unresolved.product_code,
           unresolved.occurrence_date,
           unresolved.source_version,
           unresolved.party_id,
           unresolved.governed_region_code,
           unresolved.sample_point_kind_code,
           unresolved.unresolved_reason,
           unresolved.point_geometry
    FROM overview.unresolved_approved_sample_point_source unresolved
), enriched AS (
    SELECT source.*,
           record.region_code source_region_code,
           record.object_type_code type_code,
           record.updated_at source_updated_at
    FROM source
    JOIN production.production_record record
      ON source.source_domain='PRODUCTION'
     AND source.source_role='SURVEY'
     AND record.record_id=source.source_record_id
    UNION ALL
    SELECT source.*,
           record.region_code,
           record.object_type_code,
           record.updated_at
    FROM source
    JOIN market.market_record record
      ON source.source_domain='MARKET'
     AND source.source_role='SURVEY'
     AND record.record_id=source.source_record_id
    UNION ALL
    SELECT source.*,
           event.origin_region_code,
           node.node_type_code,
           event.updated_at
    FROM source
    JOIN logistics.route_event event
      ON source.source_domain='LOGISTICS'
     AND source.source_role='ORIGIN'
     AND event.event_id::text=source.source_record_id
    JOIN logistics.logistics_node node ON node.node_code=event.origin_node_code
    UNION ALL
    SELECT source.*,
           event.destination_region_code,
           node.node_type_code,
           event.updated_at
    FROM source
    JOIN logistics.route_event event
      ON source.source_domain='LOGISTICS'
     AND source.source_role='DESTINATION'
     AND event.event_id::text=source.source_record_id
    JOIN logistics.logistics_node node ON node.node_code=event.destination_node_code
)
SELECT enriched.sample_point_id,
       enriched.source_domain category_code,
       CASE enriched.source_domain
           WHEN 'PRODUCTION' THEN '产情类'
           WHEN 'MARKET' THEN '市场类'
           WHEN 'LOGISTICS' THEN '物流节点'
       END::varchar(40) category_name,
       enriched.source_record_id,
       enriched.source_role,
       enriched.product_code,
       product.name product_name,
       enriched.occurrence_date,
       enriched.source_version,
       enriched.party_id,
       enriched.source_region_code,
       source_region.name source_region_name,
       enriched.governed_region_code,
       governed_region.name governed_region_name,
       enriched.sample_point_kind_code,
       enriched.type_code,
       object_type.name type_name,
       object_type.sort_order type_sort_order,
       point.canonical_name,
       point.approval_state point_approval_state,
       point.location_state,
       enriched.unresolved_reason,
       enriched.point_geometry,
       enriched.source_updated_at
FROM enriched
JOIN platform.product product ON product.code=enriched.product_code
JOIN platform.region source_region ON source_region.code=enriched.source_region_code
LEFT JOIN platform.region governed_region ON governed_region.code=enriched.governed_region_code
JOIN platform.object_type object_type
  ON object_type.code=enriched.type_code
 AND object_type.business_domain=enriched.source_domain
LEFT JOIN registry.sample_point point ON point.sample_point_id=enriched.sample_point_id;

COMMENT ON VIEW overview.sample_point_query_source IS
    'Approved source-role projection for authorized overview aggregates, lists, village icons and details.';
