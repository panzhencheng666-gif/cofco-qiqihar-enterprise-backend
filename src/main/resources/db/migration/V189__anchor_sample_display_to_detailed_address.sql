-- Address-based rendering never changes reported coordinates or county admission.
CREATE FUNCTION overview.sample_address_anchor(selected varchar, address text)
RETURNS varchar LANGUAGE sql STABLE
SET search_path=pg_catalog,platform,overview,public
AS $$
 WITH RECURSIVE descendants AS (
  SELECT code,parent_code,name,administrative_level,0 AS depth,ARRAY[name::text] AS names
  FROM platform.region WHERE code=selected
  UNION ALL
  SELECT r.code,r.parent_code,r.name,r.administrative_level,d.depth+1,d.names||r.name::text
  FROM platform.region r JOIN descendants d ON r.parent_code=d.code
 ), matches AS (
  SELECT d.*, (SELECT count(*) FROM unnest(d.names) n
              WHERE length(n)>1 AND strpos(coalesce(address,''),n)>0) AS specificity
  FROM descendants d
  WHERE d.administrative_level IN ('TOWNSHIP','VILLAGE')
    AND length(d.name)>1 AND strpos(coalesce(address,''),d.name)>0
 ), ranked AS (
  SELECT *,dense_rank() OVER (PARTITION BY depth ORDER BY specificity DESC) AS preference
  FROM matches
 ), unambiguous AS (
  SELECT depth,min(code) AS code FROM ranked WHERE preference=1
  GROUP BY depth HAVING count(*)=1
 )
 SELECT coalesce((SELECT code FROM unambiguous ORDER BY depth DESC LIMIT 1),selected)
$$;
REVOKE ALL ON FUNCTION overview.sample_address_anchor(varchar,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION overview.sample_address_anchor(varchar,text) TO qiqihar_enterprise_runtime,CURRENT_USER;

-- Use only an available ancestor inside the declared hierarchy when a lower
-- address region has no published rendering boundary. Never invent geometry.
CREATE FUNCTION overview.sample_address_display_region(selected varchar,address text)
RETURNS varchar LANGUAGE sql STABLE
SET search_path=pg_catalog,platform,overview,public AS $$
 WITH RECURSIVE ancestors AS (
  SELECT code,parent_code,0 AS distance FROM platform.region
  WHERE code=overview.sample_address_anchor(selected,address)
  UNION ALL
  SELECT r.code,r.parent_code,a.distance+1 FROM platform.region r
  JOIN ancestors a ON r.code=a.parent_code WHERE a.code<>selected
 )
 SELECT coalesce((SELECT a.code FROM ancestors a
 JOIN overview.administrative_boundary_render b ON b.region_code=a.code
 WHERE b.geometry IS NOT NULL AND NOT public.ST_IsEmpty(b.geometry)
 AND public.ST_IsValid(b.geometry) ORDER BY a.distance LIMIT 1),selected)
$$;
REVOKE ALL ON FUNCTION overview.sample_address_display_region(varchar,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION overview.sample_address_display_region(varchar,text)
 TO qiqihar_enterprise_runtime,CURRENT_USER;

ALTER TABLE platform.design_sample_point DROP CONSTRAINT design_sample_display_region_matches;
ALTER TABLE registry.sample_point DROP CONSTRAINT formal_sample_display_region_matches;
CREATE OR REPLACE FUNCTION platform.enforce_design_sample_point_containment()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog,platform,overview,public
AS $$
DECLARE selected_boundary public.geometry;
DECLARE placement_seed integer;
DECLARE anchor_region varchar(12);
BEGIN
    anchor_region := overview.sample_address_display_region(NEW.region_code,NEW.values_json->>'DSP_ADDRESS');
    SELECT public.ST_SetSRID(public.ST_GeomFromGeoJSON(geo_json),4326)
    INTO selected_boundary FROM overview.administrative_boundary_render
    WHERE region_code=anchor_region FOR SHARE;
    IF selected_boundary IS NULL OR public.ST_IsEmpty(selected_boundary)
       OR NOT public.ST_IsValid(selected_boundary) THEN
        RAISE EXCEPTION 'design sample display boundary unavailable for %',NEW.region_code
            USING ERRCODE='23514';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('SAMPLE_DISPLAY_PLACEMENT',0));
    NEW.display_region_code := anchor_region;
    BEGIN
        -- Preserve a previous valid schematic location across edits and boundary refreshes.
        IF TG_OP='UPDATE' AND OLD.display_region_code=anchor_region
           AND OLD.location_mode='REGION_SCHEMATIC'
           AND public.ST_Covers(selected_boundary,OLD.display_point)
           AND NOT overview.sample_display_point_occupied(OLD.display_point,NEW.design_sample_point_id,true) THEN
            NEW.display_point := OLD.display_point;
        ELSE
            -- Seeded by persistent identity, so different samples do not all land
            -- on PointOnSurface. Prefer candidates close to the address-region anchor.
            placement_seed := 1 + ((('x'||substr(md5(NEW.design_sample_point_id::text),1,8))::bit(32)::bigint)
                                    % 2147483646)::integer;
            SELECT candidate.geom INTO NEW.display_point
            FROM public.ST_Dump(public.ST_GeneratePoints(selected_boundary,64,placement_seed)) candidate
            WHERE NOT overview.sample_display_point_occupied(candidate.geom,NEW.design_sample_point_id,true)
            ORDER BY public.ST_Distance(candidate.geom,public.ST_PointOnSurface(selected_boundary)), candidate.path
            LIMIT 1;
            NEW.display_point := COALESCE(NEW.display_point,public.ST_PointOnSurface(selected_boundary));
        END IF;
        NEW.location_mode := 'REGION_SCHEMATIC';
    END;
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
DECLARE anchor_region varchar(12);
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
    anchor_region := overview.sample_address_display_region(NEW.region_code,(SELECT address FROM registry.formal_sample_point_profile WHERE sample_point_id=NEW.sample_point_id));
    SELECT public.ST_SetSRID(public.ST_GeomFromGeoJSON(geo_json),4326)
    INTO selected_boundary FROM overview.administrative_boundary_render
    WHERE region_code=anchor_region FOR SHARE;
    IF selected_boundary IS NULL OR public.ST_IsEmpty(selected_boundary)
       OR NOT public.ST_IsValid(selected_boundary) THEN
        RAISE EXCEPTION 'formal sample display boundary unavailable for %',NEW.region_code
            USING ERRCODE='23514';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('SAMPLE_DISPLAY_PLACEMENT',0));
    NEW.display_region_code := anchor_region;
    BEGIN
        -- Preserve a previous valid schematic location across edits and boundary refreshes.
        IF TG_OP='UPDATE' AND OLD.display_region_code=anchor_region
           AND OLD.location_mode='REGION_SCHEMATIC'
           AND public.ST_Covers(selected_boundary,OLD.display_point)
           AND NOT overview.sample_display_point_occupied(OLD.display_point,NEW.sample_point_id,false) THEN
            NEW.display_point := OLD.display_point;
        ELSE
            -- Seeded by persistent identity, so different samples do not all land
            -- on PointOnSurface. Prefer candidates close to the address-region anchor.
            placement_seed := 1 + ((('x'||substr(md5(NEW.sample_point_id::text),1,8))::bit(32)::bigint)
                                    % 2147483646)::integer;
            SELECT candidate.geom INTO NEW.display_point
            FROM public.ST_Dump(public.ST_GeneratePoints(selected_boundary,64,placement_seed)) candidate
            WHERE NOT overview.sample_display_point_occupied(candidate.geom,NEW.sample_point_id,false)
            ORDER BY public.ST_Distance(candidate.geom,public.ST_PointOnSurface(selected_boundary)), candidate.path
            LIMIT 1;
            NEW.display_point := COALESCE(NEW.display_point,public.ST_PointOnSurface(selected_boundary));
        END IF;
        NEW.location_mode := 'REGION_SCHEMATIC';
    END;
    IF NEW.display_point IS NULL OR NOT public.ST_Covers(selected_boundary,NEW.display_point)
       OR overview.sample_display_point_occupied(NEW.display_point,NEW.sample_point_id,false) THEN
        RAISE EXCEPTION 'formal sample display coordinate outside region %',NEW.region_code
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END
$$;


DROP TRIGGER design_sample_point_containment_guard ON platform.design_sample_point;
CREATE TRIGGER design_sample_point_containment_guard
BEFORE INSERT OR UPDATE OF region_code,values_json,governed_point,display_point,display_region_code,location_mode
ON platform.design_sample_point FOR EACH ROW EXECUTE FUNCTION platform.enforce_design_sample_point_containment();

CREATE FUNCTION registry.refresh_address_display_point() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,registry,public AS $$
BEGIN
 UPDATE registry.sample_point SET display_point=NULL WHERE sample_point_id=NEW.sample_point_id;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION registry.refresh_address_display_point() FROM PUBLIC;
CREATE TRIGGER refresh_address_display_point AFTER INSERT OR UPDATE OF address ON registry.formal_sample_point_profile
FOR EACH ROW EXECUTE FUNCTION registry.refresh_address_display_point();
UPDATE platform.design_sample_point SET display_point=NULL;
UPDATE registry.sample_point SET display_point=NULL WHERE location_state='VALID';
