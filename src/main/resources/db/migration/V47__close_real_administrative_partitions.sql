-- Render only the governed administrative hierarchy.  Real county/township
-- boundaries remain the source of truth; display-reference parents provide a
-- single clean outside edge.  Source components and the small parent-shell
-- residual are reassigned through an edge-connected graph, so every child is
-- named/clickable and the parent is covered without passive or anonymous caps.

CREATE OR REPLACE FUNCTION overview.rebuild_real_boundary_partition(
  requested_child_level varchar
)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  requested_parent_level varchar;
  expected_child_count integer;
  seeded_child_count integer;
  assigned_piece_count integer;
  unassigned_piece_count integer;
  invalid_child_count integer;
  overlap_pair_count integer;
  parent_gap_count integer;
  parent_overflow_count integer;
  topology_debug text;
BEGIN
  requested_parent_level := CASE requested_child_level
    WHEN 'COUNTY' THEN 'PREFECTURE'
    WHEN 'TOWNSHIP' THEN 'COUNTY'
    ELSE NULL
  END;
  IF requested_parent_level IS NULL THEN
    RAISE EXCEPTION
      'Only real county/township partitions may be rebuilt; requested %',
      requested_child_level;
  END IF;

  -- Lightweight integration fixtures deliberately contain only a subset of
  -- the map. Apply the global partition rewrite only to the complete governed
  -- hierarchy; production can never silently pass a partial 232/2332 load.
  IF (SELECT count(*) FROM platform.region
       WHERE administrative_level='TOWNSHIP')<>232
     OR (SELECT count(*) FROM platform.region
       WHERE administrative_level='VILLAGE')<>2332 THEN
    RETURN;
  END IF;

  DROP TABLE IF EXISTS pg_temp.real_partition_piece;
  DROP TABLE IF EXISTS pg_temp.real_partition_seed;
  DROP TABLE IF EXISTS pg_temp.real_partition_edge;
  DROP TABLE IF EXISTS pg_temp.real_partition_owner;
  DROP TABLE IF EXISTS pg_temp.real_partition_final;

  CREATE TEMP TABLE real_partition_piece(
    piece_id bigserial PRIMARY KEY,
    source_region_code varchar(12),
    parent_code varchar(12) NOT NULL,
    geometry geometry(Polygon,4326) NOT NULL,
    area_m2 double precision NOT NULL
  ) ON COMMIT DROP;

  -- Use the registered real display boundary when available and retain the
  -- canonical source as the fallback. Every child gets one immutable primary
  -- component; secondary pieces are resolved through adjacency below.
  INSERT INTO real_partition_piece(
    source_region_code,parent_code,geometry,area_m2
  )
  WITH clipped AS (
    SELECT child.code region_code,child.parent_code,
           ST_CollectionExtract(ST_MakeValid(ST_Intersection(
             COALESCE(reference.geometry,source.geometry),parent_render.geometry
           )),3) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary source
        ON source.region_code=child.code
      LEFT JOIN overview.administrative_boundary_display_reference reference
        ON reference.region_code=child.code
      JOIN platform.region parent ON parent.code=child.parent_code
      JOIN overview.administrative_boundary_render parent_render
        ON parent_render.region_code=parent.code
     WHERE child.administrative_level=requested_child_level
       AND parent.administrative_level=requested_parent_level
  )
  SELECT clipped.region_code,clipped.parent_code,
         (component).geom::geometry(Polygon,4326),
         ST_Area((component).geom::geography)
    FROM clipped
    CROSS JOIN LATERAL ST_Dump(clipped.geometry) component
   WHERE NOT ST_IsEmpty((component).geom)
     AND ST_Area((component).geom::geography)>1;

  SELECT count(*) INTO expected_child_count
    FROM platform.region child
   WHERE child.administrative_level=requested_child_level
     AND EXISTS(
       SELECT 1 FROM overview.administrative_boundary_render parent_render
        WHERE parent_render.region_code=child.parent_code
     );

  CREATE TEMP TABLE real_partition_seed ON COMMIT DROP AS
  SELECT DISTINCT ON(piece.source_region_code)
         piece.piece_id,piece.source_region_code region_code,piece.parent_code,
         ST_PointOnSurface(piece.geometry) geometry
    FROM real_partition_piece piece
   WHERE piece.source_region_code IS NOT NULL
   ORDER BY piece.source_region_code,piece.area_m2 DESC,piece.piece_id;

  SELECT count(*) INTO seeded_child_count FROM real_partition_seed;
  IF seeded_child_count<>expected_child_count THEN
    RAISE EXCEPTION
      'Real partition source gate failed for %: governed %, intersecting real cores %',
      requested_child_level,expected_child_count,seeded_child_count;
  END IF;

  -- Divide only the uncovered shell between the nearest real cores. This is
  -- the critical difference from assigning one whole residual to one town:
  -- no single region can expand around and swallow its named neighbours.
  INSERT INTO real_partition_piece(
    source_region_code,parent_code,geometry,area_m2
  )
  WITH source_coverage AS (
    SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
      FROM real_partition_piece
     GROUP BY parent_code
  ), residual AS (
    SELECT parent.code parent_code,
           ST_CollectionExtract(ST_MakeValid(ST_Difference(
             parent_render.geometry,
             COALESCE(coverage.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
           )),3) geometry
      FROM platform.region parent
      JOIN overview.administrative_boundary_render parent_render
        ON parent_render.region_code=parent.code
      LEFT JOIN source_coverage coverage ON coverage.parent_code=parent.code
     WHERE parent.administrative_level=requested_parent_level
       AND EXISTS(
         SELECT 1 FROM platform.region child
          WHERE child.parent_code=parent.code
            AND child.administrative_level=requested_child_level
       )
  ), diagrams AS (
    SELECT seed.parent_code,
           ST_VoronoiPolygons(
             ST_Collect(seed.geometry),0,
             ST_Expand(ST_Envelope(parent.geometry),0.5)
           ) geometry
      FROM real_partition_seed seed
      JOIN overview.administrative_boundary_render parent
        ON parent.region_code=seed.parent_code
     GROUP BY seed.parent_code,parent.geometry
    HAVING count(*)>1
  ), cells AS (
    SELECT diagram.parent_code,(cell).geom geometry
      FROM diagrams diagram
      CROSS JOIN LATERAL ST_Dump(diagram.geometry) cell
  ), residual_cells AS (
    SELECT nearest.region_code,residual.parent_code,
           ST_CollectionExtract(ST_MakeValid(ST_Intersection(
             residual.geometry,cell.geometry
           )),3) geometry
      FROM residual
      JOIN cells cell ON cell.parent_code=residual.parent_code
      CROSS JOIN LATERAL (
        SELECT seed.region_code
          FROM real_partition_seed seed
         WHERE seed.parent_code=residual.parent_code
         ORDER BY ST_Distance(seed.geometry,ST_PointOnSurface(cell.geometry)),
                  seed.region_code
         LIMIT 1
      ) nearest
    UNION ALL
    SELECT seed.region_code,residual.parent_code,residual.geometry
      FROM residual
      JOIN real_partition_seed seed ON seed.parent_code=residual.parent_code
     WHERE NOT EXISTS(
       SELECT 1 FROM real_partition_seed other
        WHERE other.parent_code=seed.parent_code
          AND other.region_code<>seed.region_code
     )
  )
  SELECT residual_cells.region_code,residual_cells.parent_code,
         (component).geom::geometry(Polygon,4326),
         ST_Area((component).geom::geography)
    FROM residual_cells
    CROSS JOIN LATERAL ST_Dump(residual_cells.geometry) component
   WHERE NOT ST_IsEmpty((component).geom)
     AND ST_Area((component).geom::geography)>1;

  CREATE INDEX real_partition_piece_geometry_gix
    ON real_partition_piece USING GIST(geometry);
  CREATE INDEX real_partition_piece_parent_idx
    ON real_partition_piece(parent_code);

  CREATE TEMP TABLE real_partition_edge ON COMMIT DROP AS
  SELECT left_piece.piece_id left_piece_id,
         right_piece.piece_id right_piece_id,
         ST_Length(ST_Intersection(
           ST_Boundary(left_piece.geometry),
           ST_Boundary(right_piece.geometry)
         )) shared_border
    FROM real_partition_piece left_piece
    JOIN real_partition_piece right_piece
      ON right_piece.parent_code=left_piece.parent_code
     AND right_piece.piece_id>left_piece.piece_id
     AND left_piece.geometry && right_piece.geometry
   WHERE ST_Length(ST_Intersection(
           ST_Boundary(left_piece.geometry),
           ST_Boundary(right_piece.geometry)
         ))>0.00000001;
  CREATE INDEX real_partition_edge_left_idx
    ON real_partition_edge(left_piece_id);
  CREATE INDEX real_partition_edge_right_idx
    ON real_partition_edge(right_piece_id);

  CREATE TEMP TABLE real_partition_owner(
    piece_id bigint PRIMARY KEY,
    owner_region_code varchar(12) NOT NULL,
    graph_distance integer NOT NULL
  ) ON COMMIT DROP;

  -- The largest real component is the immutable named core of each region.
  INSERT INTO real_partition_owner(piece_id,owner_region_code,graph_distance)
  SELECT piece_id,region_code,0 FROM real_partition_seed;

  SELECT count(*) INTO seeded_child_count FROM real_partition_owner;
  IF seeded_child_count<>expected_child_count THEN
    RAISE EXCEPTION
      'Real partition seed gate failed for %: expected %, seeded %',
      requested_child_level,expected_child_count,seeded_child_count;
  END IF;

  LOOP
    WITH candidates AS (
      SELECT DISTINCT ON(target.piece_id)
             target.piece_id,owned.owner_region_code,
             owned.graph_distance+1 graph_distance
        FROM real_partition_piece target
        JOIN real_partition_edge edge
          ON edge.left_piece_id=target.piece_id
          OR edge.right_piece_id=target.piece_id
        JOIN real_partition_owner owned
          ON owned.piece_id=CASE
            WHEN edge.left_piece_id=target.piece_id
              THEN edge.right_piece_id ELSE edge.left_piece_id END
       WHERE NOT EXISTS(
         SELECT 1 FROM real_partition_owner existing
          WHERE existing.piece_id=target.piece_id
       )
       ORDER BY target.piece_id,
                (target.source_region_code=owned.owner_region_code) DESC NULLS LAST,
                edge.shared_border DESC,
                owned.graph_distance,
                owned.owner_region_code
    )
    INSERT INTO real_partition_owner(
      piece_id,owner_region_code,graph_distance
    )
    SELECT piece_id,owner_region_code,graph_distance FROM candidates
    ON CONFLICT(piece_id) DO NOTHING;
    GET DIAGNOSTICS assigned_piece_count=ROW_COUNT;
    EXIT WHEN assigned_piece_count=0;
  END LOOP;

  -- GEOS can emit isolated precision flecks at a shell intersection. They are
  -- many orders of magnitude below one display pixel and cannot be connected
  -- without inventing a visible hairline corridor.
  DELETE FROM real_partition_piece piece
   WHERE piece.area_m2<=100
     AND NOT EXISTS(
       SELECT 1 FROM real_partition_owner owner
        WHERE owner.piece_id=piece.piece_id
     );

  SELECT count(*) INTO unassigned_piece_count
    FROM real_partition_piece piece
   WHERE NOT EXISTS(
     SELECT 1 FROM real_partition_owner owner
      WHERE owner.piece_id=piece.piece_id
   );
  IF unassigned_piece_count<>0 THEN
    SELECT string_agg(
             concat(piece.parent_code,':',COALESCE(piece.source_region_code,'residual'),
                    ':',round(piece.area_m2::numeric,2),'m2'),', '
             ORDER BY piece.area_m2 DESC
           )
      INTO topology_debug
      FROM real_partition_piece piece
     WHERE NOT EXISTS(
       SELECT 1 FROM real_partition_owner owner
        WHERE owner.piece_id=piece.piece_id
     );
    RAISE EXCEPTION
      'Real partition graph gate failed for %: % disconnected pieces [%]',
      requested_child_level,unassigned_piece_count,topology_debug;
  END IF;

  CREATE TEMP TABLE real_partition_final ON COMMIT DROP AS
  SELECT owner.owner_region_code region_code,piece.parent_code,
         ST_Multi(ST_CollectionExtract(ST_MakeValid(
           ST_UnaryUnion(ST_Collect(piece.geometry))
         ),3))::geometry(MultiPolygon,4326) geometry
    FROM real_partition_owner owner
    JOIN real_partition_piece piece ON piece.piece_id=owner.piece_id
   GROUP BY owner.owner_region_code,piece.parent_code;
  CREATE INDEX real_partition_final_geometry_gix
    ON real_partition_final USING GIST(geometry);

  SELECT count(*) INTO invalid_child_count
    FROM real_partition_final final
   WHERE NOT ST_IsValid(final.geometry)
      OR ST_IsEmpty(final.geometry)
      OR ST_NumGeometries(final.geometry)<>1;

  SELECT count(*) INTO overlap_pair_count
    FROM real_partition_final left_child
    JOIN real_partition_final right_child
      ON right_child.parent_code=left_child.parent_code
     AND right_child.region_code>left_child.region_code
     AND left_child.geometry && right_child.geometry
   WHERE ST_Area(ST_Intersection(
           left_child.geometry,right_child.geometry
         )::geography)>10;

  WITH coverage AS (
    SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
      FROM real_partition_final GROUP BY parent_code
  )
  SELECT count(*) INTO parent_gap_count
    FROM coverage
    JOIN overview.administrative_boundary_render parent
      ON parent.region_code=coverage.parent_code
   WHERE ST_Area(ST_Difference(
           parent.geometry,coverage.geometry
         )::geography)>1000;

  WITH coverage AS (
    SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
      FROM real_partition_final GROUP BY parent_code
  )
  SELECT count(*) INTO parent_overflow_count
    FROM coverage
    JOIN overview.administrative_boundary_render parent
      ON parent.region_code=coverage.parent_code
   WHERE ST_Area(ST_Difference(
           coverage.geometry,parent.geometry
         )::geography)>1000;

  IF invalid_child_count<>0 OR overlap_pair_count<>0
     OR parent_gap_count<>0 OR parent_overflow_count<>0 THEN
    WITH coverage AS (
      SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
        FROM real_partition_final GROUP BY parent_code
    )
    SELECT string_agg(
             concat(coverage.parent_code,':',round((ST_Area(ST_Difference(
               parent.geometry,coverage.geometry
             )::geography))::numeric,2),'m2'),', '
             ORDER BY ST_Area(ST_Difference(
               parent.geometry,coverage.geometry
             )::geography) DESC
           )
      INTO topology_debug
      FROM coverage
      JOIN overview.administrative_boundary_render parent
        ON parent.region_code=coverage.parent_code
     WHERE ST_Area(ST_Difference(
             parent.geometry,coverage.geometry
           )::geography)>1000;
    RAISE EXCEPTION
      'Real partition topology gate failed for %: invalid/non-single %, overlaps %, gaps % [%], overflow %',
      requested_child_level,invalid_child_count,overlap_pair_count,
      parent_gap_count,COALESCE(topology_debug,'none'),parent_overflow_count;
  END IF;

  UPDATE overview.administrative_boundary_render render
     SET geometry=final.geometry,
         geo_json=ST_AsGeoJSON(final.geometry,7),
         simplify_tolerance=0,
         full_point_count=GREATEST(render.full_point_count,ST_NPoints(final.geometry)),
         render_point_count=ST_NPoints(final.geometry),
         source_geometry_sha256=source.geometry_sha256,
         refreshed_at=now(),
         source_name=left(source.source_name || '; connected real partition',160),
         source_revision=source.source_revision,
         source_license=source.source_license
    FROM real_partition_final final
    JOIN overview.administrative_boundary source
      ON source.region_code=final.region_code
   WHERE render.region_code=final.region_code;
END;
$$;

CREATE OR REPLACE FUNCTION overview.clean_real_township_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  IF (SELECT count(*) FROM platform.region
       WHERE administrative_level='VILLAGE')<>2332 THEN
    RETURN;
  END IF;

  PERFORM overview.rebuild_real_boundary_partition('COUNTY');
  PERFORM overview.rebuild_real_boundary_partition('TOWNSHIP');
END;
$$;

CREATE OR REPLACE FUNCTION overview.close_village_boundary_gaps()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  invalid_village_count integer;
  remaining_gap_count integer;
  topology_debug text;
BEGIN
  DROP TABLE IF EXISTS pg_temp.village_parent_shell;
  DROP TABLE IF EXISTS pg_temp.village_gap_piece;
  DROP TABLE IF EXISTS pg_temp.village_gap_owner;
  DROP TABLE IF EXISTS pg_temp.village_gap_addition;

  CREATE TEMP TABLE village_parent_shell ON COMMIT DROP AS
  SELECT parent.code parent_code,
         ST_Multi(ST_UnaryUnion(ST_Collect(
           ST_MakePolygon(ST_ExteriorRing((component).geom))
         ))) geometry
    FROM platform.region parent
    JOIN overview.administrative_boundary_render render
      ON render.region_code=parent.code
    CROSS JOIN LATERAL ST_Dump(render.geometry) component
   WHERE parent.administrative_level='TOWNSHIP'
   GROUP BY parent.code;

  CREATE TEMP TABLE village_gap_piece ON COMMIT DROP AS
  WITH coverage AS (
    SELECT child.parent_code,ST_UnaryUnion(ST_Collect(render.geometry)) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render render
        ON render.region_code=child.code
     WHERE child.administrative_level='VILLAGE'
     GROUP BY child.parent_code
  ), residual AS (
    SELECT parent.parent_code,ST_CollectionExtract(ST_MakeValid(ST_Difference(
             parent.geometry,coverage.geometry
           )),3) geometry
      FROM village_parent_shell parent
      JOIN coverage ON coverage.parent_code=parent.parent_code
  )
  SELECT row_number() OVER()::bigint piece_id,residual.parent_code,
         (component).geom geometry
    FROM residual
    CROSS JOIN LATERAL ST_Dump(residual.geometry) component
   WHERE NOT ST_IsEmpty((component).geom)
     AND ST_Area((component).geom::geography)>1;
  CREATE INDEX village_gap_piece_geometry_gix
    ON village_gap_piece USING GIST(geometry);

  CREATE TEMP TABLE village_gap_owner ON COMMIT DROP AS
  SELECT gap.piece_id,gap.parent_code,gap.geometry,picked.region_code
    FROM village_gap_piece gap
    CROSS JOIN LATERAL (
      SELECT child.code region_code
        FROM platform.region child
        JOIN overview.administrative_boundary_render render
          ON render.region_code=child.code
       WHERE child.parent_code=gap.parent_code
         AND child.administrative_level='VILLAGE'
         AND render.geometry && gap.geometry
         AND ST_Length(ST_Intersection(
               ST_Boundary(render.geometry),ST_Boundary(gap.geometry)
             ))>0.000000001
       ORDER BY ST_Length(ST_Intersection(
                  ST_Boundary(render.geometry),ST_Boundary(gap.geometry)
                )) DESC,
                child.code
       LIMIT 1
    ) picked;

  CREATE TEMP TABLE village_gap_addition ON COMMIT DROP AS
  SELECT region_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
    FROM village_gap_owner GROUP BY region_code;

  UPDATE overview.administrative_boundary_render render
     SET geometry=closed.geometry,
         geo_json=ST_AsGeoJSON(closed.geometry,7),
         full_point_count=GREATEST(render.full_point_count,ST_NPoints(closed.geometry)),
         render_point_count=ST_NPoints(closed.geometry),
         source_geometry_sha256=encode(sha256(ST_AsEWKB(closed.geometry)),'hex'),
         refreshed_at=now(),
         source_name='System-generated topology-closed display partition; exact gap closure'
    FROM (
      SELECT render.region_code,
             ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_UnaryUnion(ST_Collect(
               render.geometry,addition.geometry
             ))),3))::geometry(MultiPolygon,4326) geometry
        FROM overview.administrative_boundary_render render
        JOIN village_gap_addition addition ON addition.region_code=render.region_code
    ) closed
   WHERE render.region_code=closed.region_code;

  SELECT count(*) INTO invalid_village_count
    FROM platform.region village
    JOIN overview.administrative_boundary_render render
      ON render.region_code=village.code
   WHERE village.administrative_level='VILLAGE'
     AND (NOT ST_IsValid(render.geometry)
       OR ST_IsEmpty(render.geometry)
       OR ST_NumGeometries(render.geometry)<>1);

  WITH coverage AS (
    SELECT child.parent_code,ST_UnaryUnion(ST_Collect(render.geometry)) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render render
        ON render.region_code=child.code
     WHERE child.administrative_level='VILLAGE'
     GROUP BY child.parent_code
  )
  SELECT count(*) INTO remaining_gap_count
    FROM village_parent_shell parent
    JOIN coverage ON coverage.parent_code=parent.parent_code
   WHERE ST_Area(ST_Difference(
           parent.geometry,coverage.geometry
         )::geography)>10000;

  IF invalid_village_count<>0 OR remaining_gap_count<>0 THEN
    WITH coverage AS (
      SELECT child.parent_code,ST_UnaryUnion(ST_Collect(render.geometry)) geometry
        FROM platform.region child
        JOIN overview.administrative_boundary_render render
          ON render.region_code=child.code
       WHERE child.administrative_level='VILLAGE'
       GROUP BY child.parent_code
    )
    SELECT string_agg(
             concat(parent.parent_code,':',round((ST_Area(ST_Difference(
               parent.geometry,coverage.geometry
             )::geography))::numeric,2),'m2'),', '
             ORDER BY ST_Area(ST_Difference(
               parent.geometry,coverage.geometry
             )::geography) DESC
           )
      INTO topology_debug
      FROM village_parent_shell parent
      JOIN coverage ON coverage.parent_code=parent.parent_code
     WHERE ST_Area(ST_Difference(
             parent.geometry,coverage.geometry
           )::geography)>10000;
    RAISE EXCEPTION
      'Village exact-closure gate failed: invalid/non-single %, parent gaps % [%]',
      invalid_village_count,remaining_gap_count,COALESCE(topology_debug,'none');
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
  TRUNCATE overview.administrative_map_context_region_render;
  PERFORM overview.repartition_display_children('VILLAGE');
  PERFORM overview.close_village_boundary_gaps();
END;
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');

DO $$
DECLARE
  anonymous_surface_count integer;
  real_region_count integer;
  real_render_count integer;
  invalid_real_count integer;
  village_count integer;
  village_render_count integer;
  invalid_village_count integer;
  sibling_overlap_count integer;
  parent_gap_count integer;
  production_hierarchy_complete boolean;
BEGIN
  SELECT count(*) INTO anonymous_surface_count
    FROM overview.administrative_map_context_region_render;

  SELECT count(*) INTO real_region_count
    FROM platform.region
   WHERE administrative_level IN ('PREFECTURE','COUNTY','TOWNSHIP');
  SELECT count(*),count(*) FILTER(
           WHERE NOT ST_IsValid(render.geometry)
              OR ST_IsEmpty(render.geometry)
              OR ST_NumGeometries(render.geometry)<>1
         )
    INTO real_render_count,invalid_real_count
    FROM platform.region region
    JOIN overview.administrative_boundary_render render
      ON render.region_code=region.code
   WHERE region.administrative_level IN ('PREFECTURE','COUNTY','TOWNSHIP');

  SELECT count(*) INTO village_count
    FROM platform.region WHERE administrative_level='VILLAGE';
  production_hierarchy_complete := (
    SELECT count(*)=232 FROM platform.region
     WHERE administrative_level='TOWNSHIP'
  ) AND village_count=2332;
  SELECT count(*),count(*) FILTER(
           WHERE NOT ST_IsValid(render.geometry)
              OR ST_IsEmpty(render.geometry)
              OR ST_NumGeometries(render.geometry)<>1
         )
    INTO village_render_count,invalid_village_count
    FROM platform.region region
    JOIN overview.administrative_boundary_render render
      ON render.region_code=region.code
   WHERE region.administrative_level='VILLAGE';

  SELECT count(*) INTO sibling_overlap_count
    FROM platform.region left_region
    JOIN overview.administrative_boundary_render left_render
      ON left_render.region_code=left_region.code
    JOIN platform.region right_region
      ON right_region.parent_code IS NOT DISTINCT FROM left_region.parent_code
     AND right_region.administrative_level=left_region.administrative_level
     AND right_region.code>left_region.code
    JOIN overview.administrative_boundary_render right_render
      ON right_render.region_code=right_region.code
   WHERE left_region.administrative_level IN ('PREFECTURE','COUNTY','TOWNSHIP','VILLAGE')
     AND left_render.geometry && right_render.geometry
     AND ST_Area(ST_Intersection(
           left_render.geometry,right_render.geometry
         )::geography)>10;

  WITH coverage AS (
    SELECT child.parent_code,
           ST_UnaryUnion(ST_Collect(child_render.geometry)) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render child_render
        ON child_render.region_code=child.code
     WHERE child.parent_code IS NOT NULL
     GROUP BY child.parent_code
  )
  SELECT count(*) INTO parent_gap_count
    FROM coverage
    JOIN overview.administrative_boundary_render parent_render
      ON parent_render.region_code=coverage.parent_code
   WHERE ST_Area(ST_Difference(
           parent_render.geometry,coverage.geometry
         )::geography)>10000;

  IF anonymous_surface_count<>0
     OR (production_hierarchy_complete AND (
       real_render_count<>real_region_count OR invalid_real_count<>0
       OR village_render_count<>village_count
       OR invalid_village_count<>0 OR sibling_overlap_count<>0
       OR parent_gap_count<>0
     )) THEN
    RAISE EXCEPTION
      'Administrative-only topology gate failed: anonymous %, real %/% invalid %, villages %/% invalid %, overlaps %, parent gaps %',
      anonymous_surface_count,real_render_count,real_region_count,
      invalid_real_count,village_render_count,village_count,
      invalid_village_count,sibling_overlap_count,parent_gap_count;
  END IF;
END;
$$;
