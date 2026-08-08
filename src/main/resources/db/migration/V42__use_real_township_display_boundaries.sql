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
                 WHEN 'COUNTY' THEN 0.001
                 WHEN 'TOWNSHIP' THEN 0.00012
                 WHEN 'VILLAGE' THEN 0.00012
               END::double precision tolerance
          FROM overview.administrative_boundary boundary
          JOIN platform.region region ON region.code=boundary.region_code
          LEFT JOIN overview.administrative_boundary_display_reference reference
            ON reference.region_code=boundary.region_code
          LEFT JOIN prefecture_reference prefecture
            ON prefecture.region_code=boundary.region_code
    ), prepared AS (
        SELECT source.*,
               ST_Multi(ST_SimplifyPreserveTopology(source.geometry, source.tolerance)) simplified_geometry,
               encode(sha256(ST_AsEWKB(source.geometry)), 'hex') geometry_sha256
          FROM source
    )
    INSERT INTO overview.administrative_boundary_render(
        region_code, geometry, geo_json, simplify_tolerance,
        full_point_count, render_point_count, source_geometry_sha256,
        refreshed_at, source_name, source_revision, source_license
    )
    SELECT prepared.region_code,
           prepared.simplified_geometry,
           ST_AsGeoJSON(prepared.simplified_geometry),
           prepared.tolerance,
           ST_NPoints(prepared.geometry),
           ST_NPoints(prepared.simplified_geometry),
           prepared.geometry_sha256,
           now(),
           prepared.source_name,
           prepared.source_revision,
           prepared.source_license
      FROM prepared
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

COMMENT ON TABLE overview.administrative_boundary_display_reference IS
    'Source-attributed real display geometry for county and township map levels. It never replaces governed village data or the business hierarchy.';

COMMENT ON FUNCTION overview.refresh_administrative_boundary_render() IS
    'Uses source-attributed Overture/OSM county and township polygons when present; no generated township partitions are permitted.';
