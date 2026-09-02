ALTER TABLE registry.formal_sample_point_profile
ADD COLUMN maintainer_subject_id varchar(120)
    REFERENCES platform.security_user(subject_id);

CREATE INDEX formal_sample_point_profile_maintainer
ON registry.formal_sample_point_profile(maintainer_subject_id,sample_point_id)
WHERE maintainer_subject_id IS NOT NULL;

COMMENT ON COLUMN registry.formal_sample_point_profile.maintainer_subject_id IS
    'Current employee responsible for periodic formal-sample observations; historical rows remain unassigned until an administrator explicitly assigns an active employee.';
