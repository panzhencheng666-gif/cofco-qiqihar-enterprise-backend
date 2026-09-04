ALTER TABLE registry.sample_point
    ADD COLUMN retired_at timestamptz,
    ADD COLUMN retired_by varchar(120)
        REFERENCES platform.security_user(subject_id),
    ADD COLUMN retired_reason varchar(500);

ALTER TABLE registry.sample_point
    DROP CONSTRAINT sample_point_deletion_state_check,
    ADD CONSTRAINT sample_point_deletion_state_check CHECK (
        (deletion_state='ACTIVE'
          AND deleted_at IS NULL AND deleted_by IS NULL
          AND retired_at IS NULL AND retired_by IS NULL AND retired_reason IS NULL)
        OR (deletion_state='RETIRED'
          AND deleted_at IS NULL AND deleted_by IS NULL
          AND retired_at IS NOT NULL AND retired_by IS NOT NULL
          AND retired_reason IS NOT NULL AND btrim(retired_reason)<>'')
        OR (deletion_state='DELETED'
          AND deleted_at IS NOT NULL AND deleted_by IS NOT NULL
          AND retired_at IS NULL AND retired_by IS NULL AND retired_reason IS NULL));

CREATE INDEX sample_point_retirement_lookup
    ON registry.sample_point(deletion_state,retired_at,sample_point_id)
    WHERE deletion_state='RETIRED';

GRANT UPDATE(deletion_state,effective_to,retired_at,retired_by,retired_reason,
    version,updated_by,updated_at)
ON registry.sample_point TO qiqihar_enterprise_runtime;

COMMENT ON COLUMN registry.sample_point.retired_at IS
    'Business retirement instant. Retired samples remain durable historical records.';
COMMENT ON COLUMN registry.sample_point.retired_reason IS
    'Human-entered reason for moving an active sample into historical display.';
