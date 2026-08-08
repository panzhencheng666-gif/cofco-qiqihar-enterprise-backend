-- Villages are display partitions, but they still must never occupy a named
-- enclave that is outside their final township surface.  Keep the existing
-- topology generator and change only its village parent canvas from the outer
-- ring to the final published township geometry.

DO $migration$
DECLARE
  source_definition text;
  revised_definition text;
BEGIN
  SELECT pg_get_functiondef(
    'overview.repartition_display_children_watertight(text,text[])'::regprocedure
  ) INTO source_definition;

  revised_definition := replace(
    source_definition,
    $needle$           ST_Multi(ST_MakePolygon(ST_ExteriorRing(
             ST_GeometryN(grouped.source_parent_geometry,1)
           )))::geometry(MultiPolygon,4326) geometry,$needle$,
    $replacement$           CASE
             WHEN requested_child_level='VILLAGE'
               THEN grouped.source_parent_geometry
             ELSE ST_Multi(ST_MakePolygon(ST_ExteriorRing(
               ST_GeometryN(grouped.source_parent_geometry,1)
             )))::geometry(MultiPolygon,4326)
           END geometry,$replacement$
  );

  IF revised_definition=source_definition THEN
    RAISE EXCEPTION
      'Village topology canvas upgrade failed: expected generator clause was not found';
  END IF;

  EXECUTE revised_definition;
END;
$migration$;

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
           child_render.geometry,parent_render.geometry
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
       overview.has_visible_surface_gap(ST_Difference(parent.geometry,coverage.geometry))
       OR overview.has_visible_surface_gap(ST_Difference(coverage.geometry,parent.geometry))
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
  PERFORM overview.refresh_administrative_boundary_render_source();
  PERFORM overview.clean_real_township_boundary_render();
  PERFORM overview.reconcile_real_named_primary_surfaces();
  PERFORM overview.close_root_prefecture_gaps('FORMAL_BUSINESS');

  -- V55 passes the actual final township geometry to the village partitioner.
  -- A named township enclave therefore remains outside its neighbour's
  -- generated villages instead of becoming an invisible overflow surface.
  PERFORM overview.repartition_display_children_watertight('VILLAGE');

  PERFORM overview.restore_administrative_boundary_render_provenance();
  TRUNCATE overview.administrative_map_context_region_render;
  PERFORM overview.assert_watertight_administrative_render();
  PERFORM overview.assert_clean_real_named_boundary_render();
END;
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');
