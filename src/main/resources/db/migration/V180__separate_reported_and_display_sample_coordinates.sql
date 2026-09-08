-- Reported values remain unchanged. A mismatched location no longer prevents
-- saving a design reference; only its separately labelled map position is placed
-- inside the exact administrative region selected by the user (at any level).
ALTER TABLE platform.design_sample_point
    ADD COLUMN display_point geometry(Point,4326),
    ADD COLUMN display_region_code varchar(12) REFERENCES platform.region(code),
    ADD COLUMN location_mode varchar(40);

CREATE OR REPLACE FUNCTION platform.enforce_design_sample_point_containment()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,platform,overview,public
AS $$
DECLARE selected_boundary public.geometry;
DECLARE placement_seed integer;
BEGIN
    SELECT public.ST_SetSRID(public.ST_GeomFromGeoJSON(geo_json),4326)
    INTO selected_boundary FROM overview.administrative_boundary_render
    WHERE region_code=NEW.region_code FOR SHARE;
    IF selected_boundary IS NULL OR public.ST_IsEmpty(selected_boundary)
       OR NOT public.ST_IsValid(selected_boundary) THEN
        RAISE EXCEPTION 'design sample display boundary unavailable for %',NEW.region_code
            USING ERRCODE='23514';
    END IF;
    NEW.display_region_code := NEW.region_code;
    IF public.ST_Covers(selected_boundary,NEW.governed_point) THEN
        NEW.display_point := NEW.governed_point;
        NEW.location_mode := 'REPORTED_COORDINATE';
    ELSE
        -- Preserve a previous valid schematic location across edits and boundary refreshes.
        IF TG_OP='UPDATE' AND OLD.region_code=NEW.region_code
           AND public.ST_Equals(OLD.governed_point,NEW.governed_point)
           AND OLD.location_mode='REGION_SCHEMATIC'
           AND public.ST_Covers(selected_boundary,OLD.display_point) THEN
            NEW.display_point := OLD.display_point;
        ELSE
            -- Seeded by persistent identity, so different samples do not all land
            -- on PointOnSurface. Prefer the candidate furthest from existing dots.
            placement_seed := 1 + ((('x'||substr(md5(NEW.design_sample_point_id::text),1,8))::bit(32)::bigint)
                                    % 2147483646)::integer;
            SELECT candidate.geom INTO NEW.display_point
            FROM public.ST_Dump(public.ST_GeneratePoints(selected_boundary,64,placement_seed)) candidate
            ORDER BY COALESCE((
                SELECT min(public.ST_Distance(candidate.geom,p.display_point))
                FROM platform.design_sample_point p
                WHERE p.region_code=NEW.region_code
                  AND p.design_sample_point_id<>NEW.design_sample_point_id
                  AND p.display_point IS NOT NULL
            ),0) DESC, candidate.path
            LIMIT 1;
            NEW.display_point := COALESCE(NEW.display_point,public.ST_PointOnSurface(selected_boundary));
        END IF;
        NEW.location_mode := 'REGION_SCHEMATIC';
    END IF;
    IF NEW.display_point IS NULL OR NOT public.ST_Covers(selected_boundary,NEW.display_point) THEN
        RAISE EXCEPTION 'design sample display coordinate outside region %',NEW.region_code
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER design_sample_point_containment_guard ON platform.design_sample_point;
CREATE TRIGGER design_sample_point_containment_guard
BEFORE INSERT OR UPDATE OF region_code,governed_point,display_point,display_region_code,location_mode
ON platform.design_sample_point
FOR EACH ROW EXECUTE FUNCTION platform.enforce_design_sample_point_containment();

UPDATE platform.design_sample_point SET governed_point=governed_point;
ALTER TABLE platform.design_sample_point
    ALTER COLUMN display_point SET NOT NULL,
    ALTER COLUMN display_region_code SET NOT NULL,
    ALTER COLUMN location_mode SET NOT NULL,
    ADD CONSTRAINT design_sample_display_region_matches CHECK(display_region_code=region_code),
    ADD CONSTRAINT design_sample_location_mode_valid
      CHECK(location_mode IN ('REPORTED_COORDINATE','REGION_SCHEMATIC'));
CREATE INDEX design_sample_display_point_gix ON platform.design_sample_point USING gist(display_point);

CREATE OR REPLACE FUNCTION overview.enforce_display_boundary_integrity()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,overview,platform,public
AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF EXISTS(SELECT 1 FROM platform.design_sample_point WHERE display_region_code=OLD.region_code) THEN
            RAISE EXCEPTION 'cannot remove boundary % with existing design sample points',OLD.region_code
                USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;
    NEW.geo_json := public.ST_AsGeoJSON(NEW.geometry,15,0);
    IF NOT public.ST_IsValid(public.ST_GeomFromGeoJSON(NEW.geo_json),1)
       OR public.ST_IsEmpty(public.ST_GeomFromGeoJSON(NEW.geo_json)) THEN
        RAISE EXCEPTION 'invalid serialized display boundary %',NEW.region_code USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION overview.refresh_design_sample_display_coordinates()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,overview,platform,public
AS $$
BEGIN
    UPDATE platform.design_sample_point SET governed_point=governed_point
    WHERE display_region_code=NEW.region_code;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION overview.refresh_design_sample_display_coordinates() FROM PUBLIC;
CREATE TRIGGER refresh_design_sample_display_coordinates
AFTER UPDATE OF geometry ON overview.administrative_boundary_render
FOR EACH ROW WHEN (OLD.geometry IS DISTINCT FROM NEW.geometry)
EXECUTE FUNCTION overview.refresh_design_sample_display_coordinates();

COMMENT ON COLUMN platform.design_sample_point.governed_point IS
 'Original reported coordinate, preserved independently from the map display coordinate.';
COMMENT ON COLUMN platform.design_sample_point.display_point IS
 'Reported coordinate when inside selected region; otherwise a deterministic interior schematic point. Never asserts a verified physical business location.';
COMMENT ON COLUMN platform.design_sample_point.location_mode IS
 'REPORTED_COORDINATE or explicitly labelled REGION_SCHEMATIC; selected county, township or village is never replaced by an ancestor.';
