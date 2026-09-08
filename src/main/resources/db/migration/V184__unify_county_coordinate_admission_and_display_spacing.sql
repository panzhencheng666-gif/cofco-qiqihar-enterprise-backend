-- Admission uses the authoritative county, while rendering uses the smallest
-- declared region. Existing reported coordinates are never rewritten.
CREATE FUNCTION overview.sample_coordinate_admission_state(
    requested_region varchar, longitude numeric, latitude numeric)
RETURNS text LANGUAGE sql STABLE
SET search_path = pg_catalog,platform,overview,public
AS $$
    WITH RECURSIVE ancestors AS (
      SELECT code,parent_code,administrative_level FROM platform.region WHERE code=requested_region
      UNION
      SELECT r.code,r.parent_code,r.administrative_level FROM platform.region r
      JOIN ancestors child ON r.code=child.parent_code
    ), admission_region AS (
      SELECT code FROM ancestors WHERE administrative_level='COUNTY'
      UNION ALL
      SELECT code FROM ancestors WHERE code=requested_region AND administrative_level='PREFECTURE'
    )
    SELECT CASE
      WHEN longitude IS NULL OR latitude IS NULL
        OR NOT (longitude BETWEEN -180 AND 180 AND latitude BETWEEN -90 AND 90) THEN 'OUTSIDE'
      WHEN county.geometry IS NULL OR display.geometry IS NULL
        OR NOT public.ST_IsValid(county.geometry) OR public.ST_IsEmpty(county.geometry)
        OR NOT public.ST_IsValid(display.geometry) OR public.ST_IsEmpty(display.geometry) THEN 'UNAVAILABLE'
      WHEN public.ST_Covers(county.geometry,
        public.ST_SetSRID(public.ST_MakePoint(longitude,latitude),4326)) THEN 'INSIDE'
      ELSE 'OUTSIDE' END
    FROM platform.region selected
    LEFT JOIN admission_region admitted ON true
    LEFT JOIN overview.administrative_boundary county ON county.region_code=admitted.code
    LEFT JOIN overview.administrative_boundary_render display ON display.region_code=selected.code
    WHERE selected.code=requested_region
$$;
REVOKE ALL ON FUNCTION overview.sample_coordinate_admission_state(varchar,numeric,numeric) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION overview.sample_coordinate_admission_state(varchar,numeric,numeric)
    TO qiqihar_enterprise_runtime,CURRENT_USER;

-- Serialise placement across both point stores. The coordinate is a display
-- hint only; sample identity and reviewed sharing retain their own contracts.
CREATE FUNCTION overview.sample_display_point_occupied(candidate public.geometry, excluded_id uuid, design boolean)
RETURNS boolean LANGUAGE sql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog,platform,registry,public
AS $$
    SELECT EXISTS(SELECT 1 FROM platform.design_sample_point p
      WHERE (NOT design OR p.design_sample_point_id<>excluded_id)
        AND public.ST_Equals(p.display_point,candidate))
      OR EXISTS(SELECT 1 FROM registry.sample_point p
      WHERE (design OR p.sample_point_id<>excluded_id)
        AND public.ST_Equals(p.display_point,candidate))
$$;
REVOKE ALL ON FUNCTION overview.sample_display_point_occupied(public.geometry,uuid,boolean) FROM PUBLIC;

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
    PERFORM pg_advisory_xact_lock(hashtextextended('SAMPLE_DISPLAY_PLACEMENT',0));
    NEW.display_region_code := NEW.region_code;
    IF public.ST_Covers(selected_boundary,NEW.governed_point)
       AND NOT overview.sample_display_point_occupied(NEW.governed_point,NEW.design_sample_point_id,true) THEN
        NEW.display_point := NEW.governed_point;
        NEW.location_mode := 'REPORTED_COORDINATE';
    ELSE
        -- Preserve a previous valid schematic location across edits and boundary refreshes.
        IF TG_OP='UPDATE' AND OLD.region_code=NEW.region_code
           AND public.ST_Equals(OLD.governed_point,NEW.governed_point)
           AND OLD.location_mode='REGION_SCHEMATIC'
           AND public.ST_Covers(selected_boundary,OLD.display_point)
           AND NOT overview.sample_display_point_occupied(OLD.display_point,NEW.design_sample_point_id,true) THEN
            NEW.display_point := OLD.display_point;
        ELSE
            -- Seeded by persistent identity, so different samples do not all land
            -- on PointOnSurface. Prefer the candidate furthest from existing dots.
            placement_seed := 1 + ((('x'||substr(md5(NEW.design_sample_point_id::text),1,8))::bit(32)::bigint)
                                    % 2147483646)::integer;
            SELECT candidate.geom INTO NEW.display_point
            FROM public.ST_Dump(public.ST_GeneratePoints(selected_boundary,64,placement_seed)) candidate
            WHERE NOT overview.sample_display_point_occupied(candidate.geom,NEW.design_sample_point_id,true)
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
    IF NEW.display_point IS NULL OR NOT public.ST_Covers(selected_boundary,NEW.display_point)
       OR overview.sample_display_point_occupied(NEW.display_point,NEW.design_sample_point_id,true) THEN
        RAISE EXCEPTION 'design sample display coordinate outside region %',NEW.region_code
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

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
    PERFORM pg_advisory_xact_lock(hashtextextended('SAMPLE_DISPLAY_PLACEMENT',0));
    NEW.display_region_code := NEW.region_code;
    IF public.ST_Covers(selected_boundary,NEW.governed_point)
       AND NOT overview.sample_display_point_occupied(NEW.governed_point,NEW.sample_point_id,false) THEN
        NEW.display_point := NEW.governed_point;
        NEW.location_mode := 'REPORTED_COORDINATE';
    ELSE
        -- Preserve a previous valid schematic location across edits and boundary refreshes.
        IF TG_OP='UPDATE' AND OLD.region_code=NEW.region_code
           AND public.ST_Equals(OLD.governed_point,NEW.governed_point)
           AND OLD.location_mode='REGION_SCHEMATIC'
           AND public.ST_Covers(selected_boundary,OLD.display_point)
           AND NOT overview.sample_display_point_occupied(OLD.display_point,NEW.sample_point_id,false) THEN
            NEW.display_point := OLD.display_point;
        ELSE
            -- Seeded by persistent identity, so different samples do not all land
            -- on PointOnSurface. Prefer the candidate furthest from existing dots.
            placement_seed := 1 + ((('x'||substr(md5(NEW.sample_point_id::text),1,8))::bit(32)::bigint)
                                    % 2147483646)::integer;
            SELECT candidate.geom INTO NEW.display_point
            FROM public.ST_Dump(public.ST_GeneratePoints(selected_boundary,64,placement_seed)) candidate
            WHERE NOT overview.sample_display_point_occupied(candidate.geom,NEW.sample_point_id,false)
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
    IF NEW.display_point IS NULL OR NOT public.ST_Covers(selected_boundary,NEW.display_point)
       OR overview.sample_display_point_occupied(NEW.display_point,NEW.sample_point_id,false) THEN
        RAISE EXCEPTION 'formal sample display coordinate outside region %',NEW.region_code
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;

-- Repair only overlapping display positions; the original reported values and
-- all sample identities, approval states and business facts remain unchanged.
UPDATE registry.sample_point p SET display_point=p.display_point
WHERE p.display_point IS NOT NULL
  AND overview.sample_display_point_occupied(p.display_point,p.sample_point_id,false);
UPDATE platform.design_sample_point p SET display_point=p.display_point
WHERE overview.sample_display_point_occupied(p.display_point,p.design_sample_point_id,true);
