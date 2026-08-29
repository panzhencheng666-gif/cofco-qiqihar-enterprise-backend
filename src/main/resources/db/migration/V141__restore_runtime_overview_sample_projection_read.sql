-- Recreating a PostgreSQL view drops its object-level grants. V140 replaced the
-- overview projection, so restore the existing runtime read boundary explicitly.
GRANT SELECT ON TABLE overview.sample_point_query_source
TO qiqihar_enterprise_runtime;
