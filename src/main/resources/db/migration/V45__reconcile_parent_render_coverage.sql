ALTER FUNCTION overview.refresh_administrative_boundary_render()
  RENAME TO refresh_administrative_boundary_render_base;

CREATE OR REPLACE FUNCTION overview.reconcile_parent_render_coverage()
RETURNS void
LANGUAGE sql
AS $$
  WITH prefecture_coverage AS (
    SELECT parent.code region_code,
           ST_Multi(ST_UnaryUnion(ST_Collect(child_render.geometry))) geometry,
           string_agg(DISTINCT child_render.source_revision, ', ' ORDER BY child_render.source_revision)::varchar(120) source_revision,
           string_agg(DISTINCT child_render.source_license, '; ' ORDER BY child_render.source_license) source_license
      FROM platform.region parent
      JOIN platform.region child
        ON child.parent_code=parent.code
       AND child.administrative_level='COUNTY'
      JOIN overview.administrative_boundary_render child_render
        ON child_render.region_code=child.code
     WHERE parent.administrative_level='PREFECTURE'
     GROUP BY parent.code
    HAVING count(*)=(
      SELECT count(*) FROM platform.region expected
       WHERE expected.parent_code=parent.code
         AND expected.administrative_level='COUNTY'
    )
  )
  UPDATE overview.administrative_boundary_render parent_render
     SET geometry=coverage.geometry,
         geo_json=ST_AsGeoJSON(coverage.geometry),
         full_point_count=GREATEST(parent_render.full_point_count,ST_NPoints(coverage.geometry)),
         render_point_count=ST_NPoints(coverage.geometry),
         source_geometry_sha256=encode(sha256(ST_AsEWKB(coverage.geometry)),'hex'),
         refreshed_at=now(),
         source_name='Topology-closed union of Overture county display boundaries',
         source_revision=coverage.source_revision,
         source_license=coverage.source_license
    FROM prefecture_coverage coverage
   WHERE parent_render.region_code=coverage.region_code;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM overview.refresh_administrative_boundary_render_base();
  PERFORM overview.reconcile_parent_render_coverage();
END;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_monitoring_scope_boundary_render(requested_scope_code varchar)
RETURNS void
LANGUAGE sql
AS $$
  WITH coverage AS (
    SELECT scope.code scope_code,
           ST_Multi(ST_UnaryUnion(ST_Collect(render.geometry))) geometry,
           string_agg(DISTINCT render.source_name, '; ' ORDER BY render.source_name)::varchar(160) source_name,
           string_agg(DISTINCT render.source_revision, ', ' ORDER BY render.source_revision)::varchar(120) source_revision,
           string_agg(DISTINCT render.source_license, '; ' ORDER BY render.source_license) source_license,
           string_agg(
             render.region_code||':'||render.source_geometry_sha256,
             ',' ORDER BY render.region_code
           ) component_geometry_fingerprint
      FROM platform.monitoring_scope scope
      JOIN overview.monitoring_scope_boundary governed_scope
        ON governed_scope.scope_code=scope.code
      JOIN platform.monitoring_scope_region member
        ON member.scope_code=scope.code AND member.included
      JOIN platform.region region
        ON region.code=member.region_code AND region.administrative_level='PREFECTURE'
      JOIN overview.administrative_boundary_render render
        ON render.region_code=region.code
     WHERE scope.code=requested_scope_code
     GROUP BY scope.code
    HAVING count(*)=(
      SELECT count(*)
        FROM platform.monitoring_scope_region expected_member
        JOIN platform.region expected_region
          ON expected_region.code=expected_member.region_code
         AND expected_region.administrative_level='PREFECTURE'
       WHERE expected_member.scope_code=requested_scope_code
         AND expected_member.included
    )
  )
  INSERT INTO overview.monitoring_scope_boundary_render(
    scope_code,geometry,geo_json,simplify_tolerance,full_point_count,
    render_point_count,component_geometry_fingerprint,refreshed_at,
    source_name,source_revision,source_license
  )
  SELECT coverage.scope_code,
         coverage.geometry,
         ST_AsGeoJSON(coverage.geometry),
         0::double precision,
         ST_NPoints(coverage.geometry),
         ST_NPoints(coverage.geometry),
         coverage.component_geometry_fingerprint,
         now(),
         coverage.source_name,
         coverage.source_revision,
         coverage.source_license
    FROM coverage
  ON CONFLICT(scope_code) DO UPDATE SET
    geometry=EXCLUDED.geometry,
    geo_json=EXCLUDED.geo_json,
    simplify_tolerance=EXCLUDED.simplify_tolerance,
    full_point_count=EXCLUDED.full_point_count,
    render_point_count=EXCLUDED.render_point_count,
    component_geometry_fingerprint=EXCLUDED.component_geometry_fingerprint,
    refreshed_at=EXCLUDED.refreshed_at,
    source_name=EXCLUDED.source_name,
    source_revision=EXCLUDED.source_revision,
    source_license=EXCLUDED.source_license;
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');

DO $$
DECLARE
  parent_gap_count integer;
  scope_gap_m2 double precision;
BEGIN
  WITH prefectures AS (
    SELECT parent.code,render.geometry
      FROM platform.region parent
      JOIN overview.administrative_boundary_render render ON render.region_code=parent.code
     WHERE parent.administrative_level='PREFECTURE'
  ), county_coverage AS (
    SELECT child.parent_code,ST_UnaryUnion(ST_Collect(render.geometry)) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render render ON render.region_code=child.code
     WHERE child.administrative_level='COUNTY'
     GROUP BY child.parent_code
  )
  SELECT count(*) INTO parent_gap_count
    FROM prefectures parent
    JOIN county_coverage children ON children.parent_code=parent.code
   WHERE ST_Area(ST_SymDifference(parent.geometry,children.geometry)::geography)>10;

  SELECT ST_Area(ST_SymDifference(scope.geometry,prefectures.geometry)::geography)
    INTO scope_gap_m2
    FROM overview.monitoring_scope_boundary_render scope
    CROSS JOIN (
      SELECT ST_Multi(ST_UnaryUnion(ST_Collect(render.geometry))) geometry
        FROM overview.administrative_boundary_render render
        JOIN platform.region region ON region.code=render.region_code
       WHERE region.administrative_level='PREFECTURE'
    ) prefectures
   WHERE scope.scope_code='FORMAL_BUSINESS';

  IF parent_gap_count<>0 OR scope_gap_m2>10 THEN
    RAISE EXCEPTION 'Parent render reconciliation failed: parent mismatches %, scope mismatch m2 %',
      parent_gap_count,scope_gap_m2;
  END IF;
END;
$$;

COMMENT ON FUNCTION overview.reconcile_parent_render_coverage() IS
  'Derives each displayed prefecture shell from its complete displayed county coverage so caps, child outlines, hit targets and side walls share identical topology.';
