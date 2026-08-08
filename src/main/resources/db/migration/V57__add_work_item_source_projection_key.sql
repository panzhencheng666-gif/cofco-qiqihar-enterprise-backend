ALTER TABLE workflow.work_item
    ADD COLUMN source_type varchar(30),
    ADD COLUMN source_id varchar(80);

ALTER TABLE workflow.work_item
    ADD CONSTRAINT work_item_source_key_unique UNIQUE (source_type, source_id);

COMMENT ON COLUMN workflow.work_item.source_type IS
    'Source business domain for a locally projected work item; null keeps legacy fixtures valid.';
COMMENT ON COLUMN workflow.work_item.source_id IS
    'Stable source record identifier used to idempotently refresh local work-item projections.';
