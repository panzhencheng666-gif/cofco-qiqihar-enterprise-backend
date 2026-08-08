CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE sql
AS $$
    WITH prefecture_reference AS (
        SELECT parent.code region_code,
               ST_Multi(ST_UnaryUnion(ST_Collect(reference.geometry))) geometry,
               'Overture county display union'::varchar(160) source_name,
               string_agg(DISTINCT reference.source_revision, ', ' ORDER BY reference.source_revision)::varchar(120) source_revision,
               string_agg(DISTINCT reference.source_license, '; ' ORDER BY reference.source_license) source_license
          FROM platform.region parent
          JOIN platform.region child
            ON child.parent_code=parent.code
           AND child.administrative_level='COUNTY'
          JOIN overview.administrative_boundary_display_reference reference
            ON reference.region_code=child.code
         WHERE parent.administrative_level='PREFECTURE'
         GROUP BY parent.code
        HAVING count(*)=(
            SELECT count(*)
              FROM platform.region expected
             WHERE expected.parent_code=parent.code
               AND expected.administrative_level='COUNTY'
        )
    ), source AS (
        SELECT boundary.region_code,
               region.administrative_level,
               region.parent_code,
               reference.region_code IS NOT NULL uses_display_reference,
               CASE
                 WHEN region.administrative_level='PREFECTURE' AND prefecture.geometry IS NOT NULL
                   THEN prefecture.geometry
                 WHEN region.administrative_level IN ('COUNTY','TOWNSHIP') AND reference.geometry IS NOT NULL
                   THEN reference.geometry
                 ELSE boundary.geometry
               END geometry,
               CASE
                 WHEN region.administrative_level='PREFECTURE' AND prefecture.geometry IS NOT NULL
                   THEN prefecture.source_name
                 WHEN region.administrative_level IN ('COUNTY','TOWNSHIP') AND reference.geometry IS NOT NULL
                   THEN reference.source_name
                 ELSE boundary.source_name
               END source_name,
               CASE
                 WHEN region.administrative_level='PREFECTURE' AND prefecture.geometry IS NOT NULL
                   THEN prefecture.source_revision
                 WHEN region.administrative_level IN ('COUNTY','TOWNSHIP') AND reference.geometry IS NOT NULL
                   THEN reference.source_revision
                 ELSE boundary.source_revision
               END source_revision,
               CASE
                 WHEN region.administrative_level='PREFECTURE' AND prefecture.geometry IS NOT NULL
                   THEN prefecture.source_license
                 WHEN region.administrative_level IN ('COUNTY','TOWNSHIP') AND reference.geometry IS NOT NULL
                   THEN reference.source_license
                 ELSE boundary.source_license
               END source_license,
               CASE region.administrative_level
                 WHEN 'PREFECTURE' THEN 0.005
                 WHEN 'COUNTY' THEN 0.00035
                 WHEN 'TOWNSHIP' THEN 0.0002
                 WHEN 'VILLAGE' THEN 0.00012
               END::double precision tolerance
          FROM overview.administrative_boundary boundary
          JOIN platform.region region ON region.code=boundary.region_code
          LEFT JOIN overview.administrative_boundary_display_reference reference
            ON reference.region_code=boundary.region_code
          LEFT JOIN prefecture_reference prefecture
            ON prefecture.region_code=boundary.region_code
    ), coverage_prepared AS (
        SELECT source.*,
               ST_Multi(ST_CollectionExtract(
                   ST_CoverageSimplify(geometry, tolerance, true) OVER (
                       PARTITION BY administrative_level, parent_code
                   ),
                   3
               )) simplified_geometry,
               encode(sha256(ST_AsEWKB(geometry)), 'hex') geometry_sha256
          FROM source
         WHERE uses_display_reference
           AND administrative_level IN ('COUNTY','TOWNSHIP')
    ), ordinary_prepared AS (
        SELECT source.*,
               ST_Multi(ST_CollectionExtract(
                   ST_SimplifyPreserveTopology(geometry, tolerance),
                   3
               )) simplified_geometry,
               encode(sha256(ST_AsEWKB(geometry)), 'hex') geometry_sha256
          FROM source
         WHERE NOT (uses_display_reference AND administrative_level IN ('COUNTY','TOWNSHIP'))
    ), prepared AS (
        SELECT * FROM coverage_prepared
        UNION ALL
        SELECT * FROM ordinary_prepared
    ), parent_clipped AS (
        SELECT child.*,
               CASE
                 WHEN child.administrative_level='TOWNSHIP' AND parent.simplified_geometry IS NOT NULL
                   THEN ST_Multi(ST_CollectionExtract(
                       ST_Intersection(child.simplified_geometry, parent.simplified_geometry),
                       3
                   ))
                 ELSE child.simplified_geometry
               END render_geometry
          FROM prepared child
          LEFT JOIN prepared parent ON parent.region_code=child.parent_code
    )
    INSERT INTO overview.administrative_boundary_render(
        region_code, geometry, geo_json, simplify_tolerance,
        full_point_count, render_point_count, source_geometry_sha256,
        refreshed_at, source_name, source_revision, source_license
    )
    SELECT prepared.region_code,
           prepared.render_geometry,
           ST_AsGeoJSON(prepared.render_geometry),
           prepared.tolerance,
           GREATEST(ST_NPoints(prepared.geometry), ST_NPoints(prepared.render_geometry)),
           ST_NPoints(prepared.render_geometry),
           prepared.geometry_sha256,
           now(),
           prepared.source_name,
           prepared.source_revision,
           prepared.source_license
      FROM parent_clipped prepared
    ON CONFLICT(region_code) DO UPDATE SET
        geometry=EXCLUDED.geometry,
        geo_json=EXCLUDED.geo_json,
        simplify_tolerance=EXCLUDED.simplify_tolerance,
        full_point_count=EXCLUDED.full_point_count,
        render_point_count=EXCLUDED.render_point_count,
        source_geometry_sha256=EXCLUDED.source_geometry_sha256,
        refreshed_at=EXCLUDED.refreshed_at,
        source_name=EXCLUDED.source_name,
        source_revision=EXCLUDED.source_revision,
        source_license=EXCLUDED.source_license;

    DELETE FROM overview.administrative_boundary_render render
     WHERE NOT EXISTS (
       SELECT 1 FROM overview.administrative_boundary boundary
        WHERE boundary.region_code=render.region_code
     );
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');

DO $$
DECLARE
    governed_township_count integer;
    township_count integer;
    invalid_count integer;
    outside_parent_count integer;
    sibling_overlap_count integer;
BEGIN
    SELECT count(*)
      INTO governed_township_count
      FROM platform.region region
     WHERE region.administrative_level='TOWNSHIP'
       AND region.parent_code IN (
           SELECT code FROM platform.region
            WHERE administrative_level='COUNTY'
              AND parent_code IN ('150700','230200','231100')
       );

    SELECT count(*), count(*) FILTER (WHERE NOT ST_IsValid(render.geometry) OR ST_IsEmpty(render.geometry))
      INTO township_count, invalid_count
      FROM overview.administrative_boundary_render render
      JOIN platform.region region ON region.code=render.region_code
     WHERE region.administrative_level='TOWNSHIP'
       AND region.parent_code IN (
           SELECT code FROM platform.region
            WHERE administrative_level='COUNTY'
              AND parent_code IN ('150700','230200','231100')
       );

    SELECT count(*)
      INTO outside_parent_count
      FROM overview.administrative_boundary_render child
      JOIN platform.region child_region
        ON child_region.code=child.region_code
       AND child_region.administrative_level='TOWNSHIP'
      JOIN overview.administrative_boundary_render parent
        ON parent.region_code=child_region.parent_code
     WHERE ST_Area(ST_Difference(child.geometry, parent.geometry)::geography)>10;

    SELECT count(*)
      INTO sibling_overlap_count
      FROM overview.administrative_boundary_render left_boundary
      JOIN platform.region left_region
        ON left_region.code=left_boundary.region_code
       AND left_region.administrative_level='TOWNSHIP'
      JOIN overview.administrative_boundary_render right_boundary
        ON right_boundary.region_code>left_boundary.region_code
      JOIN platform.region right_region
        ON right_region.code=right_boundary.region_code
       AND right_region.administrative_level='TOWNSHIP'
       AND right_region.parent_code=left_region.parent_code
     WHERE ST_Area(ST_Intersection(left_boundary.geometry, right_boundary.geometry)::geography)>10;

    -- The governed township master and its authoritative geometry bundle are
    -- imported after Flyway in a clean environment. Keep the migration
    -- bootstrap-safe when that bundle is absent, but enforce the full topology
    -- contract as soon as any governed township data is present.
    IF governed_township_count<>0 AND (
        governed_township_count<>232
        OR township_count<>governed_township_count
        OR invalid_count<>0
        OR outside_parent_count<>0
        OR sibling_overlap_count<>0
    ) THEN
        RAISE EXCEPTION
          'Topology render gate failed: governed townships %, rendered townships %, invalid %, outside parent %, sibling overlaps %',
          governed_township_count, township_count, invalid_count, outside_parent_count, sibling_overlap_count;
    END IF;
END;
$$;

COMMENT ON FUNCTION overview.refresh_administrative_boundary_render() IS
    'Uses coverage-aware simplification for real county/township polygons, preserving shared edges and clipping township render geometry to its rendered parent; generated partitions are forbidden.';
