-- A source boundary can contain residual islands and holes. Publish only its
-- primary real outline; retain an interior edge solely when that area is
-- demonstrably occupied by another named sibling administrative region.

CREATE OR REPLACE FUNCTION overview.reconcile_real_named_primary_surfaces()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  WITH raw AS (
    SELECT region.code,region.parent_code,region.administrative_level,
           render.geometry raw_geometry,
           overview.village_display_parent_surface(render.geometry) primary_geometry
      FROM platform.region region
      JOIN overview.administrative_boundary_render render
        ON render.region_code=region.code
     WHERE region.administrative_level IN ('COUNTY','TOWNSHIP')
  ), covered_holes AS (
    SELECT subject.code,
           ST_UnaryUnion(ST_Collect(
             ST_MakePolygon(ST_InteriorRingN(part.geom,hole_number))
           )) geometry
      FROM raw subject
      CROSS JOIN LATERAL ST_Dump(subject.raw_geometry) part
      CROSS JOIN LATERAL generate_series(1,ST_NumInteriorRings(part.geom)) hole_number
      JOIN raw sibling
        ON sibling.parent_code=subject.parent_code
       AND sibling.administrative_level=subject.administrative_level
       AND sibling.code<>subject.code
     WHERE overview.has_visible_surface_gap(ST_Intersection(
       ST_MakePolygon(ST_InteriorRingN(part.geom,hole_number)),sibling.raw_geometry
     ))
     GROUP BY subject.code
  ), normalized AS (
    SELECT raw.code,
           ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Difference(
             raw.primary_geometry,
             COALESCE(covered_holes.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
           )),3))::geometry(MultiPolygon,4326) geometry
      FROM raw
      LEFT JOIN covered_holes ON covered_holes.code=raw.code
  )
  UPDATE overview.administrative_boundary_render render
     SET geometry=normalized.geometry,
         geo_json=ST_AsGeoJSON(normalized.geometry),
         render_point_count=ST_NPoints(normalized.geometry),
         refreshed_at=now()
    FROM normalized
   WHERE render.region_code=normalized.code;
END;
$$;

CREATE OR REPLACE FUNCTION overview.assert_clean_real_named_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  malformed_real_surface_count integer;
  unnamed_hole_count integer;
  real_parent_gap_count integer;
  real_sibling_overlap_count integer;
  complete_hierarchy boolean;
BEGIN
  SELECT count(*) INTO malformed_real_surface_count
    FROM platform.region region
    JOIN overview.administrative_boundary_render render
      ON render.region_code=region.code
   WHERE region.administrative_level IN ('COUNTY','TOWNSHIP')
     AND (NOT ST_IsValid(render.geometry) OR ST_IsEmpty(render.geometry)
       OR ST_NumGeometries(render.geometry)<>1);

  SELECT count(*) INTO unnamed_hole_count
    FROM platform.region subject
    JOIN overview.administrative_boundary_render subject_render
      ON subject_render.region_code=subject.code
    CROSS JOIN LATERAL ST_Dump(subject_render.geometry) part
    CROSS JOIN LATERAL generate_series(1,ST_NumInteriorRings(part.geom)) hole_number
   WHERE subject.administrative_level IN ('COUNTY','TOWNSHIP')
     AND NOT EXISTS (
       SELECT 1
         FROM platform.region sibling
         JOIN overview.administrative_boundary_render sibling_render
           ON sibling_render.region_code=sibling.code
        WHERE sibling.parent_code=subject.parent_code
          AND sibling.administrative_level=subject.administrative_level
          AND sibling.code<>subject.code
          AND overview.has_visible_surface_gap(ST_Intersection(
            ST_MakePolygon(ST_InteriorRingN(part.geom,hole_number)),sibling_render.geometry
          ))
     );

  complete_hierarchy := (
    SELECT count(*)=232 FROM platform.region WHERE administrative_level='TOWNSHIP'
  ) AND (
    SELECT count(*)=2332 FROM platform.region WHERE administrative_level='VILLAGE'
  );

  IF complete_hierarchy THEN
    WITH coverage AS (
      SELECT child.parent_code,ST_UnaryUnion(ST_Collect(render.geometry)) geometry
        FROM platform.region child
        JOIN overview.administrative_boundary_render render
          ON render.region_code=child.code
       WHERE child.administrative_level IN ('COUNTY','TOWNSHIP')
       GROUP BY child.parent_code
    )
    SELECT count(*) INTO real_parent_gap_count
      FROM coverage
      JOIN overview.administrative_boundary_render parent
        ON parent.region_code=coverage.parent_code
     WHERE overview.has_visible_surface_gap(ST_Difference(parent.geometry,coverage.geometry))
        OR overview.has_visible_surface_gap(ST_Difference(coverage.geometry,parent.geometry));

    SELECT count(*) INTO real_sibling_overlap_count
      FROM platform.region left_region
      JOIN overview.administrative_boundary_render left_render
        ON left_render.region_code=left_region.code
      JOIN platform.region right_region
        ON right_region.parent_code=left_region.parent_code
       AND right_region.administrative_level=left_region.administrative_level
       AND right_region.code>left_region.code
      JOIN overview.administrative_boundary_render right_render
        ON right_render.region_code=right_region.code
     WHERE left_region.administrative_level IN ('COUNTY','TOWNSHIP')
       AND left_render.geometry && right_render.geometry
       AND overview.has_visible_surface_gap(ST_Intersection(left_render.geometry,right_render.geometry));
  ELSE
    real_parent_gap_count := 0;
    real_sibling_overlap_count := 0;
  END IF;

  IF malformed_real_surface_count<>0 OR unnamed_hole_count<>0
     OR real_parent_gap_count<>0 OR real_sibling_overlap_count<>0 THEN
    RAISE EXCEPTION
      'Named real boundary publication gate failed: malformed %, unnamed holes %, parent gaps %, sibling overlaps %',
      malformed_real_surface_count,unnamed_hole_count,real_parent_gap_count,real_sibling_overlap_count;
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM overview.refresh_administrative_boundary_render_source();
  PERFORM overview.clean_real_township_boundary_render();
  PERFORM overview.reconcile_real_named_primary_surfaces();
  PERFORM overview.close_root_prefecture_gaps('FORMAL_BUSINESS');

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
  PERFORM overview.assert_clean_real_named_boundary_render();
END;
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');
