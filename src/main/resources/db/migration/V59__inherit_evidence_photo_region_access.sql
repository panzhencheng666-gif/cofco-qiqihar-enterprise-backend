ALTER TABLE evidence.evidence_photo
    ADD COLUMN attached_region_code varchar(12) REFERENCES platform.region(code);

ALTER TABLE evidence.evidence_photo
    ADD CONSTRAINT evidence_photo_attached_region_check CHECK (
        (state_code = 'STAGED' AND attached_region_code IS NULL)
        OR (state_code = 'ATTACHED' AND attached_region_code IS NOT NULL)
    );

COMMENT ON COLUMN evidence.evidence_photo.attached_region_code IS
    'Region inherited from the attached business record for private read authorization.';
