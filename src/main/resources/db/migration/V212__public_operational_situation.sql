CREATE TABLE overview.public_event_snapshot (
    source_code varchar(40) NOT NULL,
    event_id varchar(120) NOT NULL,
    title varchar(300) NOT NULL CHECK (btrim(title) <> ''),
    description text,
    category_code varchar(80) NOT NULL,
    category_label varchar(160) NOT NULL,
    longitude numeric(10,7) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    latitude numeric(10,7) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    observed_at timestamptz NOT NULL,
    magnitude_value numeric(20,4),
    magnitude_unit varchar(80),
    event_url text NOT NULL CHECK (event_url LIKE 'https://%'),
    evidence_url text CHECK (evidence_url IS NULL OR evidence_url LIKE 'https://%'),
    fetched_at timestamptz NOT NULL,
    PRIMARY KEY (source_code,event_id)
);

CREATE TABLE overview.public_event_refresh_state (
    source_code varchar(40) PRIMARY KEY,
    source_name varchar(160) NOT NULL,
    source_url text NOT NULL CHECK (source_url LIKE 'https://%'),
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    last_error text,
    record_count integer NOT NULL DEFAULT 0 CHECK (record_count >= 0)
);

INSERT INTO overview.public_event_refresh_state(source_code,source_name,source_url)
VALUES ('NASA_EONET','NASA EONET','https://eonet.gsfc.nasa.gov/api/v3/events');

CREATE INDEX public_event_snapshot_observed
    ON overview.public_event_snapshot(observed_at DESC,event_id);
CREATE INDEX public_event_snapshot_location
    ON overview.public_event_snapshot(longitude,latitude);

GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE
    overview.public_event_snapshot,
    overview.public_event_refresh_state
TO qiqihar_enterprise_runtime;

COMMENT ON TABLE overview.public_event_snapshot IS
    'Cached open natural-event observations from named public sources. Successful refresh replaces the source snapshot atomically; failures preserve the last successful snapshot.';
