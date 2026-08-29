-- Link formal logistics records approved before V140 to the same stable visible
-- identity used by production and market. Product and business role are facts of
-- a record; neither is part of the physical sample identity.
CREATE TEMP TABLE logistics_sample_backfill ON COMMIT DROP AS
SELECT event.event_id,
       lower(regexp_replace(normalize(btrim(event.source_organization),NFKC),
             '[[:space:]]+','','g')) name_key,
       lower(regexp_replace(normalize(btrim(event.sample_contact),NFKC),
             '[[:space:]()（）-]+','','g')) contact_key,
       event.source_organization canonical_name,event.sample_contact,
       event.business_region_code region_code,event.sample_longitude,event.sample_latitude,
       event.collection_date,event.created_by,event.created_at,
       event.last_modified_by,event.updated_at
FROM logistics.route_event event
WHERE event.status_code='APPROVED'
  AND event.survey_period_governance_state='CONFIRMED'
  AND event.sample_point_id IS NULL;

DO $$
BEGIN
  IF EXISTS(SELECT 1 FROM logistics_sample_backfill
            WHERE name_key='' OR contact_key='' OR region_code IS NULL
               OR sample_longitude IS NULL OR sample_latitude IS NULL) THEN
    RAISE EXCEPTION 'Approved logistics sample backfill requires complete visible identity and coordinates';
  END IF;
END;
$$;

CREATE TEMP TABLE logistics_sample_exact_match ON COMMIT DROP AS
WITH visible_candidate AS (
  SELECT identity.business_identity,record.sample_point_id
  FROM production.production_record record
  JOIN production.production_record_business_identity identity USING(record_id)
  JOIN registry.sample_point point USING(sample_point_id)
  WHERE record.status_code='APPROVED'
    AND record.survey_period_governance_state='CONFIRMED'
    AND point.approval_state='APPROVED' AND point.location_state='VALID'
  UNION
  SELECT identity.business_identity,record.sample_point_id
  FROM market.market_record record
  JOIN market.market_record_business_identity identity USING(record_id)
  JOIN registry.sample_point point USING(sample_point_id)
  WHERE record.status_code='APPROVED'
    AND record.survey_period_governance_state='CONFIRMED'
    AND point.approval_state='APPROVED' AND point.location_state='VALID'
)
SELECT backfill.event_id,candidate.sample_point_id
FROM logistics_sample_backfill backfill
JOIN visible_candidate candidate
  ON candidate.business_identity='VISIBLE|'||backfill.name_key||'|'||backfill.contact_key;

DO $$
BEGIN
  IF EXISTS(SELECT event_id FROM logistics_sample_exact_match
            GROUP BY event_id HAVING count(DISTINCT sample_point_id)>1) THEN
    RAISE EXCEPTION 'Approved logistics sample identity matches multiple governed sample points';
  END IF;
END;
$$;

UPDATE logistics.route_event event
SET sample_point_id=matched.sample_point_id
FROM (SELECT event_id,min(sample_point_id::text)::uuid sample_point_id
      FROM logistics_sample_exact_match GROUP BY event_id) matched
WHERE event.event_id=matched.event_id AND event.sample_point_id IS NULL;

DELETE FROM logistics_sample_backfill backfill
WHERE EXISTS(SELECT 1 FROM logistics.route_event event
             WHERE event.event_id=backfill.event_id AND event.sample_point_id IS NOT NULL);

DO $$
BEGIN
  IF EXISTS(
    SELECT 1 FROM logistics_sample_backfill backfill
    LEFT JOIN overview.administrative_boundary boundary ON boundary.region_code=backfill.region_code
    WHERE boundary.region_code IS NULL
       OR NOT ST_Covers(boundary.geometry,
              ST_SetSRID(ST_MakePoint(backfill.sample_longitude,backfill.sample_latitude),4326))) THEN
    RAISE EXCEPTION 'Approved logistics sample coordinates are outside their governed boundary';
  END IF;
  IF EXISTS(
    SELECT 1 FROM logistics_sample_backfill backfill
    JOIN registry.sample_point point
      ON point.location_state='VALID'
     AND ST_Equals(point.governed_point,
           ST_SetSRID(ST_MakePoint(backfill.sample_longitude,backfill.sample_latitude),4326))) THEN
    RAISE EXCEPTION 'Approved logistics sample coordinates are already occupied and require identity review';
  END IF;
  IF EXISTS(
    SELECT name_key,contact_key FROM logistics_sample_backfill
    GROUP BY name_key,contact_key
    HAVING count(DISTINCT region_code)>1
       OR count(DISTINCT (sample_longitude::text||','||sample_latitude::text))>1) THEN
    RAISE EXCEPTION 'One approved logistics identity has conflicting governed locations';
  END IF;
END;
$$;

CREATE TEMP TABLE logistics_sample_new_identity ON COMMIT DROP AS
SELECT gen_random_uuid() sample_point_id,name_key,contact_key,
       min(canonical_name) canonical_name,min(region_code) region_code,
       min(sample_longitude) sample_longitude,min(sample_latitude) sample_latitude,
       min(collection_date) effective_from,min(created_by) created_by,min(created_at) created_at,
       max(last_modified_by) updated_by,max(updated_at) updated_at
FROM logistics_sample_backfill
GROUP BY name_key,contact_key;

INSERT INTO registry.sample_point(
  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
  governed_point,effective_from,version,created_by,created_at,updated_by,updated_at)
SELECT sample_point_id,'LOGISTICS_NODE',canonical_name,region_code,'APPROVED','VALID',
       ST_SetSRID(ST_MakePoint(sample_longitude,sample_latitude),4326),effective_from,0,
       created_by,created_at,updated_by,updated_at
FROM logistics_sample_new_identity;

UPDATE logistics.route_event event
SET sample_point_id=identity.sample_point_id
FROM logistics_sample_backfill backfill
JOIN logistics_sample_new_identity identity USING(name_key,contact_key)
WHERE event.event_id=backfill.event_id AND event.sample_point_id IS NULL;

-- A stable identity can carry production, market and logistics roles. Do not
-- require a logistics-only point kind when projecting a formal logistics fact.
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
    SELECT unresolved.sample_point_id,unresolved.source_domain,unresolved.source_record_id,
           unresolved.source_role,unresolved.product_code,unresolved.occurrence_date,
           unresolved.source_version,unresolved.party_id,unresolved.governed_region_code,
           unresolved.sample_point_kind_code,unresolved.unresolved_reason,
           unresolved.point_geometry
    FROM overview.unresolved_approved_sample_point_source unresolved
    WHERE unresolved.source_domain<>'LOGISTICS' OR NOT EXISTS(
      SELECT 1 FROM logistics.route_event event
      WHERE event.event_id::text=unresolved.source_record_id AND event.sample_point_id IS NOT NULL)
    UNION ALL
    SELECT point.sample_point_id,'LOGISTICS'::varchar(30),event.event_id::text::varchar(120),
           'SURVEY'::varchar(20),event.product_code,event.collection_date,event.version,
           NULL::uuid,point.region_code,point.kind_code,NULL::varchar(40),point.governed_point
    FROM logistics.route_event event
    JOIN registry.sample_point point ON point.sample_point_id=event.sample_point_id
    JOIN overview.administrative_boundary boundary ON boundary.region_code=point.region_code
    WHERE event.status_code='APPROVED'
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
    UNION ALL
    SELECT source.*,record.region_code,record.object_type_code,record.updated_at
    FROM source JOIN market.market_record record
      ON source.source_domain='MARKET' AND source.source_role='SURVEY'
     AND record.record_id=source.source_record_id
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
LEFT JOIN registry.sample_point point ON point.sample_point_id=enriched.sample_point_id;

COMMENT ON VIEW overview.sample_point_query_source IS
    'Approved formal production, market and logistics roles projected from stable product-independent sample identities.';
GRANT SELECT ON TABLE overview.sample_point_query_source TO qiqihar_enterprise_runtime;
