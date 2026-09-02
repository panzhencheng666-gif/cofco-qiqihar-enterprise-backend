ALTER TABLE registry.sample_point
ADD COLUMN maintainer_subject_id varchar(120)
    REFERENCES platform.security_user(subject_id);

CREATE INDEX sample_point_maintainer
ON registry.sample_point(maintainer_subject_id,sample_point_id)
WHERE maintainer_subject_id IS NOT NULL;

COMMENT ON COLUMN registry.sample_point.maintainer_subject_id IS
    'Current employee responsible for periodic formal-sample observations; historical rows remain unassigned until an administrator explicitly assigns an active employee.';
