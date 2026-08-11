CREATE TABLE market.business_party (
    party_id uuid PRIMARY KEY,
    current_name varchar(200) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    CHECK (btrim(current_name) <> ''),
    CHECK (version >= 0)
);

CREATE INDEX business_party_by_current_name
    ON market.business_party(lower(btrim(current_name)),party_id);

CREATE TABLE market.business_party_revision (
    revision_id uuid PRIMARY KEY,
    party_id uuid NOT NULL REFERENCES market.business_party(party_id),
    party_version bigint NOT NULL CHECK (party_version >= 0),
    change_type varchar(60) NOT NULL,
    snapshot_json jsonb NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    UNIQUE (party_id, party_version),
    CHECK (change_type IN ('MIGRATED_FROM_MONITORING_OBJECT','CREATED','UPDATED'))
);

CREATE OR REPLACE FUNCTION market.reject_business_party_revision_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'business party revisions are append-only';
END;
$$;

CREATE TRIGGER business_party_revision_no_update
BEFORE UPDATE OR DELETE ON market.business_party_revision
FOR EACH ROW EXECUTE FUNCTION market.reject_business_party_revision_mutation();

INSERT INTO market.business_party(
    party_id,current_name,version,created_at,created_by,updated_at,updated_by
)
SELECT object_id,object_name,version,created_at,updated_by,updated_at,updated_by
FROM market.monitoring_object;

INSERT INTO market.business_party_revision(
    revision_id,party_id,party_version,change_type,snapshot_json,recorded_at,recorded_by
)
SELECT object_id,object_id,version,'MIGRATED_FROM_MONITORING_OBJECT',
       jsonb_build_object(
           'partyId',object_id::text,
           'currentName',object_name,
           'migrationSource',jsonb_build_object(
               'type','MARKET_MONITORING_OBJECT',
               'objectId',object_id::text
           )
       ),
       updated_at,updated_by
FROM market.monitoring_object;

DROP INDEX market.monitoring_object_duplicate_name_guard;

ALTER TABLE market.monitoring_object ADD COLUMN party_id uuid;
UPDATE market.monitoring_object SET party_id=object_id;
ALTER TABLE market.monitoring_object ALTER COLUMN party_id SET NOT NULL;
ALTER TABLE market.monitoring_object
    ADD CONSTRAINT monitoring_object_business_party_fk
    FOREIGN KEY (party_id) REFERENCES market.business_party(party_id);
ALTER TABLE market.monitoring_object
    ADD CONSTRAINT monitoring_object_one_dossier_per_party UNIQUE (party_id);

COMMENT ON TABLE market.business_party IS
    'Stable internal identity for a real business party; the current name is a mutable attribute.';
COMMENT ON TABLE market.business_party_revision IS
    'Append-only auditable snapshots of business-party names.';
COMMENT ON COLUMN market.monitoring_object.object_name IS
    'Legacy-compatible current-name projection; stable identity is monitoring_object.party_id.';
