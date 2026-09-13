-- The singleton row serializes writers until commit. An outbox sequence cannot
-- provide this guarantee: a smaller sequence may commit after a larger one.
CREATE TABLE overview.map_revision (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 revision uuid NOT NULL DEFAULT gen_random_uuid()
);
INSERT INTO overview.map_revision DEFAULT VALUES;
GRANT SELECT ON overview.map_revision TO qiqihar_enterprise_runtime;
CREATE FUNCTION overview.advance_map_revision() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,overview,public AS $$
BEGIN
 UPDATE overview.map_revision SET revision=gen_random_uuid() WHERE singleton;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION overview.advance_map_revision() FROM PUBLIC;
CREATE TRIGGER overview_map_revision AFTER INSERT OR UPDATE OR DELETE
ON registry.sample_point FOR EACH STATEMENT EXECUTE FUNCTION overview.advance_map_revision();
CREATE TRIGGER overview_map_revision AFTER INSERT OR UPDATE OR DELETE
ON registry.formal_sample_point_profile FOR EACH STATEMENT EXECUTE FUNCTION overview.advance_map_revision();
CREATE TRIGGER overview_map_revision AFTER INSERT OR UPDATE OR DELETE
ON platform.design_sample_point FOR EACH STATEMENT EXECUTE FUNCTION overview.advance_map_revision();
CREATE TRIGGER overview_map_revision AFTER INSERT OR UPDATE OR DELETE
ON overview.administrative_boundary_render FOR EACH STATEMENT EXECUTE FUNCTION overview.advance_map_revision();
