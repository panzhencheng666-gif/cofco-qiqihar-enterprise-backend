-- The overview is a formal consumer. Import/review failures remain available to
-- their governance workflow, but must never enter the current sample network or
-- any dashboard derived from it.
CREATE OR REPLACE VIEW overview.sample_point_query_source AS
WITH source AS (
    SELECT approved.sample_point_id,approved.source_domain,approved.source_record_id,
           approved.source_role,approved.product_code,approved.occurrence_date,
           approved.source_version,approved.party_id,approved.governed_region_code,
           approved.sample_point_kind_code,NULL::varchar(40) unresolved_reason,
           approved.point_geometry
    FROM overview.approved_sample_point_source approved
    WHERE approved.source_domain<>'LOGISTICS' OR NOT EXISTS(
      SELECT 1 FROM logistics.route_event event
      WHERE event.event_id::text=approved.source_record_id AND event.sample_point_id IS NOT NULL)
    UNION ALL
    SELECT point.sample_point_id,'LOGISTICS'::varchar(30),event.event_id::text::varchar(120),
           'SURVEY'::varchar(20),event.product_code,event.collection_date,event.version,
           NULL::uuid,point.region_code,point.kind_code,NULL::varchar(40),point.governed_point
    FROM logistics.route_event event
    JOIN registry.sample_point point ON point.sample_point_id=event.sample_point_id
    JOIN overview.administrative_boundary boundary ON boundary.region_code=point.region_code
    WHERE event.status_code='APPROVED'
      AND event.survey_period_governance_state='CONFIRMED'
      AND point.approval_state='APPROVED' AND point.location_state='VALID'
      AND event.collection_date>=point.effective_from
      AND (point.effective_to IS NULL OR event.collection_date<=point.effective_to)
      AND point.containment_boundary_sha256=boundary.geometry_sha256
      AND point.containment_boundary_revision=boundary.source_revision
      AND ST_Covers(boundary.geometry,point.governed_point)
), enriched AS (
    SELECT source.*,record.region_code source_region_code,
           record.object_type_code type_code,record.updated_at source_updated_at
    FROM source JOIN production.production_record record
      ON source.source_domain='PRODUCTION' AND source.source_role='SURVEY'
     AND record.record_id=source.source_record_id
     AND record.status_code='APPROVED'
     AND record.survey_period_governance_state='CONFIRMED'
    UNION ALL
    SELECT source.*,record.region_code,record.object_type_code,record.updated_at
    FROM source JOIN market.market_record record
      ON source.source_domain='MARKET' AND source.source_role='SURVEY'
     AND record.record_id=source.source_record_id
     AND record.status_code='APPROVED'
     AND record.survey_period_governance_state='CONFIRMED'
    UNION ALL
    SELECT source.*,event.origin_region_code,node.node_type_code,event.updated_at
    FROM source JOIN logistics.route_event event
      ON source.source_domain='LOGISTICS' AND source.source_role='ORIGIN'
     AND event.event_id::text=source.source_record_id
    JOIN logistics.logistics_node node ON node.node_code=event.origin_node_code
    UNION ALL
    SELECT source.*,event.destination_region_code,node.node_type_code,event.updated_at
    FROM source JOIN logistics.route_event event
      ON source.source_domain='LOGISTICS' AND source.source_role='DESTINATION'
     AND event.event_id::text=source.source_record_id
    JOIN logistics.logistics_node node ON node.node_code=event.destination_node_code
    UNION ALL
    SELECT source.*,event.business_region_code,
           CASE event.transport_mode_code WHEN 'RAIL' THEN 'RAIL_NODE'
             WHEN 'ROAD' THEN 'ROAD_NODE' END::varchar(60),event.updated_at
    FROM source JOIN logistics.route_event event
      ON source.source_domain='LOGISTICS' AND source.source_role='SURVEY'
     AND event.event_id::text=source.source_record_id
    WHERE event.transport_mode_code IN ('RAIL','ROAD')
)
SELECT enriched.sample_point_id,enriched.source_domain category_code,
       CASE enriched.source_domain WHEN 'PRODUCTION' THEN '产情类'
         WHEN 'MARKET' THEN '市场类' WHEN 'LOGISTICS' THEN '物流节点' END::varchar(40) category_name,
       enriched.source_record_id,enriched.source_role,enriched.product_code,
       product.name product_name,enriched.occurrence_date,enriched.source_version,
       enriched.party_id,enriched.source_region_code,source_region.name source_region_name,
       enriched.governed_region_code,governed_region.name governed_region_name,
       enriched.sample_point_kind_code,enriched.type_code,object_type.name type_name,
       object_type.sort_order type_sort_order,point.canonical_name,
       point.approval_state point_approval_state,point.location_state,
       enriched.unresolved_reason,enriched.point_geometry,enriched.source_updated_at
FROM enriched
JOIN platform.product product ON product.code=enriched.product_code
JOIN platform.region source_region ON source_region.code=enriched.source_region_code
LEFT JOIN platform.region governed_region ON governed_region.code=enriched.governed_region_code
JOIN platform.object_type object_type ON object_type.code=enriched.type_code
 AND object_type.business_domain=enriched.source_domain
JOIN registry.sample_point point ON point.sample_point_id=enriched.sample_point_id
 AND point.approval_state='APPROVED' AND point.location_state='VALID';

COMMENT ON VIEW overview.sample_point_query_source IS
    'Approved and confirmed formal business roles projected from governed, location-valid stable sample identities.';
GRANT SELECT ON TABLE overview.sample_point_query_source TO qiqihar_enterprise_runtime;
