ALTER TABLE overview.storage_facility
    ADD COLUMN source_origin varchar(30) NOT NULL DEFAULT 'USER_SUBMITTED'
        CHECK (source_origin IN ('USER_SUBMITTED','PUBLIC_DISCOVERY')),
    ADD COLUMN created_by_subject varchar(160),
    ADD COLUMN updated_by_subject varchar(160),
    ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN archived boolean NOT NULL DEFAULT false;

-- V210 contained one conservative public discovery example. Associated depots now come only
-- from authenticated user submissions, so remove that exact bootstrap row and its cascading
-- evidence/price rows without touching any other facility record.
UPDATE overview.storage_facility
SET source_origin='PUBLIC_DISCOVERY'
WHERE facility_code='KESHAN_DEPOT'
  AND facility_name='中粮贸易（克山）粮食储运有限公司'
  AND created_by_subject IS NULL;

DELETE FROM overview.storage_facility
WHERE facility_code='KESHAN_DEPOT'
  AND source_origin='PUBLIC_DISCOVERY'
  AND created_by_subject IS NULL;

GRANT INSERT,UPDATE,DELETE ON overview.storage_facility TO qiqihar_enterprise_runtime;

COMMENT ON COLUMN overview.storage_facility.source_origin IS
    'USER_SUBMITTED rows are maintained by authenticated users. PUBLIC_DISCOVERY is retained only for forward-compatible provenance and is never created by the application.';
