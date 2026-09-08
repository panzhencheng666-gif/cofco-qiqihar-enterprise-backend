-- Reported values remain unchanged. A mismatched location no longer prevents
-- saving a formal sample; only its separately labelled map position is placed
-- inside the exact administrative region selected by the user (at any level).
ALTER TABLE registry.sample_point
    ADD COLUMN display_point geometry(Point,4326),
    ADD COLUMN display_region_code varchar(12) REFERENCES platform.region(code),
    ADD COLUMN location_mode varchar(40);

CREATE OR REPLACE FUNCTION registry.enforce_sample_point_containment()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,platform,overview,public
AS $$
DECLARE selected_boundary public.geometry;
DECLARE placement_seed integer;
DECLARE governed_boundary overview.administrative_boundary%ROWTYPE;
BEGIN
    IF NEW.location_state<>'VALID' THEN
        IF NEW.governed_point IS NOT NULL OR NEW.containment_boundary_sha256 IS NOT NULL
           OR NEW.containment_boundary_revision IS NOT NULL THEN
            RAISE EXCEPTION 'Non-VALID sample point cannot retain governed geometry or containment evidence';
        END IF;
        NEW.display_point := NULL;
        NEW.display_region_code := NULL;
        NEW.location_mode := NULL;
        RETURN NEW;
    END IF;
    IF NEW.governed_point IS NULL THEN
        RAISE EXCEPTION 'VALID sample point requires governed geometry';
    END IF;
    SELECT * INTO governed_boundary FROM overview.administrative_boundary
    WHERE region_code=NEW.region_code;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No governed boundary exists for region %',NEW.region_code;
    END IF;
    NEW.containment_boundary_sha256 := governed_boundary.geometry_sha256;
    NEW.containment_boundary_revision := governed_boundary.source_revision;
    SELECT public.ST_SetSRID(public.ST_GeomFromGeoJSON(geo_json),4326)
    INTO selected_boundary FROM overview.administrative_boundary_render
    WHERE region_code=NEW.region_code FOR SHARE;
    IF selected_boundary IS NULL OR public.ST_IsEmpty(selected_boundary)
       OR NOT public.ST_IsValid(selected_boundary) THEN
        RAISE EXCEPTION 'formal sample display boundary unavailable for %',NEW.region_code
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
            placement_seed := 1 + ((('x'||substr(md5(NEW.sample_point_id::text),1,8))::bit(32)::bigint)
                                    % 2147483646)::integer;
            SELECT candidate.geom INTO NEW.display_point
            FROM public.ST_Dump(public.ST_GeneratePoints(selected_boundary,64,placement_seed)) candidate
            ORDER BY COALESCE((
                SELECT min(public.ST_Distance(candidate.geom,p.display_point))
                FROM registry.sample_point p
                WHERE p.region_code=NEW.region_code
                  AND p.sample_point_id<>NEW.sample_point_id
                  AND p.display_point IS NOT NULL
            ),0) DESC, candidate.path
            LIMIT 1;
            NEW.display_point := COALESCE(NEW.display_point,public.ST_PointOnSurface(selected_boundary));
        END IF;
        NEW.location_mode := 'REGION_SCHEMATIC';
    END IF;
    IF NEW.display_point IS NULL OR NOT public.ST_Covers(selected_boundary,NEW.display_point) THEN
        RAISE EXCEPTION 'formal sample display coordinate outside region %',NEW.region_code
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER sample_point_containment_guard ON registry.sample_point;
CREATE TRIGGER sample_point_containment_guard
BEFORE INSERT OR UPDATE OF region_code,location_state,governed_point,display_point,display_region_code,location_mode,
containment_boundary_sha256,containment_boundary_revision
ON registry.sample_point
FOR EACH ROW EXECUTE FUNCTION registry.enforce_sample_point_containment();

UPDATE registry.sample_point SET governed_point=governed_point;
ALTER TABLE registry.sample_point
    ADD CONSTRAINT formal_sample_valid_display CHECK(location_state<>'VALID' OR
      (display_point IS NOT NULL AND display_region_code IS NOT NULL AND location_mode IS NOT NULL)),
    ADD CONSTRAINT formal_sample_display_region_matches CHECK(display_region_code=region_code),
    ADD CONSTRAINT formal_sample_location_mode_valid
      CHECK(location_mode IN ('REPORTED_COORDINATE','REGION_SCHEMATIC'));
CREATE INDEX formal_sample_display_point_gix ON registry.sample_point USING gist(display_point);

CREATE FUNCTION overview.refresh_formal_sample_display_coordinates()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,overview,registry,public
AS $$
BEGIN
    UPDATE registry.sample_point SET governed_point=governed_point
    WHERE display_region_code=NEW.region_code;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION overview.refresh_formal_sample_display_coordinates() FROM PUBLIC;
CREATE TRIGGER refresh_formal_sample_display_coordinates
AFTER UPDATE OF geometry ON overview.administrative_boundary_render
FOR EACH ROW WHEN (OLD.geometry IS DISTINCT FROM NEW.geometry)
EXECUTE FUNCTION overview.refresh_formal_sample_display_coordinates();

COMMENT ON COLUMN registry.sample_point.governed_point IS 'Original reported coordinate; schematic map placement never overwrites this value.';
COMMENT ON COLUMN registry.sample_point.display_point IS 'Map coordinate inside the exact selected region, labelled REGION_SCHEMATIC when the reported location falls outside.';

CREATE FUNCTION overview.protect_formal_display_boundary()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,overview,registry,public
AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM registry.sample_point WHERE display_region_code=OLD.region_code) THEN
        RAISE EXCEPTION 'cannot remove boundary % with existing formal sample points',OLD.region_code
            USING ERRCODE='23514';
    END IF;
    RETURN OLD;
END
$$;
REVOKE ALL ON FUNCTION overview.protect_formal_display_boundary() FROM PUBLIC;
CREATE TRIGGER protect_formal_display_boundary BEFORE DELETE ON overview.administrative_boundary_render
FOR EACH ROW EXECUTE FUNCTION overview.protect_formal_display_boundary();
