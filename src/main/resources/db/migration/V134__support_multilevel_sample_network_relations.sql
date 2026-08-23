DROP TRIGGER IF EXISTS sample_network_membership_village_guard
ON registry.sample_network_membership;
DROP FUNCTION IF EXISTS registry.guard_sample_network_membership_village();
ALTER TABLE registry.sample_network_membership
    ALTER COLUMN village_region_code DROP NOT NULL;

CREATE TABLE registry.sample_network_design_relation (
    network_year smallint NOT NULL,
    sample_point_id uuid NOT NULL,
    design_village_region_code varchar(12) NOT NULL
        REFERENCES platform.region(code) ON DELETE RESTRICT,
    relation_type varchar(30) NOT NULL,
    evidence_reference varchar(500),
    review_status varchar(30) NOT NULL DEFAULT 'PENDING_REVIEW',
    created_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    reviewed_by varchar(120) REFERENCES platform.security_user(subject_id),
    reviewed_at timestamptz,
    PRIMARY KEY(network_year,sample_point_id,design_village_region_code,relation_type),
    FOREIGN KEY(network_year,sample_point_id)
      REFERENCES registry.sample_network_membership(network_year,sample_point_id)
      ON DELETE RESTRICT,
    CHECK (relation_type IN ('EXACT_VILLAGE','EXPLICIT_REPRESENTATION')),
    CHECK (review_status IN ('PENDING_REVIEW','APPROVED','RETURNED')),
    CHECK (relation_type<>'EXPLICIT_REPRESENTATION'
           OR (evidence_reference IS NOT NULL AND length(btrim(evidence_reference))>0)),
    CONSTRAINT sample_network_design_relation_review_shape_check CHECK (
        (review_status='PENDING_REVIEW' AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR
        (review_status='APPROVED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
            AND reviewed_by<>created_by)
        OR
        (review_status='RETURNED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

INSERT INTO registry.sample_network_design_relation(
  network_year,sample_point_id,design_village_region_code,relation_type,
  evidence_reference,review_status,created_by,created_at,reviewed_by,reviewed_at)
SELECT membership.network_year,membership.sample_point_id,membership.village_region_code,
       'EXACT_VILLAGE','V133 annual membership migration',
       CASE WHEN network.status_code='PUBLISHED' THEN 'APPROVED'
            ELSE 'PENDING_REVIEW' END,
       membership.created_by,membership.created_at,
       CASE WHEN network.status_code='PUBLISHED' THEN network.reviewed_by END,
       CASE WHEN network.status_code='PUBLISHED' THEN network.reviewed_at END
FROM registry.sample_network_membership membership
JOIN registry.sample_network_year network USING(network_year)
WHERE membership.village_region_code IS NOT NULL;

CREATE FUNCTION registry.guard_sample_network_design_relation_village()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog,platform,registry
AS $$
DECLARE
    membership_region_level varchar(30);
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM platform.region
        WHERE code=NEW.design_village_region_code AND administrative_level='VILLAGE'
    ) THEN
        RAISE EXCEPTION 'sample network design relation village must be a village'
            USING ERRCODE='23514';
    END IF;
    SELECT region.administrative_level INTO membership_region_level
    FROM registry.sample_network_membership membership
    JOIN registry.sample_point point ON point.sample_point_id=membership.sample_point_id
    JOIN platform.region region ON region.code=point.region_code
    WHERE membership.network_year=NEW.network_year
      AND membership.sample_point_id=NEW.sample_point_id;
    IF NEW.relation_type='EXACT_VILLAGE'
       AND membership_region_level IS NOT NULL
       AND membership_region_level<>'VILLAGE' THEN
        RAISE EXCEPTION 'EXACT_VILLAGE relation requires a village-level sample member'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER sample_network_design_relation_village_guard
BEFORE INSERT OR UPDATE OF network_year,sample_point_id,design_village_region_code,relation_type
ON registry.sample_network_design_relation
FOR EACH ROW EXECUTE FUNCTION registry.guard_sample_network_design_relation_village();

CREATE INDEX sample_network_design_relation_village_lookup
    ON registry.sample_network_design_relation(
        network_year,design_village_region_code,review_status,sample_point_id);

ALTER TABLE registry.sample_network_design_relation OWNER TO qiqihar_migration_owner;
ALTER FUNCTION registry.guard_sample_network_design_relation_village()
    OWNER TO qiqihar_migration_owner;

REVOKE ALL ON TABLE registry.sample_network_design_relation FROM PUBLIC;
REVOKE ALL ON FUNCTION registry.guard_sample_network_design_relation_village()
FROM PUBLIC;

GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE registry.sample_network_design_relation
TO qiqihar_enterprise_runtime,CURRENT_USER;

COMMENT ON COLUMN registry.sample_network_membership.village_region_code IS
    'Read-only compatibility field for legacy village memberships; new coverage calculations use sample_network_design_relation.';
COMMENT ON TABLE registry.sample_network_design_relation IS
    'Reviewed relationship from an annual real-sample member to an administrative-village design reference.';
COMMENT ON FUNCTION registry.guard_sample_network_design_relation_village() IS
    'Requires an administrative-village design reference and permits EXACT_VILLAGE only for village-level members.';
