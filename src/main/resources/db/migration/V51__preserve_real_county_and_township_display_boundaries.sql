-- County and township boundaries are source-attributed administrative surfaces.
-- They must never be regenerated to satisfy a display partition constraint:
-- only villages are allowed to use a generated, watertight display partition.

CREATE OR REPLACE FUNCTION overview.village_display_parent_surface(source_geometry geometry)
RETURNS geometry
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
  -- Villages are presentation-only and must occupy one continuous, hole-free
  -- canvas. Keep the largest real township land component, remove only its
  -- interior rings, and never change the township geometry itself.
  SELECT ST_Multi(ST_MakePolygon(ST_ExteriorRing((component).geom)))
           ::geometry(MultiPolygon,4326)
    FROM ST_Dump(ST_CollectionExtract(ST_MakeValid(source_geometry),3)) component
   ORDER BY ST_Area((component).geom::geography) DESC
   LIMIT 1;
$$;

CREATE OR REPLACE FUNCTION overview.assert_watertight_administrative_render()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  invalid_surface_count integer;
  invalid_village_surface_count integer;
  containment_error_count integer;
  village_partition_error_count integer;
  village_sibling_overlap_count integer;
BEGIN
  SELECT count(*) INTO invalid_surface_count
    FROM overview.administrative_boundary_render render
   WHERE NOT ST_IsValid(render.geometry)
      OR ST_IsEmpty(render.geometry);

  -- A village is a display-only subdivision and therefore must be one closed
  -- surface. County and township source data may legitimately contain an
  -- enclave, island, or interior ring and is deliberately not rewritten.
  SELECT count(*) INTO invalid_village_surface_count
    FROM platform.region region
    JOIN overview.administrative_boundary_render render
      ON render.region_code=region.code
   WHERE region.administrative_level='VILLAGE'
     AND (
       ST_NumGeometries(render.geometry)<>1
       OR ST_NumInteriorRings(ST_GeometryN(render.geometry,1))<>0
     );

  SELECT count(*) INTO containment_error_count
    FROM platform.region child
    JOIN overview.administrative_boundary_render child_render
      ON child_render.region_code=child.code
    JOIN overview.administrative_boundary_render parent_render
      ON parent_render.region_code=child.parent_code
   WHERE child.administrative_level='VILLAGE'
     AND overview.has_visible_surface_gap(ST_Difference(
           child_render.geometry,
           overview.village_display_parent_surface(parent_render.geometry)
         ));

  WITH coverage AS (
    SELECT child.parent_code,count(*) rendered_child_count,
           ST_UnaryUnion(ST_Collect(render.geometry)) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render render
        ON render.region_code=child.code
     WHERE child.administrative_level='VILLAGE'
     GROUP BY child.parent_code
  )
  SELECT count(*) INTO village_partition_error_count
    FROM coverage
    JOIN overview.administrative_boundary_render parent
      ON parent.region_code=coverage.parent_code
    CROSS JOIN LATERAL (
      SELECT count(*) expected_child_count
        FROM platform.region child
       WHERE child.parent_code=coverage.parent_code
         AND child.administrative_level='VILLAGE'
    ) expected
   WHERE coverage.rendered_child_count=expected.expected_child_count
     AND (
       overview.has_visible_surface_gap(ST_Difference(
         overview.village_display_parent_surface(parent.geometry),coverage.geometry
       ))
       OR overview.has_visible_surface_gap(ST_Difference(
         coverage.geometry,overview.village_display_parent_surface(parent.geometry)
       ))
     );

  SELECT count(*) INTO village_sibling_overlap_count
    FROM platform.region left_region
    JOIN overview.administrative_boundary_render left_render
      ON left_render.region_code=left_region.code
    JOIN platform.region right_region
      ON right_region.parent_code IS NOT DISTINCT FROM left_region.parent_code
     AND right_region.administrative_level='VILLAGE'
     AND right_region.code>left_region.code
    JOIN overview.administrative_boundary_render right_render
      ON right_render.region_code=right_region.code
   WHERE left_region.administrative_level='VILLAGE'
     AND left_render.geometry && right_render.geometry
     AND overview.has_visible_surface_gap(ST_Intersection(
           left_render.geometry,right_render.geometry
         ));

  IF invalid_surface_count<>0 OR invalid_village_surface_count<>0
     OR containment_error_count<>0 OR village_partition_error_count<>0
     OR village_sibling_overlap_count<>0 THEN
    RAISE EXCEPTION
      'Administrative render integrity gate failed: invalid %, invalid village %, village containment %, village partition %, village overlap %',
      invalid_surface_count,invalid_village_surface_count,containment_error_count,
      village_partition_error_count,village_sibling_overlap_count;
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  -- Restores source-derived prefecture, county, and township render geometry.
  -- Do this before every generated village pass so a prior display operation
  -- cannot persist a synthetic county or township boundary.
  PERFORM overview.refresh_administrative_boundary_render_source();
  PERFORM overview.close_root_prefecture_gaps('FORMAL_BUSINESS');

  -- Only administrative villages are presentation partitions. Temporarily
  -- supply their generator a single, clean canvas from each township's largest
  -- real land component; immediately restore the untouched real township
  -- geometry afterwards. This prevents holes and detached source fragments
  -- from becoming village flylands without altering the township boundary.
  CREATE TEMP TABLE village_parent_source_geometry ON COMMIT DROP AS
    SELECT render.region_code,render.geometry,render.geo_json
      FROM overview.administrative_boundary_render render
      JOIN platform.region region ON region.code=render.region_code
     WHERE region.administrative_level='TOWNSHIP';

  UPDATE overview.administrative_boundary_render render
     SET geometry=overview.village_display_parent_surface(render.geometry),
         geo_json=ST_AsGeoJSON(overview.village_display_parent_surface(render.geometry))
    FROM platform.region region
   WHERE region.code=render.region_code
     AND region.administrative_level='TOWNSHIP';

  PERFORM overview.repartition_display_children_watertight('VILLAGE');

  UPDATE overview.administrative_boundary_render render
     SET geometry=source.geometry,
         geo_json=source.geo_json
    FROM village_parent_source_geometry source
   WHERE render.region_code=source.region_code;

  PERFORM overview.restore_administrative_boundary_render_provenance();
  TRUNCATE overview.administrative_map_context_region_render;
  PERFORM overview.assert_watertight_administrative_render();
END;
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');
