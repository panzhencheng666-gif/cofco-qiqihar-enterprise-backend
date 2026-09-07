-- The same serialized boundary is returned to the map. Do not validate a
-- design-reference coordinate against a different community source polygon.
-- Original coordinates, numeric constraints, region references and write scopes
-- remain unchanged; this does not certify survey accuracy or relax containment.
CREATE FUNCTION overview.design_sample_display_boundary_state(
    requested_region varchar, longitude numeric, latitude numeric)
RETURNS text
LANGUAGE sql
STABLE
SET search_path = pg_catalog,platform,overview,public
AS $$
    SELECT CASE
      WHEN boundary.region_code IS NULL THEN 'UNAVAILABLE'
      WHEN public.ST_Covers(
        public.ST_SetSRID(public.ST_GeomFromGeoJSON(boundary.geo_json),4326),
        public.ST_SetSRID(public.ST_MakePoint(longitude,latitude),4326)) THEN 'INSIDE'
      ELSE 'OUTSIDE'
    END
    FROM platform.region region
    LEFT JOIN overview.administrative_boundary_render boundary ON boundary.region_code=region.code
    WHERE region.code=requested_region
$$;

REVOKE ALL ON FUNCTION overview.design_sample_display_boundary_state(varchar,numeric,numeric) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION overview.design_sample_display_boundary_state(varchar,numeric,numeric)
    TO qiqihar_enterprise_runtime,CURRENT_USER;

CREATE OR REPLACE FUNCTION platform.enforce_design_sample_point_containment()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,platform,overview
AS $$
BEGIN
    IF overview.design_sample_display_boundary_state(
        NEW.region_code,public.ST_X(NEW.governed_point)::numeric,
        public.ST_Y(NEW.governed_point)::numeric) IS DISTINCT FROM 'INSIDE' THEN
        RAISE EXCEPTION 'design sample point coordinate outside displayed administrative region %',
            NEW.region_code USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON COLUMN platform.design_sample_point.governed_point IS
    'Original coordinate preserved after numeric validation and containment in the same serialized regional boundary displayed by the map. No snapping or replacement coordinate is applied.';
