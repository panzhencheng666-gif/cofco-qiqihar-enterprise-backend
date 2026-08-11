CREATE SCHEMA registry;

CREATE TABLE registry.sample_point_kind_definition (
    code varchar(40) PRIMARY KEY,
    name varchar(100) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL,
    CHECK (btrim(code) <> ''),
    CHECK (btrim(name) <> ''),
    CHECK (sort_order > 0)
);

INSERT INTO registry.sample_point_kind_definition(code,name,sort_order) VALUES
    ('SURVEY_SITE','调查采样点',10),
    ('LOGISTICS_NODE','物流节点',20);

CREATE TABLE registry.sample_point (
    sample_point_id uuid PRIMARY KEY,
    kind_code varchar(40) NOT NULL
        REFERENCES registry.sample_point_kind_definition(code),
    owner_party_id uuid REFERENCES market.business_party(party_id),
    canonical_name varchar(200) NOT NULL,
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    approval_state varchar(30) NOT NULL,
    location_state varchar(30) NOT NULL,
    governed_point geometry(Point,4326),
    containment_boundary_sha256 char(64),
    containment_boundary_revision varchar(120),
    effective_from date NOT NULL,
    effective_to date,
    version bigint NOT NULL DEFAULT 0,
    created_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (btrim(canonical_name) <> ''),
    CHECK (approval_state IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED')),
    CHECK (location_state IN ('MISSING','VALID','INVALID','OUTSIDE_REGION')),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CHECK (version >= 0),
    CHECK (updated_at >= created_at),
    CHECK (governed_point IS NULL OR (
        ST_SRID(governed_point)=4326
        AND ST_NDims(governed_point)=2
        AND NOT ST_IsEmpty(governed_point)
        AND ST_IsValid(governed_point)
        AND ST_X(governed_point) BETWEEN -180 AND 180
        AND ST_Y(governed_point) BETWEEN -90 AND 90
    )),
    CHECK (
        (location_state='VALID'
          AND governed_point IS NOT NULL
          AND containment_boundary_sha256 IS NOT NULL
          AND containment_boundary_revision IS NOT NULL
          AND btrim(containment_boundary_revision) <> '')
        OR
        (location_state<>'VALID'
          AND governed_point IS NULL
          AND containment_boundary_sha256 IS NULL
          AND containment_boundary_revision IS NULL)
    )
);

CREATE INDEX sample_point_kind_lookup
    ON registry.sample_point(kind_code,sample_point_id);
CREATE INDEX sample_point_owner_lookup
    ON registry.sample_point(owner_party_id,sample_point_id)
    WHERE owner_party_id IS NOT NULL;
CREATE INDEX sample_point_region_lookup
    ON registry.sample_point(region_code,sample_point_id);
CREATE INDEX sample_point_governance_lookup
    ON registry.sample_point(approval_state,location_state,effective_from,effective_to,sample_point_id);
CREATE INDEX sample_point_canonical_name_lookup
    ON registry.sample_point(lower(btrim(canonical_name)),sample_point_id);
CREATE INDEX sample_point_governed_point_gix
    ON registry.sample_point USING GIST (governed_point)
    WHERE governed_point IS NOT NULL;

CREATE FUNCTION registry.enforce_sample_point_containment()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    boundary overview.administrative_boundary%ROWTYPE;
BEGIN
    IF NEW.location_state='VALID' THEN
        IF NEW.governed_point IS NULL THEN
            RAISE EXCEPTION 'VALID sample point requires governed geometry';
        END IF;

        SELECT * INTO boundary
        FROM overview.administrative_boundary
        WHERE region_code=NEW.region_code;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'No governed boundary exists for region %',NEW.region_code;
        END IF;
        IF NOT ST_Covers(boundary.geometry,NEW.governed_point) THEN
            RAISE EXCEPTION 'Sample point geometry is outside governed region %',NEW.region_code;
        END IF;

        NEW.containment_boundary_sha256:=boundary.geometry_sha256;
        NEW.containment_boundary_revision:=boundary.source_revision;
    ELSE
        IF NEW.governed_point IS NOT NULL
           OR NEW.containment_boundary_sha256 IS NOT NULL
           OR NEW.containment_boundary_revision IS NOT NULL THEN
            RAISE EXCEPTION 'Non-VALID sample point cannot retain governed geometry or containment evidence';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER sample_point_containment_guard
BEFORE INSERT OR UPDATE OF region_code,location_state,governed_point,
    containment_boundary_sha256,containment_boundary_revision
ON registry.sample_point
FOR EACH ROW EXECUTE FUNCTION registry.enforce_sample_point_containment();

ALTER TABLE production.production_record
    ADD COLUMN sample_point_id uuid REFERENCES registry.sample_point(sample_point_id);

ALTER TABLE market.market_record
    ADD COLUMN party_id uuid REFERENCES market.business_party(party_id),
    ADD COLUMN sample_point_id uuid REFERENCES registry.sample_point(sample_point_id);

ALTER TABLE logistics.logistics_node
    ADD COLUMN sample_point_id uuid REFERENCES registry.sample_point(sample_point_id);

CREATE INDEX production_record_sample_point_lookup
    ON production.production_record(sample_point_id,record_id)
    WHERE sample_point_id IS NOT NULL;
CREATE INDEX market_record_party_lookup
    ON market.market_record(party_id,record_id)
    WHERE party_id IS NOT NULL;
CREATE INDEX market_record_sample_point_lookup
    ON market.market_record(sample_point_id,record_id)
    WHERE sample_point_id IS NOT NULL;
CREATE INDEX logistics_node_sample_point_lookup
    ON logistics.logistics_node(sample_point_id,node_code)
    WHERE sample_point_id IS NOT NULL;

CREATE VIEW overview.approved_sample_point_source AS
WITH approved_source AS (
    SELECT record.sample_point_id,
           'PRODUCTION'::varchar(30) source_domain,
           record.record_id::varchar(120) source_record_id,
           'SURVEY'::varchar(20) source_role,
           record.product_code,
           record.survey_date occurrence_date,
           record.version source_version,
           NULL::uuid party_id,
           'SURVEY_SITE'::varchar(40) expected_kind_code
    FROM production.production_record record
    WHERE record.status_code='APPROVED'
    UNION ALL
    SELECT record.sample_point_id,
           'MARKET'::varchar(30),
           record.record_id::varchar(120),
           'SURVEY'::varchar(20),
           record.product_code,
           record.trade_date,
           record.version,
           record.party_id,
           'SURVEY_SITE'::varchar(40)
    FROM market.market_record record
    WHERE record.status_code='APPROVED'
    UNION ALL
    SELECT node.sample_point_id,
           'LOGISTICS'::varchar(30),
           event.event_id::text::varchar(120),
           'ORIGIN'::varchar(20),
           event.product_code,
           event.collection_date,
           event.version,
           NULL::uuid,
           'LOGISTICS_NODE'::varchar(40)
    FROM logistics.route_event event
    JOIN logistics.logistics_node node ON node.node_code=event.origin_node_code
    WHERE event.status_code='APPROVED'
    UNION ALL
    SELECT node.sample_point_id,
           'LOGISTICS'::varchar(30),
           event.event_id::text::varchar(120),
           'DESTINATION'::varchar(20),
           event.product_code,
           event.collection_date,
           event.version,
           NULL::uuid,
           'LOGISTICS_NODE'::varchar(40)
    FROM logistics.route_event event
    JOIN logistics.logistics_node node ON node.node_code=event.destination_node_code
    WHERE event.status_code='APPROVED'
)
SELECT source.sample_point_id,
       source.source_domain,
       source.source_record_id,
       source.source_role,
       source.product_code,
       source.occurrence_date,
       source.source_version,
       source.party_id,
       point.region_code governed_region_code,
       point.kind_code sample_point_kind_code,
       point.governed_point point_geometry
FROM approved_source source
JOIN registry.sample_point point ON point.sample_point_id=source.sample_point_id
JOIN overview.administrative_boundary boundary ON boundary.region_code=point.region_code
WHERE point.approval_state='APPROVED'
  AND point.kind_code=source.expected_kind_code
  AND point.location_state='VALID'
  AND source.occurrence_date>=point.effective_from
  AND (point.effective_to IS NULL OR source.occurrence_date<=point.effective_to)
  AND point.containment_boundary_sha256=boundary.geometry_sha256
  AND point.containment_boundary_revision=boundary.source_revision
  AND ST_Covers(boundary.geometry,point.governed_point);

CREATE VIEW overview.unresolved_approved_sample_point_source AS
WITH approved_source AS (
    SELECT record.sample_point_id,
           'PRODUCTION'::varchar(30) source_domain,
           record.record_id::varchar(120) source_record_id,
           'SURVEY'::varchar(20) source_role,
           record.product_code,
           record.survey_date occurrence_date,
           record.version source_version,
           NULL::uuid party_id,
           'SURVEY_SITE'::varchar(40) expected_kind_code
    FROM production.production_record record
    WHERE record.status_code='APPROVED'
    UNION ALL
    SELECT record.sample_point_id,
           'MARKET'::varchar(30),
           record.record_id::varchar(120),
           'SURVEY'::varchar(20),
           record.product_code,
           record.trade_date,
           record.version,
           record.party_id,
           'SURVEY_SITE'::varchar(40)
    FROM market.market_record record
    WHERE record.status_code='APPROVED'
    UNION ALL
    SELECT node.sample_point_id,
           'LOGISTICS'::varchar(30),
           event.event_id::text::varchar(120),
           'ORIGIN'::varchar(20),
           event.product_code,
           event.collection_date,
           event.version,
           NULL::uuid,
           'LOGISTICS_NODE'::varchar(40)
    FROM logistics.route_event event
    JOIN logistics.logistics_node node ON node.node_code=event.origin_node_code
    WHERE event.status_code='APPROVED'
    UNION ALL
    SELECT node.sample_point_id,
           'LOGISTICS'::varchar(30),
           event.event_id::text::varchar(120),
           'DESTINATION'::varchar(20),
           event.product_code,
           event.collection_date,
           event.version,
           NULL::uuid,
           'LOGISTICS_NODE'::varchar(40)
    FROM logistics.route_event event
    JOIN logistics.logistics_node node ON node.node_code=event.destination_node_code
    WHERE event.status_code='APPROVED'
)
SELECT source.sample_point_id,
       source.source_domain,
       source.source_record_id,
       source.source_role,
       source.product_code,
       source.occurrence_date,
       source.source_version,
       source.party_id,
       point.region_code governed_region_code,
       point.kind_code sample_point_kind_code,
       CASE
           WHEN source.sample_point_id IS NULL THEN 'UNLINKED_SOURCE'
           WHEN point.approval_state<>'APPROVED' THEN 'POINT_NOT_APPROVED'
           WHEN point.kind_code<>source.expected_kind_code THEN 'POINT_KIND_MISMATCH'
           WHEN point.location_state='MISSING' THEN 'LOCATION_MISSING'
           WHEN point.location_state='INVALID' THEN 'LOCATION_INVALID'
           WHEN point.location_state='OUTSIDE_REGION' THEN 'LOCATION_OUTSIDE_REGION'
           WHEN source.occurrence_date<point.effective_from
             OR (point.effective_to IS NOT NULL AND source.occurrence_date>point.effective_to)
               THEN 'OUTSIDE_VALIDITY_WINDOW'
           ELSE 'CONTAINMENT_EVIDENCE_STALE'
       END::varchar(40) unresolved_reason,
       NULL::geometry(Point,4326) point_geometry
FROM approved_source source
LEFT JOIN registry.sample_point point ON point.sample_point_id=source.sample_point_id
LEFT JOIN overview.administrative_boundary boundary ON boundary.region_code=point.region_code
WHERE source.sample_point_id IS NULL
   OR point.approval_state<>'APPROVED'
   OR point.kind_code<>source.expected_kind_code
   OR point.location_state<>'VALID'
   OR source.occurrence_date<point.effective_from
   OR (point.effective_to IS NOT NULL AND source.occurrence_date>point.effective_to)
   OR boundary.region_code IS NULL
   OR point.containment_boundary_sha256<>boundary.geometry_sha256
   OR point.containment_boundary_revision<>boundary.source_revision
   OR NOT ST_Covers(boundary.geometry,point.governed_point);

COMMENT ON TABLE registry.sample_point IS
    'Neutral governed place identity. Links are explicit; V93 performs no matching or historical backfill.';
COMMENT ON VIEW overview.approved_sample_point_source IS
    'Approved business source roles whose explicitly linked governed point is approved, valid, contained and effective.';
COMMENT ON VIEW overview.unresolved_approved_sample_point_source IS
    'Approved business source roles that cannot be resolved to drawable governed geometry, with deterministic reasons.';
