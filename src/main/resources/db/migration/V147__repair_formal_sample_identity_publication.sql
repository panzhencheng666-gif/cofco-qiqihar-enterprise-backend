-- Import is the only coordinate/region validation boundary. Repair legacy
-- globally matched records once, then let every read model trust the published
-- APPROVED + CONFIRMED stable sample identity.

CREATE TEMP TABLE v147_formal_record_rebind (
    source_domain varchar(30) NOT NULL,
    source_record_id varchar(120) NOT NULL,
    old_sample_point_id uuid NOT NULL,
    canonical_name varchar(200) NOT NULL,
    correct_region_code varchar(12) NOT NULL,
    longitude double precision NOT NULL,
    latitude double precision NOT NULL,
    occurrence_date date NOT NULL
) ON COMMIT DROP;

INSERT INTO v147_formal_record_rebind(
    source_domain,source_record_id,old_sample_point_id,canonical_name,
    correct_region_code,longitude,latitude,occurrence_date)
WITH RECURSIVE source AS (
    SELECT 'PRODUCTION'::varchar(30) source_domain,
           record.record_id::varchar(120) source_record_id,
           record.sample_point_id old_sample_point_id,
           record.region_code source_region_code,
           record.survey_date occurrence_date,
           sample_name.value::varchar(200) canonical_name,
           CASE WHEN btrim(longitude.value) ~ '^[+-]?[0-9]+([.][0-9]+)?$'
             THEN longitude.value::double precision END longitude,
           CASE WHEN btrim(latitude.value) ~ '^[+-]?[0-9]+([.][0-9]+)?$'
             THEN latitude.value::double precision END latitude
    FROM production.production_record record
    JOIN production.production_record_submission_metadata sample_name
      ON sample_name.record_id=record.record_id
     AND sample_name.field_code='PROD_SAMPLE_NAME'
    JOIN production.production_record_submission_metadata longitude
      ON longitude.record_id=record.record_id
     AND longitude.field_code='PROD_SAMPLE_LONGITUDE'
    JOIN production.production_record_submission_metadata latitude
      ON latitude.record_id=record.record_id
     AND latitude.field_code='PROD_SAMPLE_LATITUDE'
    WHERE record.status_code='APPROVED'
      AND record.survey_period_governance_state='CONFIRMED'
      AND record.sample_point_id IS NOT NULL
    UNION ALL
    SELECT 'MARKET'::varchar(30),record.record_id::varchar(120),
           record.sample_point_id,record.region_code,record.trade_date,
           sample_name.value::varchar(200),
           CASE WHEN btrim(longitude.value) ~ '^[+-]?[0-9]+([.][0-9]+)?$'
             THEN longitude.value::double precision END,
           CASE WHEN btrim(latitude.value) ~ '^[+-]?[0-9]+([.][0-9]+)?$'
             THEN latitude.value::double precision END
    FROM market.market_record record
    JOIN market.market_record_core_value sample_name
      ON sample_name.record_id=record.record_id
     AND sample_name.field_code='MKT_SAMPLE_NAME'
    JOIN market.market_record_core_value longitude
      ON longitude.record_id=record.record_id
     AND longitude.field_code='MKT_SAMPLE_LONGITUDE'
    JOIN market.market_record_core_value latitude
      ON latitude.record_id=record.record_id
     AND latitude.field_code='MKT_SAMPLE_LATITUDE'
    WHERE record.status_code='APPROVED'
      AND record.survey_period_governance_state='CONFIRMED'
      AND record.sample_point_id IS NOT NULL
), invalid_publication AS (
    SELECT source.*
    FROM source
    JOIN registry.sample_point point
      ON point.sample_point_id=source.old_sample_point_id
     AND point.approval_state='APPROVED'
     AND point.location_state='VALID'
    LEFT JOIN overview.administrative_boundary_render published_region
      ON published_region.region_code=point.region_code
    WHERE source.longitude IS NOT NULL AND source.latitude IS NOT NULL
      AND NOT COALESCE(ST_Covers(
        published_region.geometry,
        ST_SetSRID(ST_MakePoint(source.longitude,source.latitude),4326)),false)
), declared_region_tree(source_domain,source_record_id,code,depth) AS (
    SELECT invalid.source_domain,invalid.source_record_id,region.code,0
    FROM invalid_publication invalid
    JOIN platform.region region ON region.code=invalid.source_region_code
    UNION ALL
    SELECT tree.source_domain,tree.source_record_id,child.code,tree.depth+1
    FROM declared_region_tree tree
    JOIN platform.region child ON child.parent_code=tree.code
), ranked_region AS (
    SELECT invalid.*,tree.code correct_region_code,
           row_number() OVER (
             PARTITION BY invalid.source_domain,invalid.source_record_id
             ORDER BY tree.depth DESC,tree.code) region_rank
    FROM invalid_publication invalid
    JOIN declared_region_tree tree
      ON tree.source_domain=invalid.source_domain
     AND tree.source_record_id=invalid.source_record_id
    JOIN overview.administrative_boundary boundary
      ON boundary.region_code=tree.code
     AND ST_Covers(boundary.geometry,
       ST_SetSRID(ST_MakePoint(invalid.longitude,invalid.latitude),4326))
)
SELECT source_domain,source_record_id,old_sample_point_id,btrim(canonical_name),
       correct_region_code,longitude,latitude,occurrence_date
FROM ranked_region
WHERE region_rank=1;

CREATE TEMP TABLE v147_formal_identity_group (
    source_domain varchar(30) NOT NULL,
    old_sample_point_id uuid NOT NULL,
    canonical_name varchar(200) NOT NULL,
    correct_region_code varchar(12) NOT NULL,
    longitude double precision NOT NULL,
    latitude double precision NOT NULL,
    effective_from date NOT NULL,
    new_sample_point_id uuid NOT NULL DEFAULT gen_random_uuid(),
    PRIMARY KEY(source_domain,old_sample_point_id,canonical_name,
      correct_region_code,longitude,latitude)
) ON COMMIT DROP;

INSERT INTO v147_formal_identity_group(
    source_domain,old_sample_point_id,canonical_name,correct_region_code,
    longitude,latitude,effective_from)
SELECT source_domain,old_sample_point_id,canonical_name,correct_region_code,
       longitude,latitude,min(occurrence_date)
FROM v147_formal_record_rebind
GROUP BY source_domain,old_sample_point_id,canonical_name,correct_region_code,
         longitude,latitude;

INSERT INTO registry.sample_point(
    sample_point_id,kind_code,owner_party_id,canonical_name,region_code,
    approval_state,location_state,governed_point,effective_from,version,
    created_by,created_at,updated_by,updated_at)
SELECT identity.new_sample_point_id,'SURVEY_SITE',
       CASE WHEN identity.source_domain='MARKET' THEN old.owner_party_id END,
       identity.canonical_name,identity.correct_region_code,'APPROVED','VALID',
       ST_SetSRID(ST_MakePoint(identity.longitude,identity.latitude),4326),
       identity.effective_from,0,'wang-yang',now(),'wang-yang',now()
FROM v147_formal_identity_group identity
JOIN registry.sample_point old
  ON old.sample_point_id=identity.old_sample_point_id;

UPDATE production.production_record record
SET sample_point_id=identity.new_sample_point_id
FROM v147_formal_record_rebind rebind
JOIN v147_formal_identity_group identity
  ON identity.source_domain=rebind.source_domain
 AND identity.old_sample_point_id=rebind.old_sample_point_id
 AND identity.canonical_name=rebind.canonical_name
 AND identity.correct_region_code=rebind.correct_region_code
 AND identity.longitude=rebind.longitude
 AND identity.latitude=rebind.latitude
WHERE rebind.source_domain='PRODUCTION'
  AND record.record_id=rebind.source_record_id
  AND record.sample_point_id=rebind.old_sample_point_id;

UPDATE market.market_record record
SET sample_point_id=identity.new_sample_point_id
FROM v147_formal_record_rebind rebind
JOIN v147_formal_identity_group identity
  ON identity.source_domain=rebind.source_domain
 AND identity.old_sample_point_id=rebind.old_sample_point_id
 AND identity.canonical_name=rebind.canonical_name
 AND identity.correct_region_code=rebind.correct_region_code
 AND identity.longitude=rebind.longitude
 AND identity.latitude=rebind.latitude
WHERE rebind.source_domain='MARKET'
  AND record.record_id=rebind.source_record_id
  AND record.sample_point_id=rebind.old_sample_point_id;

-- Formal projections use publication state and lifecycle only. Coordinate,
-- region and duplicate checks already ran before the business record existed.
CREATE OR REPLACE VIEW overview.approved_sample_point_source AS
WITH approved_source AS (
    SELECT record.sample_point_id,'PRODUCTION'::varchar(30) source_domain,
           record.record_id::varchar(120) source_record_id,
           'SURVEY'::varchar(20) source_role,record.product_code,
           record.survey_date occurrence_date,record.version source_version,
           NULL::uuid party_id,'SURVEY_SITE'::varchar(40) expected_kind_code
    FROM production.production_record record
    WHERE record.status_code='APPROVED'
      AND record.survey_period_governance_state='CONFIRMED'
    UNION ALL
    SELECT record.sample_point_id,'MARKET'::varchar(30),
           record.record_id::varchar(120),'SURVEY'::varchar(20),
           record.product_code,record.trade_date,record.version,
           record.party_id,'SURVEY_SITE'::varchar(40)
    FROM market.market_record record
    WHERE record.status_code='APPROVED'
      AND record.survey_period_governance_state='CONFIRMED'
    UNION ALL
    SELECT node.sample_point_id,'LOGISTICS'::varchar(30),
           event.event_id::text::varchar(120),'ORIGIN'::varchar(20),
           event.product_code,event.collection_date,event.version,NULL::uuid,
           'LOGISTICS_NODE'::varchar(40)
    FROM logistics.route_event event
    JOIN logistics.logistics_node node ON node.node_code=event.origin_node_code
    WHERE event.status_code='APPROVED'
      AND event.survey_period_governance_state='CONFIRMED'
    UNION ALL
    SELECT node.sample_point_id,'LOGISTICS'::varchar(30),
           event.event_id::text::varchar(120),'DESTINATION'::varchar(20),
           event.product_code,event.collection_date,event.version,NULL::uuid,
           'LOGISTICS_NODE'::varchar(40)
    FROM logistics.route_event event
    JOIN logistics.logistics_node node ON node.node_code=event.destination_node_code
    WHERE event.status_code='APPROVED'
      AND event.survey_period_governance_state='CONFIRMED'
)
SELECT source.sample_point_id,source.source_domain,source.source_record_id,
       source.source_role,source.product_code,source.occurrence_date,
       source.source_version,source.party_id,point.region_code governed_region_code,
       point.kind_code sample_point_kind_code,point.governed_point point_geometry
FROM approved_source source
JOIN registry.sample_point point ON point.sample_point_id=source.sample_point_id
WHERE point.approval_state='APPROVED'
  AND point.kind_code=source.expected_kind_code
  AND point.location_state='VALID'
  AND point.governed_point IS NOT NULL
  AND source.occurrence_date>=point.effective_from
  AND (point.effective_to IS NULL OR source.occurrence_date<=point.effective_to);

COMMENT ON VIEW overview.approved_sample_point_source IS
    'Published APPROVED + CONFIRMED business associations. Ingress owns validation; reads trust the stable formal identity.';
GRANT SELECT ON TABLE overview.approved_sample_point_source TO qiqihar_enterprise_runtime;

-- Keep the direct logistics survey role on the same publication contract.
-- Boundary hashes and containment belong to ingress, not to every dashboard read.
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
    WHERE event.status_code='APPROVED'
      AND event.survey_period_governance_state='CONFIRMED'
      AND point.approval_state='APPROVED' AND point.location_state='VALID'
      AND point.governed_point IS NOT NULL
      AND event.collection_date>=point.effective_from
      AND (point.effective_to IS NULL OR event.collection_date<=point.effective_to)
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
    'Approved and confirmed formal business roles. Ingress owns coordinate, region and duplicate validation.';
GRANT SELECT ON TABLE overview.sample_point_query_source TO qiqihar_enterprise_runtime;
