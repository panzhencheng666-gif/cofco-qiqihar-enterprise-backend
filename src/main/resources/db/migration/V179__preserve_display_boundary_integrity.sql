-- Geometry validity did not protect geo_json: rounding a valid narrow polygon
-- to 7/9 decimal places can collapse edges and introduce self intersections.
-- Keep the stored display geometry unchanged and serialize its full precision.
-- This does not replace boundary sources, move sample coordinates or enlarge areas.
CREATE FUNCTION overview.enforce_display_boundary_integrity()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,overview,platform,public
AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF EXISTS(SELECT 1 FROM platform.design_sample_point WHERE region_code=OLD.region_code) THEN
            RAISE EXCEPTION 'cannot remove boundary % with existing design sample points',OLD.region_code
                USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;

    NEW.geo_json := public.ST_AsGeoJSON(NEW.geometry,15,0);
    IF NOT public.ST_IsValid(public.ST_GeomFromGeoJSON(NEW.geo_json),1)
       OR public.ST_IsEmpty(public.ST_GeomFromGeoJSON(NEW.geo_json)) THEN
        RAISE EXCEPTION 'invalid serialized display boundary %',NEW.region_code
            USING ERRCODE='23514';
    END IF;

    IF EXISTS(
        SELECT 1 FROM platform.design_sample_point point
        WHERE point.region_code=NEW.region_code
          AND NOT public.ST_Covers(public.ST_GeomFromGeoJSON(NEW.geo_json),point.governed_point)
    ) THEN
        RAISE EXCEPTION 'boundary % would exclude an existing design sample point',NEW.region_code
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION overview.enforce_display_boundary_integrity() FROM PUBLIC;

CREATE TRIGGER administrative_display_boundary_integrity_guard
BEFORE INSERT OR UPDATE OF geometry,geo_json OR DELETE
ON overview.administrative_boundary_render
FOR EACH ROW EXECUTE FUNCTION overview.enforce_display_boundary_integrity();

CREATE OR REPLACE FUNCTION platform.enforce_design_sample_point_containment()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,platform,overview
AS $$
BEGIN
    -- Serialize coordinate writes with boundary replacement/removal. Without
    -- this lock, concurrent validations could both pass against old snapshots.
    PERFORM 1 FROM overview.administrative_boundary_render
    WHERE region_code=NEW.region_code FOR SHARE;
    IF overview.design_sample_display_boundary_state(
        NEW.region_code,public.ST_X(NEW.governed_point)::numeric,
        public.ST_Y(NEW.governed_point)::numeric) IS DISTINCT FROM 'INSIDE' THEN
        RAISE EXCEPTION 'design sample point coordinate outside displayed administrative region %',
            NEW.region_code USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

-- Re-encode only the display payload. The boundary geometry, source provenance
-- and every saved point remain untouched; existing-point checks still apply.
UPDATE overview.administrative_boundary_render
SET geo_json=public.ST_AsGeoJSON(geometry,15,0),refreshed_at=now()
WHERE geo_json IS DISTINCT FROM public.ST_AsGeoJSON(geometry,15,0);

COMMENT ON TRIGGER administrative_display_boundary_integrity_guard
ON overview.administrative_boundary_render IS
    'Serializes the authoritative display geometry without precision loss and prevents boundary changes from excluding stored design sample points.';
