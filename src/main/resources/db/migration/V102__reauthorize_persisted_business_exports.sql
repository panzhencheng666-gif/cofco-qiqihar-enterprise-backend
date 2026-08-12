ALTER TABLE workflow.obligation_report_export
    ADD COLUMN authorized_region_codes varchar(32)[] NOT NULL DEFAULT ARRAY[]::varchar(32)[];

COMMENT ON COLUMN workflow.obligation_report_export.authorized_region_codes IS
    'Artifact authorization snapshot; downloads must revalidate this complete set against current grants.';
