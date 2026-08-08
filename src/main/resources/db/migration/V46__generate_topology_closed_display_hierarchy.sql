DO $$
BEGIN
  IF to_regprocedure('overview.refresh_administrative_boundary_render_source()') IS NULL THEN
    EXECUTE 'ALTER FUNCTION overview.refresh_administrative_boundary_render() '
      || 'RENAME TO refresh_administrative_boundary_render_source';
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION overview.repartition_display_children(requested_child_level varchar)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  expected_child_count integer;
  generated_child_count integer;
  invalid_child_count integer;
  unowned_piece_count integer;
  overlap_pair_count integer;
  parent_gap_count integer;
  inserted_owner_count integer;
  requested_parent_level varchar;
  topology_debug text;
BEGIN
  requested_parent_level := CASE requested_child_level
    WHEN 'VILLAGE' THEN 'TOWNSHIP'
    ELSE NULL
  END;
  IF requested_parent_level IS NULL THEN
    RAISE EXCEPTION
      'Only village display boundaries may be generated; requested % must retain its real administrative boundary',
      requested_child_level;
  END IF;

  DROP TABLE IF EXISTS pg_temp.display_partition_parents;
  DROP TABLE IF EXISTS pg_temp.display_partition_seeds;
  DROP TABLE IF EXISTS pg_temp.display_partition_cells;
  DROP TABLE IF EXISTS pg_temp.display_partition_raw;
  DROP TABLE IF EXISTS pg_temp.display_partition_pieces;
  DROP TABLE IF EXISTS pg_temp.display_partition_edges;
  DROP TABLE IF EXISTS pg_temp.display_partition_owner;
  DROP TABLE IF EXISTS pg_temp.display_partition_final;
  DROP TABLE IF EXISTS pg_temp.display_partition_complete;
  DROP TABLE IF EXISTS pg_temp.display_partition_disjoint;

  CREATE TEMP TABLE display_partition_parents ON COMMIT DROP AS
  SELECT parent.code parent_code,
         ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_ReducePrecision(
           ST_UnaryUnion(ST_Collect(
             ST_MakePolygon(ST_ExteriorRing((component).geom))
           )),
           0.000001
         )),3)) geometry,
         count(child.code)::integer child_count
    FROM platform.region parent
    JOIN overview.administrative_boundary_render parent_render
      ON parent_render.region_code=parent.code
    CROSS JOIN LATERAL ST_Dump(parent_render.geometry) component
    JOIN platform.region child
      ON child.parent_code=parent.code
     AND child.administrative_level=requested_child_level
   WHERE parent.administrative_level=requested_parent_level
   GROUP BY parent.code;

  SELECT count(*) INTO expected_child_count
    FROM platform.region child
    JOIN display_partition_parents parent ON parent.parent_code=child.parent_code
   WHERE child.administrative_level=requested_child_level;
  IF expected_child_count=0 THEN
    RETURN;
  END IF;

  CREATE TEMP TABLE display_partition_seeds ON COMMIT DROP AS
  WITH raw AS (
    SELECT child.code region_code,
           child.parent_code,
           parent.geometry parent_geometry,
           parent.child_count,
           ST_PointOnSurface(parent.geometry) parent_anchor,
           COALESCE(
             CASE WHEN requested_child_level='VILLAGE' THEN location.wgs84_coordinate END,
             CASE WHEN requested_child_level='TOWNSHIP' THEN derived_location.geometry END,
             ST_PointOnSurface(source_render.geometry),
             location.wgs84_coordinate,
             ST_PointOnSurface(parent.geometry)
           ) raw_seed
      FROM platform.region child
      JOIN display_partition_parents parent ON parent.parent_code=child.parent_code
      LEFT JOIN overview.administrative_boundary_render source_render
        ON source_render.region_code=child.code
      LEFT JOIN platform.region_location location ON location.region_code=child.code
      LEFT JOIN LATERAL (
        SELECT ST_Centroid(ST_Collect(village_location.wgs84_coordinate)) geometry
          FROM platform.region village
          JOIN platform.region_location village_location
            ON village_location.region_code=village.code
         WHERE village.parent_code=child.code
           AND village.administrative_level='VILLAGE'
      ) derived_location ON true
     WHERE child.administrative_level=requested_child_level
  ), clamped AS (
    SELECT raw.*,
           CASE
             WHEN ST_Contains(parent_geometry,raw_seed) THEN raw_seed
             ELSE ST_LineInterpolatePoint(
               ST_MakeLine(ST_ClosestPoint(parent_geometry,raw_seed),parent_anchor),
               0.02
             )
           END clamped_seed
      FROM raw
  ), jittered AS (
    SELECT clamped.*,
           ST_Translate(
             clamped_seed,
             ((('x'||substr(md5(region_code),1,8))::bit(32)::bigint % 2001)-1000)*1e-8,
             ((('x'||substr(md5(region_code),9,8))::bit(32)::bigint % 2001)-1000)*1e-8
           ) jittered_seed
      FROM clamped
  )
  SELECT region_code,parent_code,parent_geometry,child_count,
         CASE
           WHEN ST_Contains(parent_geometry,jittered_seed) THEN jittered_seed
           ELSE ST_LineInterpolatePoint(ST_MakeLine(clamped_seed,parent_anchor),0.02)
         END seed
    FROM jittered;

  -- Village geometry is explicitly synthetic.  Generate exactly one stable,
  -- unique interior seed per village instead of clamping unreliable village
  -- coordinates onto the same township edge (which previously gave several
  -- villages the identical Voronoi cell).
  TRUNCATE display_partition_seeds;
  INSERT INTO display_partition_seeds(
    region_code,parent_code,parent_geometry,child_count,seed
  )
  WITH generated AS (
    SELECT parent.parent_code,parent.geometry parent_geometry,
           parent.child_count,(point).geom seed,
           row_number() OVER(
             PARTITION BY parent.parent_code
             ORDER BY ST_X((point).geom),ST_Y((point).geom)
           ) seed_rank
      FROM display_partition_parents parent
      CROSS JOIN LATERAL ST_Dump(ST_GeneratePoints(
        parent.geometry,
        parent.child_count,
        ((('x'||substr(md5(parent.parent_code),1,8))::bit(32)::bigint
           % 2147483646)+1)::integer
      )) point
  ), children AS (
    SELECT child.code region_code,child.parent_code,
           row_number() OVER(
             PARTITION BY child.parent_code ORDER BY child.code
           ) seed_rank
      FROM platform.region child
     WHERE child.administrative_level=requested_child_level
  )
  SELECT children.region_code,generated.parent_code,
         generated.parent_geometry,generated.child_count,generated.seed
    FROM generated
    JOIN children USING(parent_code,seed_rank);

  CREATE TEMP TABLE display_partition_cells ON COMMIT DROP AS
  WITH diagrams AS (
    SELECT parent_code,parent_geometry,child_count,
           ST_VoronoiPolygons(
             ST_Collect(seed),
             0,
             ST_Expand(ST_Envelope(parent_geometry),0.5)
           ) diagram
      FROM display_partition_seeds
     WHERE child_count>1
     GROUP BY parent_code,parent_geometry,child_count
  )
  SELECT parent_code,(ST_Dump(diagram)).geom geometry FROM diagrams;
  CREATE INDEX display_partition_cells_geometry_gix
    ON display_partition_cells USING GIST(geometry);

  CREATE TEMP TABLE display_partition_raw ON COMMIT DROP AS
  WITH assigned AS (
    SELECT owner.region_code,cell.parent_code,
           ST_Multi(ST_CollectionExtract(ST_MakeValid(
             ST_Intersection(
               owner.parent_geometry,
               ST_ReducePrecision(cell.geometry,0.000001),
               0.000001
             )
           ),3)) geometry
      FROM display_partition_cells cell
      CROSS JOIN LATERAL (
        SELECT seed.region_code,seed.parent_geometry
          FROM display_partition_seeds seed
         WHERE seed.parent_code=cell.parent_code
           AND seed.child_count>1
         ORDER BY ST_Distance(
                    seed.seed,ST_PointOnSurface(cell.geometry)
                  ),
                  seed.region_code
         LIMIT 1
      ) owner
  ), singletons AS (
    SELECT region_code,parent_code,parent_geometry geometry
      FROM display_partition_seeds
     WHERE child_count=1
  )
  SELECT * FROM assigned
  UNION ALL
  SELECT * FROM singletons;

  SELECT count(*) INTO generated_child_count FROM display_partition_raw;
  IF generated_child_count<>expected_child_count THEN
    RAISE EXCEPTION
      'Display partition seed gate failed for %: expected %, generated %',
      requested_child_level,expected_child_count,generated_child_count;
  END IF;

  CREATE TEMP TABLE display_partition_pieces ON COMMIT DROP AS
  SELECT row_number() OVER()::integer piece_id,
         raw.region_code,raw.parent_code,(piece).geom geometry,
         ST_Area((piece).geom::geography) area_m2
    FROM display_partition_raw raw
    CROSS JOIN LATERAL ST_Dump(raw.geometry) piece;
  CREATE INDEX display_partition_pieces_geometry_gix
    ON display_partition_pieces USING GIST(geometry);
  CREATE INDEX display_partition_pieces_parent_idx
    ON display_partition_pieces(parent_code);

  CREATE TEMP TABLE display_partition_edges ON COMMIT DROP AS
  SELECT left_piece.piece_id left_piece_id,
         right_piece.piece_id right_piece_id,
         ST_Length(ST_Intersection(
           ST_Boundary(left_piece.geometry),ST_Boundary(right_piece.geometry)
         )::geography) shared_border_m
    FROM display_partition_pieces left_piece
    JOIN display_partition_pieces right_piece
      ON right_piece.parent_code=left_piece.parent_code
     AND right_piece.piece_id>left_piece.piece_id
     AND left_piece.geometry && right_piece.geometry
   WHERE ST_Length(ST_Intersection(
           ST_Boundary(left_piece.geometry),ST_Boundary(right_piece.geometry)
         )::geography)>0.01;
  CREATE INDEX display_partition_edges_left_idx
    ON display_partition_edges(left_piece_id);
  CREATE INDEX display_partition_edges_right_idx
    ON display_partition_edges(right_piece_id);

  CREATE TEMP TABLE display_partition_owner(
    piece_id integer PRIMARY KEY,
    owner_region_code varchar(12) NOT NULL,
    graph_distance integer NOT NULL
  ) ON COMMIT DROP;
  INSERT INTO display_partition_owner(piece_id,owner_region_code,graph_distance)
  SELECT DISTINCT ON(region_code) piece_id,region_code,0
    FROM display_partition_pieces
   ORDER BY region_code,area_m2 DESC,piece_id;

  LOOP
    WITH candidates AS (
      SELECT DISTINCT ON(target.piece_id)
             target.piece_id,owned.owner_region_code,
             owned.graph_distance+1 graph_distance
        FROM display_partition_owner owned
        JOIN display_partition_edges edge
          ON edge.left_piece_id=owned.piece_id
          OR edge.right_piece_id=owned.piece_id
        JOIN display_partition_pieces target
          ON target.piece_id=CASE
               WHEN edge.left_piece_id=owned.piece_id THEN edge.right_piece_id
               ELSE edge.left_piece_id
             END
        LEFT JOIN display_partition_owner existing ON existing.piece_id=target.piece_id
       WHERE existing.piece_id IS NULL
       ORDER BY target.piece_id,edge.shared_border_m DESC,owned.owner_region_code
    )
    INSERT INTO display_partition_owner(piece_id,owner_region_code,graph_distance)
    SELECT * FROM candidates ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS inserted_owner_count=ROW_COUNT;
    EXIT WHEN inserted_owner_count=0;
  END LOOP;

  SELECT count(*) INTO unowned_piece_count
    FROM display_partition_pieces piece
    LEFT JOIN display_partition_owner owner USING(piece_id)
   WHERE owner.piece_id IS NULL
     AND piece.area_m2>=100000;
  IF unowned_piece_count<>0 THEN
    RAISE EXCEPTION
      'Display partition connectivity gate failed for %: % pieces have no connected primary region',
      requested_child_level,unowned_piece_count;
  END IF;

  CREATE TEMP TABLE display_partition_final ON COMMIT DROP AS
  SELECT owner.owner_region_code region_code,piece.parent_code,
         ST_Multi(ST_UnaryUnion(ST_Collect(piece.geometry))) geometry
    FROM display_partition_pieces piece
    JOIN display_partition_owner owner USING(piece_id)
   GROUP BY owner.owner_region_code,piece.parent_code;
  CREATE INDEX display_partition_final_geometry_gix
    ON display_partition_final USING GIST(geometry);

  CREATE TEMP TABLE display_partition_complete ON COMMIT DROP AS
  WITH parent_coverage AS (
    SELECT parent.parent_code,parent.geometry parent_geometry,
           ST_UnaryUnion(ST_Collect(child.geometry)) child_geometry
      FROM display_partition_parents parent
      JOIN display_partition_final child USING(parent_code)
     GROUP BY parent.parent_code,parent.geometry
  ), residual AS (
    SELECT coverage.parent_code,(piece).geom geometry
      FROM parent_coverage coverage
      CROSS JOIN LATERAL ST_Dump(ST_Multi(ST_CollectionExtract(ST_MakeValid(
        ST_Difference(coverage.parent_geometry,coverage.child_geometry)
      ),3))) piece
     WHERE NOT ST_IsEmpty((piece).geom)
  ), residual_owner AS (
    SELECT residual.parent_code,residual.geometry,picked.region_code
      FROM residual
      CROSS JOIN LATERAL (
        SELECT child.region_code
          FROM display_partition_final child
         WHERE child.parent_code=residual.parent_code
         ORDER BY ST_Length(ST_Intersection(
                    ST_Boundary(child.geometry),ST_Boundary(residual.geometry)
                  )::geography) DESC,
                  ST_Distance(child.geometry::geography,residual.geometry::geography),
                  child.region_code
         LIMIT 1
      ) picked
  ), all_parts AS (
    SELECT region_code,parent_code,geometry FROM display_partition_final
    UNION ALL
    SELECT region_code,parent_code,geometry FROM residual_owner
  )
  SELECT region_code,parent_code,
         ST_Multi(ST_CollectionExtract(ST_MakeValid(
           ST_UnaryUnion(ST_Collect(geometry))
         ),3)) geometry
    FROM all_parts
   GROUP BY region_code,parent_code;

  -- A precision-grid residual can occasionally attach to the wrong side of a
  -- border and leave a second microscopic component. Run the same connected
  -- ownership pass once more over the complete coverage, so the release table
  -- itself—not only the raw Voronoi cells—has one component per region.
  DROP TABLE display_partition_pieces;
  DROP TABLE display_partition_edges;
  DROP TABLE display_partition_owner;
  DROP TABLE display_partition_final;

  CREATE TEMP TABLE display_partition_pieces ON COMMIT DROP AS
  SELECT row_number() OVER()::integer piece_id,
         complete.region_code,complete.parent_code,(piece).geom geometry,
         ST_Area((piece).geom::geography) area_m2
    FROM display_partition_complete complete
    CROSS JOIN LATERAL ST_Dump(complete.geometry) piece;
  CREATE INDEX display_partition_pieces_geometry_gix
    ON display_partition_pieces USING GIST(geometry);

  CREATE TEMP TABLE display_partition_edges ON COMMIT DROP AS
  SELECT left_piece.piece_id left_piece_id,
         right_piece.piece_id right_piece_id,
         ST_Length(ST_Intersection(
           ST_Boundary(left_piece.geometry),ST_Boundary(right_piece.geometry)
         )::geography) shared_border_m
    FROM display_partition_pieces left_piece
    JOIN display_partition_pieces right_piece
      ON right_piece.parent_code=left_piece.parent_code
     AND right_piece.piece_id>left_piece.piece_id
     AND left_piece.geometry && right_piece.geometry
   WHERE ST_Length(ST_Intersection(
           ST_Boundary(left_piece.geometry),ST_Boundary(right_piece.geometry)
         )::geography)>0.01;
  CREATE INDEX display_partition_edges_left_idx
    ON display_partition_edges(left_piece_id);
  CREATE INDEX display_partition_edges_right_idx
    ON display_partition_edges(right_piece_id);

  CREATE TEMP TABLE display_partition_owner(
    piece_id integer PRIMARY KEY,
    owner_region_code varchar(12) NOT NULL,
    graph_distance integer NOT NULL
  ) ON COMMIT DROP;
  INSERT INTO display_partition_owner(piece_id,owner_region_code,graph_distance)
  SELECT DISTINCT ON(region_code) piece_id,region_code,0
    FROM display_partition_pieces
   ORDER BY region_code,area_m2 DESC,piece_id;

  LOOP
    WITH candidates AS (
      SELECT DISTINCT ON(target.piece_id)
             target.piece_id,owned.owner_region_code,
             owned.graph_distance+1 graph_distance
        FROM display_partition_owner owned
        JOIN display_partition_edges edge
          ON edge.left_piece_id=owned.piece_id
          OR edge.right_piece_id=owned.piece_id
        JOIN display_partition_pieces target
          ON target.piece_id=CASE
               WHEN edge.left_piece_id=owned.piece_id THEN edge.right_piece_id
               ELSE edge.left_piece_id
             END
        LEFT JOIN display_partition_owner existing ON existing.piece_id=target.piece_id
       WHERE existing.piece_id IS NULL
       ORDER BY target.piece_id,edge.shared_border_m DESC,owned.owner_region_code
    )
    INSERT INTO display_partition_owner(piece_id,owner_region_code,graph_distance)
    SELECT * FROM candidates ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS inserted_owner_count=ROW_COUNT;
    EXIT WHEN inserted_owner_count=0;
  END LOOP;

  SELECT count(*) INTO unowned_piece_count
    FROM display_partition_pieces piece
    LEFT JOIN display_partition_owner owner USING(piece_id)
   WHERE owner.piece_id IS NULL
     AND piece.area_m2>=100000;
  IF unowned_piece_count<>0 THEN
    SELECT string_agg(piece.region_code||':'||round(piece.area_m2::numeric,3)||'m2',', ' ORDER BY piece.region_code)
      INTO topology_debug
      FROM display_partition_pieces piece
      LEFT JOIN display_partition_owner owner USING(piece_id)
     WHERE owner.piece_id IS NULL;
    RAISE EXCEPTION
      'Display partition final connectivity gate failed for %: % pieces have no connected primary region [%]',
      requested_child_level,unowned_piece_count,topology_debug;
  END IF;

  -- Sub-pixel precision remnants below 100,000 m² cannot be connected to any
  -- released polygon and are invisible at the dashboard's maximum scale.
  -- Dropping them prevents a microscopic second component from becoming a
  -- selectable flying island; the parent coverage gate below caps their total.
  DELETE FROM display_partition_pieces piece
   WHERE NOT EXISTS (
     SELECT 1 FROM display_partition_owner owner WHERE owner.piece_id=piece.piece_id
   );

  CREATE TEMP TABLE display_partition_final ON COMMIT DROP AS
  SELECT owner.owner_region_code region_code,piece.parent_code,
         ST_Multi(ST_UnaryUnion(ST_Collect(piece.geometry))) geometry
    FROM display_partition_pieces piece
    JOIN display_partition_owner owner USING(piece_id)
   GROUP BY owner.owner_region_code,piece.parent_code;
  DROP TABLE display_partition_complete;
  ALTER TABLE display_partition_final RENAME TO display_partition_complete;

  -- Precision-grid unioning can leave metre-scale overlaps between otherwise
  -- adjacent synthetic village cells.  Resolve them deterministically before
  -- release; the winning neighbour keeps the same area, so parent coverage is
  -- unchanged and no township geometry is involved in this operation.
  CREATE TEMP TABLE display_partition_disjoint ON COMMIT DROP AS
  WITH carved AS (
    SELECT current.region_code,current.parent_code,
           ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Difference(
             current.geometry,
             COALESCE(blocker.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
           )),3)) geometry
      FROM display_partition_complete current
      LEFT JOIN LATERAL (
        SELECT ST_UnaryUnion(ST_Collect(other.geometry)) geometry
          FROM display_partition_complete other
         WHERE other.parent_code=current.parent_code
           AND other.region_code<current.region_code
           AND other.geometry && current.geometry
      ) blocker ON true
  ), components AS (
    SELECT carved.region_code,carved.parent_code,(component).geom geometry,
           row_number() OVER(
             PARTITION BY carved.region_code
             ORDER BY ST_Area((component).geom::geography) DESC
           ) component_rank
      FROM carved
      CROSS JOIN LATERAL ST_Dump(carved.geometry) component
     WHERE NOT ST_IsEmpty(carved.geometry)
  )
  SELECT region_code,parent_code,
         ST_Multi(geometry)::geometry(MultiPolygon,4326) geometry
    FROM components
   WHERE component_rank=1;

  DROP TABLE display_partition_complete;
  ALTER TABLE display_partition_disjoint RENAME TO display_partition_complete;

  SELECT count(*) FILTER(
           WHERE geometry IS NULL OR ST_IsEmpty(geometry) OR NOT ST_IsValid(geometry)
              OR ST_NumGeometries(geometry)<>1
              OR ST_NumInteriorRings(ST_GeometryN(geometry,1))<>0
         )
    INTO invalid_child_count
    FROM display_partition_complete;

  SELECT count(*) INTO overlap_pair_count
    FROM display_partition_complete left_child
    JOIN display_partition_complete right_child
      ON right_child.parent_code=left_child.parent_code
     AND right_child.region_code>left_child.region_code
     AND left_child.geometry && right_child.geometry
   WHERE ST_Area(ST_Intersection(
           left_child.geometry,right_child.geometry
         )::geography)>1;

  WITH child_coverage AS (
    SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
      FROM display_partition_complete
     GROUP BY parent_code
  )
  SELECT count(*) INTO parent_gap_count
    FROM display_partition_parents parent
    JOIN child_coverage children USING(parent_code)
   WHERE ST_Area(ST_Difference(parent.geometry,children.geometry)::geography)>100000;

  SELECT count(*) INTO generated_child_count FROM display_partition_complete;
  IF generated_child_count<>expected_child_count
     OR invalid_child_count<>0
     OR overlap_pair_count<>0
     OR parent_gap_count<>0 THEN
    SELECT string_agg(region_code||':parts='||ST_NumGeometries(geometry)||
             ':holes='||ST_NumInteriorRings(ST_GeometryN(geometry,1)),', ' ORDER BY region_code)
      INTO topology_debug
      FROM display_partition_complete
     WHERE geometry IS NULL OR ST_IsEmpty(geometry) OR NOT ST_IsValid(geometry)
        OR ST_NumGeometries(geometry)<>1
        OR ST_NumInteriorRings(ST_GeometryN(geometry,1))<>0;
    RAISE EXCEPTION
      'Display partition topology gate failed for %: expected %, generated %, invalid/non-single/holey % [%], overlaps %, parent gaps %',
      requested_child_level,expected_child_count,generated_child_count,
      invalid_child_count,COALESCE(topology_debug,'none'),overlap_pair_count,parent_gap_count;
  END IF;

  IF requested_child_level='VILLAGE' THEN
    INSERT INTO overview.administrative_boundary(
      region_code,geometry,source_name,source_url,source_revision,source_license,
      source_feature_id,source_effective_on,geometry_sha256,loaded_at
    )
    SELECT region_code,geometry,
           'System-generated topology-closed display partition',
           'urn:cofco:overview:generated-display-boundary',
           'v46-2026-08-05','Internal display-only generated geometry',
           region_code,DATE '2026-08-05',
           encode(sha256(ST_AsEWKB(geometry)),'hex'),now()
      FROM display_partition_complete
    ON CONFLICT(region_code) DO UPDATE SET
      geometry=EXCLUDED.geometry,
      source_name=EXCLUDED.source_name,
      source_url=EXCLUDED.source_url,
      source_revision=EXCLUDED.source_revision,
      source_license=EXCLUDED.source_license,
      source_feature_id=EXCLUDED.source_feature_id,
      source_effective_on=EXCLUDED.source_effective_on,
      geometry_sha256=EXCLUDED.geometry_sha256,
      loaded_at=EXCLUDED.loaded_at;
  ELSE
    INSERT INTO overview.administrative_boundary(
      region_code,geometry,source_name,source_url,source_revision,source_license,
      source_feature_id,source_effective_on,geometry_sha256,loaded_at
    )
    SELECT region_code,geometry,
           'System-generated topology-closed display partition',
           'urn:cofco:overview:generated-display-boundary',
           'v46-2026-08-05','Internal display-only generated geometry',
           region_code,DATE '2026-08-05',
           encode(sha256(ST_AsEWKB(geometry)),'hex'),now()
      FROM display_partition_complete
    ON CONFLICT(region_code) DO NOTHING;
  END IF;

  INSERT INTO overview.administrative_boundary_render(
    region_code,geometry,geo_json,simplify_tolerance,
    full_point_count,render_point_count,source_geometry_sha256,
    refreshed_at,source_name,source_revision,source_license
  )
  SELECT region_code,geometry,ST_AsGeoJSON(geometry),0,
         ST_NPoints(geometry),ST_NPoints(geometry),
         encode(sha256(ST_AsEWKB(geometry)),'hex'),now(),
         'System-generated topology-closed display partition',
         'v46-2026-08-05','Internal display-only generated geometry'
    FROM display_partition_complete
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
END;
$$;

CREATE OR REPLACE FUNCTION overview.clean_real_township_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  DROP TABLE IF EXISTS pg_temp.real_township_cleaned;
  DROP TABLE IF EXISTS pg_temp.real_township_corridor;
  DROP TABLE IF EXISTS pg_temp.real_township_resolved;
  DROP TABLE IF EXISTS pg_temp.real_township_residual;
  DROP TABLE IF EXISTS pg_temp.real_township_preliminary;
  DROP TABLE IF EXISTS pg_temp.real_township_hole_corridor;
  DROP TABLE IF EXISTS pg_temp.real_township_corridor_enlarged;
  DROP TABLE IF EXISTS pg_temp.real_township_corridor_carved;
  DROP TABLE IF EXISTS pg_temp.real_township_final;
  DROP TABLE IF EXISTS pg_temp.real_township_complete;

  CREATE TEMP TABLE real_township_cleaned ON COMMIT DROP AS
  WITH ranked_components AS (
    SELECT region.code region_code,region.parent_code,
           parent.geometry parent_geometry,component.geom geometry,
           row_number() OVER (
             PARTITION BY render.region_code
             ORDER BY ST_Area(component.geom::geography) DESC
           ) component_rank
      FROM overview.administrative_boundary_render render
      JOIN platform.region region ON region.code=render.region_code
      LEFT JOIN overview.administrative_boundary_render parent
        ON parent.region_code=region.parent_code
      CROSS JOIN LATERAL ST_Dump(
        ST_CollectionExtract(ST_MakeValid(render.geometry),3)
      ) component
     WHERE region.administrative_level IN ('PREFECTURE','COUNTY','TOWNSHIP')
  ), clipped AS (
    SELECT region_code,parent_code,parent_geometry,
           ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_ReducePrecision(
             ST_Intersection(
               ST_MakePolygon(ST_ExteriorRing(geometry)),parent_geometry
             ),0.000001
           )),3)) geometry
      FROM ranked_components
     WHERE component_rank=1
  )
  SELECT *,ST_Area(geometry::geography) area_m2 FROM clipped;
  CREATE INDEX real_township_cleaned_geometry_gix
    ON real_township_cleaned USING GIST(geometry);

  -- A genuine nested township otherwise forces its host to keep a visible
  -- interior ring. Connect the nested region to the host exterior through a
  -- sub-pixel display corridor; the canonical source geometry is untouched.
  CREATE TEMP TABLE real_township_corridor ON COMMIT DROP AS
  SELECT inner_region.region_code owner_region_code,
         host.region_code host_region_code,
         ST_Intersection(
           host.parent_geometry,
           ST_Buffer(
             ST_ShortestLine(inner_region.geometry,ST_Boundary(host.geometry)),
             0.00002,'endcap=square join=mitre'
           )
         ) geometry
    FROM real_township_cleaned inner_region
    JOIN real_township_cleaned host
      ON host.parent_code=inner_region.parent_code
     AND host.region_code<>inner_region.region_code
     AND host.area_m2>inner_region.area_m2
     AND ST_Contains(host.geometry,ST_PointOnSurface(inner_region.geometry))
   WHERE ST_Area(ST_Intersection(
           host.geometry,inner_region.geometry
         )::geography)>1;

  CREATE TEMP TABLE real_township_resolved ON COMMIT DROP AS
  WITH carved AS (
    SELECT current.region_code,current.parent_code,current.parent_geometry,
           ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Difference(
             current.geometry,
             COALESCE(blocker.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
           )),3)) geometry
      FROM real_township_cleaned current
      LEFT JOIN LATERAL (
        SELECT ST_UnaryUnion(ST_Collect(piece.geometry)) geometry
          FROM (
            SELECT other.geometry
              FROM real_township_cleaned other
             WHERE other.parent_code=current.parent_code
               AND other.region_code<>current.region_code
               AND (other.area_m2,other.region_code)<(current.area_m2,current.region_code)
               AND other.geometry && current.geometry
            UNION ALL
            SELECT corridor.geometry
              FROM real_township_corridor corridor
             WHERE corridor.host_region_code=current.region_code
          ) piece
      ) blocker ON true
  ), enlarged AS (
    SELECT carved.region_code,carved.parent_code,carved.parent_geometry,
           ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_UnaryUnion(ST_Collect(
             carved.geometry,
             COALESCE(corridor.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
           ))),3)) geometry
      FROM carved
      LEFT JOIN LATERAL (
        SELECT ST_UnaryUnion(ST_Collect(geometry)) geometry
          FROM real_township_corridor
         WHERE owner_region_code=carved.region_code
      ) corridor ON true
  )
  SELECT * FROM enlarged WHERE NOT ST_IsEmpty(geometry);
  CREATE INDEX real_township_resolved_geometry_gix
    ON real_township_resolved USING GIST(geometry);

  CREATE TEMP TABLE real_township_residual ON COMMIT DROP AS
  WITH parent_coverage AS (
    SELECT resolved.parent_code,resolved.parent_geometry,
           ST_UnaryUnion(ST_Collect(resolved.geometry)) geometry
      FROM real_township_resolved resolved
     GROUP BY resolved.parent_code,resolved.parent_geometry
  )
  SELECT row_number() OVER()::integer residual_id,coverage.parent_code,
         (piece).geom geometry
    FROM parent_coverage coverage
    CROSS JOIN LATERAL ST_Dump(ST_Multi(ST_CollectionExtract(ST_MakeValid(
      ST_Difference(coverage.parent_geometry,coverage.geometry)
    ),3))) piece
   WHERE NOT ST_IsEmpty((piece).geom);
  CREATE INDEX real_township_residual_geometry_gix
    ON real_township_residual USING GIST(geometry);

  CREATE TEMP TABLE real_township_preliminary ON COMMIT DROP AS
  WITH owned_residual AS (
    SELECT residual.geometry,picked.region_code
      FROM real_township_residual residual
      CROSS JOIN LATERAL (
        SELECT township.region_code
          FROM real_township_resolved township
         WHERE township.parent_code=residual.parent_code
         ORDER BY ST_Length(ST_Intersection(
                    ST_Boundary(township.geometry),ST_Boundary(residual.geometry)
                  )::geography) DESC,
                  ST_Distance(township.geometry::geography,residual.geometry::geography),
                  township.region_code
         LIMIT 1
      ) picked
  ), all_geometry AS (
    SELECT region_code,geometry FROM real_township_resolved
    UNION ALL
    SELECT region_code,geometry FROM owned_residual
  ), merged AS (
    SELECT region_code,
           ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_UnaryUnion(
             ST_Collect(geometry)
           )),3)) geometry
      FROM all_geometry
     GROUP BY region_code
  ), ranked_components AS (
    SELECT merged.region_code,(component).geom geometry,
           row_number() OVER(
             PARTITION BY merged.region_code
             ORDER BY ST_Area((component).geom::geography) DESC
           ) component_rank
      FROM merged
      CROSS JOIN LATERAL ST_Dump(merged.geometry) component
  )
  SELECT region_code,ST_Multi(geometry)::geometry(MultiPolygon,4326) geometry
    FROM ranked_components
   WHERE component_rank=1;

  -- Some source counties omit a large ungoverned area between otherwise real
  -- townships.  Absorbing that residual can make one real township surround
  -- another and therefore create an interior ring.  Keep every source edge,
  -- but open each such display-only ring through a sub-pixel corridor.  This is
  -- applied after residual closure because that is when these rings can arise.
  CREATE TEMP TABLE real_township_hole_corridor ON COMMIT DROP AS
  WITH host_components AS (
    SELECT preliminary.region_code host_region_code,
           host_region.parent_code,
           parent.geometry parent_geometry,
           (component).geom host_geometry
      FROM real_township_preliminary preliminary
      JOIN platform.region host_region ON host_region.code=preliminary.region_code
      JOIN overview.administrative_boundary_render parent
        ON parent.region_code=host_region.parent_code
      CROSS JOIN LATERAL ST_Dump(preliminary.geometry) component
  ), holes AS (
    SELECT host.host_region_code,host.parent_code,host.parent_geometry,
           host.host_geometry,(ring).geom hole_geometry
      FROM host_components host
      CROSS JOIN LATERAL ST_DumpRings(host.host_geometry) ring
     WHERE (ring).path[1]>0
  ), owned AS (
    SELECT hole.*,owner.region_code owner_region_code,owner.geometry owner_geometry
      FROM holes hole
      CROSS JOIN LATERAL (
        SELECT candidate.region_code,candidate.geometry
          FROM real_township_preliminary candidate
          JOIN platform.region candidate_region
            ON candidate_region.code=candidate.region_code
         WHERE candidate_region.parent_code=hole.parent_code
           AND candidate.region_code<>hole.host_region_code
           AND candidate.geometry && hole.hole_geometry
           AND ST_Area(ST_Intersection(
                 candidate.geometry,hole.hole_geometry
               )::geography)>1
         ORDER BY ST_Area(ST_Intersection(
                    candidate.geometry,hole.hole_geometry
                  )::geography) DESC,
                  candidate.region_code
         LIMIT 1
      ) owner
  )
  SELECT owner_region_code,host_region_code,
         ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Intersection(
           parent_geometry,
           ST_Buffer(
             ST_LineExtend(
               ST_ShortestLine(
                 owner_geometry,ST_ExteriorRing(host_geometry)
               ),
               0.00005,0.00005
             ),
             0.00002,'endcap=square join=mitre'
           )
         )),3)) geometry
    FROM owned;

  CREATE TEMP TABLE real_township_corridor_enlarged ON COMMIT DROP AS
  SELECT current.region_code,
         ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_UnaryUnion(ST_Collect(
           current.geometry,
           COALESCE(corridor.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
         ))),3)) geometry
    FROM real_township_preliminary current
    LEFT JOIN LATERAL (
      SELECT ST_UnaryUnion(ST_Collect(geometry)) geometry
        FROM real_township_hole_corridor
       WHERE owner_region_code=current.region_code
    ) corridor ON true;

  -- A corridor belongs exclusively to its inner real township.  Carve it from
  -- every other region it crosses, not only from the ring host; otherwise a
  -- precision sliver can survive where the route meets a third township.
  CREATE TEMP TABLE real_township_corridor_carved ON COMMIT DROP AS
  SELECT current.region_code,
         ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Difference(
           current.geometry,
           COALESCE(corridor.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
         )),3)) geometry
    FROM real_township_corridor_enlarged current
    LEFT JOIN LATERAL (
      SELECT ST_UnaryUnion(ST_Collect(geometry)) geometry
        FROM real_township_hole_corridor
       WHERE owner_region_code<>current.region_code
         AND geometry && current.geometry
    ) corridor ON true;

  CREATE TEMP TABLE real_township_final ON COMMIT DROP AS
  WITH areas AS (
    SELECT region_code,geometry,ST_Area(geometry::geography) area_m2
      FROM real_township_corridor_carved
     WHERE NOT ST_IsEmpty(geometry)
  ), disjoint AS (
    SELECT current.region_code,
           ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Difference(
             current.geometry,
             COALESCE(blocker.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
           )),3)) geometry
      FROM areas current
      LEFT JOIN LATERAL (
        SELECT ST_UnaryUnion(ST_Collect(other.geometry)) geometry
          FROM areas other
         WHERE other.region_code<>current.region_code
           AND (other.area_m2,other.region_code)<(current.area_m2,current.region_code)
           AND other.geometry && current.geometry
      ) blocker ON true
  ), ranked_components AS (
    SELECT disjoint.region_code,(component).geom geometry,
           row_number() OVER(
             PARTITION BY disjoint.region_code
             ORDER BY ST_Area((component).geom::geography) DESC
           ) component_rank
      FROM disjoint
      CROSS JOIN LATERAL ST_Dump(disjoint.geometry) component
     WHERE NOT ST_IsEmpty(disjoint.geometry)
  )
  SELECT region_code,ST_Multi(geometry)::geometry(MultiPolygon,4326) geometry
    FROM ranked_components
   WHERE component_rank=1;

  -- A narrow ring-opening corridor can detach a lobe on a precision grid.
  -- Return every residual component to the adjacent real township with the
  -- longest common border, but only when that union remains one hole-free
  -- polygon.  This closes coverage without inventing a new township layout.
  CREATE TEMP TABLE real_township_complete ON COMMIT DROP AS
  WITH parent_coverage AS (
    SELECT child.parent_code,parent.geometry parent_geometry,
           ST_UnaryUnion(ST_Collect(final.geometry)) child_geometry
      FROM real_township_final final
      JOIN platform.region child ON child.code=final.region_code
      JOIN overview.administrative_boundary_render parent
        ON parent.region_code=child.parent_code
     GROUP BY child.parent_code,parent.geometry
  ), residual AS (
    SELECT coverage.parent_code,(piece).geom geometry
      FROM parent_coverage coverage
      CROSS JOIN LATERAL ST_Dump(ST_Multi(ST_CollectionExtract(ST_MakeValid(
        ST_Difference(coverage.parent_geometry,coverage.child_geometry)
      ),3))) piece
     WHERE NOT ST_IsEmpty((piece).geom)
       AND ST_Area((piece).geom::geography)>100000
  ), owned_residual AS (
    SELECT residual.geometry,picked.region_code
      FROM residual
      CROSS JOIN LATERAL (
        SELECT candidate.region_code
          FROM real_township_final candidate
          JOIN platform.region candidate_region
            ON candidate_region.code=candidate.region_code
          CROSS JOIN LATERAL (
            SELECT ST_Multi(ST_CollectionExtract(ST_MakeValid(
              ST_UnaryUnion(ST_Collect(candidate.geometry,residual.geometry))
            ),3)) geometry
          ) merged
         WHERE candidate_region.parent_code=residual.parent_code
           AND candidate.geometry && ST_Expand(residual.geometry,0.000001)
           AND ST_DWithin(candidate.geometry,residual.geometry,0.000001)
           AND ST_NumGeometries(merged.geometry)=1
           AND ST_NumInteriorRings(ST_GeometryN(merged.geometry,1))=0
         ORDER BY ST_Length(ST_Intersection(
                    ST_Boundary(candidate.geometry),ST_Boundary(residual.geometry)
                  )::geography) DESC,
                  candidate.region_code
         LIMIT 1
      ) picked
  ), all_geometry AS (
    SELECT region_code,geometry FROM real_township_final
    UNION ALL
    SELECT region_code,geometry FROM owned_residual
  )
  SELECT region_code,
         ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_UnaryUnion(
           ST_Collect(geometry)
         )),3))::geometry(MultiPolygon,4326) geometry
    FROM all_geometry
   GROUP BY region_code;

  DROP TABLE real_township_final;
  ALTER TABLE real_township_complete RENAME TO real_township_final;

  UPDATE overview.administrative_boundary_render render
     SET geometry=final.geometry,
         geo_json=ST_AsGeoJSON(final.geometry),
         simplify_tolerance=0,
         full_point_count=GREATEST(render.full_point_count,ST_NPoints(final.geometry)),
         render_point_count=ST_NPoints(final.geometry),
         refreshed_at=now(),
         source_name=left(render.source_name || '; real boundary topology closure',160)
    FROM real_township_final final
   WHERE render.region_code=final.region_code;
END;
$$;

-- Authoritative township renderer: township polygons remain source-real.
-- Only display-invalid detached components, overlaps and interior rings are
-- removed.  In particular, parent residuals are never assigned to a township;
-- doing that made one township expand around and visually swallow its peers.
CREATE OR REPLACE FUNCTION overview.clean_real_township_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  DROP TABLE IF EXISTS pg_temp.real_township_source_cleaned;
  DROP TABLE IF EXISTS pg_temp.real_township_source_disjoint;
  DROP TABLE IF EXISTS pg_temp.real_township_source_preliminary;
  DROP TABLE IF EXISTS pg_temp.real_township_source_final;

  CREATE TEMP TABLE real_township_source_cleaned ON COMMIT DROP AS
  WITH source_components AS (
    SELECT region.code region_code,region.parent_code,
           parent.geometry parent_geometry,(component).geom geometry,
           row_number() OVER(
             PARTITION BY region.code
             ORDER BY ST_Area((component).geom::geography) DESC
           ) source_component_rank
      FROM platform.region region
      JOIN overview.administrative_boundary source
        ON source.region_code=region.code
      LEFT JOIN overview.administrative_boundary_display_reference reference
        ON reference.region_code=region.code
      JOIN overview.administrative_boundary_render refreshed
        ON refreshed.region_code=region.code
      LEFT JOIN overview.administrative_boundary_render parent
        ON parent.region_code=region.parent_code
      CROSS JOIN LATERAL ST_Dump(ST_CollectionExtract(
        ST_MakeValid(COALESCE(
          reference.geometry,
          CASE
            WHEN region.administrative_level='PREFECTURE' THEN refreshed.geometry
            ELSE source.geometry
          END
        )),3
      )) component
     WHERE region.administrative_level IN ('PREFECTURE','COUNTY','TOWNSHIP')
  ), clipped_components AS (
    SELECT source.region_code,source.parent_code,source.parent_geometry,
           (component).geom geometry
      FROM source_components source
      CROSS JOIN LATERAL ST_Dump(ST_Multi(ST_CollectionExtract(ST_MakeValid(
        CASE
          WHEN source.parent_geometry IS NULL THEN source.geometry
          ELSE ST_Intersection(source.geometry,source.parent_geometry)
        END
      ),3))) component
     WHERE source.source_component_rank=1
       AND NOT ST_IsEmpty((component).geom)
  ), ranked AS (
    SELECT clipped.*,
           row_number() OVER(
             PARTITION BY clipped.region_code
             ORDER BY ST_Area(clipped.geometry::geography) DESC
           ) clipped_component_rank
      FROM clipped_components clipped
  )
  SELECT region_code,parent_code,parent_geometry,
         ST_Multi(geometry)::geometry(MultiPolygon,4326) geometry,
         ST_Area(geometry::geography) area_m2
    FROM ranked
   WHERE clipped_component_rank=1;
  CREATE INDEX real_township_source_cleaned_geometry_gix
    ON real_township_source_cleaned USING GIST(geometry);

  -- Preserve the smaller real footprint where two source polygons overlap.
  -- This is a display-only subtraction; overview.administrative_boundary is
  -- never updated for a township.
  CREATE TEMP TABLE real_township_source_disjoint ON COMMIT DROP AS
  SELECT current.region_code,current.parent_code,current.parent_geometry,
         ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Difference(
           current.geometry,
           COALESCE(blocker.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
         )),3)) geometry
    FROM real_township_source_cleaned current
    LEFT JOIN LATERAL (
      SELECT ST_UnaryUnion(ST_Collect(other.geometry)) geometry
        FROM real_township_source_cleaned other
       WHERE other.parent_code IS NOT DISTINCT FROM current.parent_code
         AND other.region_code<>current.region_code
         AND (other.area_m2,other.region_code)<(current.area_m2,current.region_code)
         AND other.geometry && current.geometry
    ) blocker ON true;

  CREATE TEMP TABLE real_township_source_preliminary ON COMMIT DROP AS
  WITH components AS (
    SELECT source.region_code,source.parent_code,source.parent_geometry,
           (component).geom geometry,
           row_number() OVER(
             PARTITION BY source.region_code
             ORDER BY ST_Area((component).geom::geography) DESC
           ) component_rank
      FROM real_township_source_disjoint source
      CROSS JOIN LATERAL ST_Dump(source.geometry) component
     WHERE NOT ST_IsEmpty(source.geometry)
  )
  SELECT region_code,parent_code,parent_geometry,
         ST_Multi(geometry)::geometry(MultiPolygon,4326) geometry
    FROM components
   WHERE component_rank=1;

  -- Keep source holes as holes. They are filled by the passive, non-business
  -- context surface below. Cutting a hairline corridor from every hole to the
  -- exterior made those holes look like broken internal township borders and
  -- generated visible wall shards when the region was raised.
  CREATE TEMP TABLE real_township_source_final ON COMMIT DROP AS
  WITH components AS (
    SELECT source.region_code,(component).geom geometry,
           row_number() OVER(
             PARTITION BY source.region_code
             ORDER BY ST_Area((component).geom::geography) DESC
           ) component_rank
      FROM real_township_source_preliminary source
      CROSS JOIN LATERAL ST_Dump(source.geometry) component
     WHERE NOT ST_IsEmpty(source.geometry)
  )
  SELECT region_code,ST_Multi(geometry)::geometry(MultiPolygon,4326) geometry
    FROM components
   WHERE component_rank=1;

  UPDATE overview.administrative_boundary_render render
     SET geometry=final.geometry,
         geo_json=ST_AsGeoJSON(final.geometry),
         simplify_tolerance=0,
         full_point_count=GREATEST(render.full_point_count,ST_NPoints(final.geometry)),
         render_point_count=ST_NPoints(final.geometry),
         source_geometry_sha256=COALESCE(
           reference.geometry_sha256,
           CASE WHEN region.administrative_level='PREFECTURE'
             THEN render.source_geometry_sha256 ELSE source.geometry_sha256 END
         ),
         refreshed_at=now(),
         source_name=left(COALESCE(
           reference.source_name,
           CASE WHEN region.administrative_level='PREFECTURE'
             THEN render.source_name ELSE source.source_name END
         ) || '; real boundary display cleanup',160),
         source_revision=COALESCE(
           reference.source_revision,
           CASE WHEN region.administrative_level='PREFECTURE'
             THEN render.source_revision ELSE source.source_revision END
         ),
         source_license=COALESCE(
           reference.source_license,
           CASE WHEN region.administrative_level='PREFECTURE'
             THEN render.source_license ELSE source.source_license END
         )
    FROM real_township_source_final final
    JOIN platform.region region ON region.code=final.region_code
    JOIN overview.administrative_boundary source
      ON source.region_code=final.region_code
    LEFT JOIN overview.administrative_boundary_display_reference reference
      ON reference.region_code=final.region_code
   WHERE render.region_code=final.region_code;
END;
$$;

CREATE TABLE IF NOT EXISTS overview.administrative_map_context_region_render (
  region_code varchar(64) PRIMARY KEY
    REFERENCES overview.administrative_map_context_region(code) ON DELETE CASCADE,
  geometry geometry(MultiPolygon,4326) NOT NULL,
  geo_json text NOT NULL,
  source_geometry_sha256 char(64) NOT NULL,
  refreshed_at timestamptz NOT NULL DEFAULT now(),
  CHECK (ST_IsValid(geometry)),
  CHECK (NOT ST_IsEmpty(geometry)),
  CHECK (ST_SRID(geometry)=4326)
);

CREATE INDEX IF NOT EXISTS administrative_map_context_region_render_geometry_gix
  ON overview.administrative_map_context_region_render USING gist(geometry);

CREATE OR REPLACE FUNCTION overview.refresh_map_context_region_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  -- Every county gets one deterministic passive residual record. Its rendered
  -- geometry is recalculated below after real townships and named farms/forest
  -- areas are placed. It is display-only and is never part of business drill.
  INSERT INTO overview.administrative_map_context_region(
    code,name,parent_code,administrative_level,geometry,boundary_geo_json,
    source_name,source_url,source_revision,source_license,source_feature_id,
    geometry_sha256,sort_order,loaded_at
  )
  SELECT 'CTX:RESIDUAL:'||parent.code,'其他区域',parent.code,'TOWNSHIP',
         render.geometry,ST_AsGeoJSON(render.geometry),
         'Derived passive parent-gap display surface',
         'local://overview/passive-parent-gap','V46',
         'Display-only derivative; upstream administrative source licenses apply',
         'CTX:RESIDUAL:'||parent.code,
         encode(sha256(ST_AsEWKB(render.geometry)),'hex'),2147483000,now()
    FROM platform.region parent
    JOIN overview.administrative_boundary_render render
      ON render.region_code=parent.code
   WHERE parent.administrative_level='COUNTY'
  ON CONFLICT(code) DO UPDATE SET
    name=EXCLUDED.name,
    parent_code=EXCLUDED.parent_code,
    administrative_level=EXCLUDED.administrative_level,
    geometry=EXCLUDED.geometry,
    boundary_geo_json=EXCLUDED.boundary_geo_json,
    source_name=EXCLUDED.source_name,
    source_url=EXCLUDED.source_url,
    source_revision=EXCLUDED.source_revision,
    source_license=EXCLUDED.source_license,
    source_feature_id=EXCLUDED.source_feature_id,
    geometry_sha256=EXCLUDED.geometry_sha256,
    sort_order=EXCLUDED.sort_order,
    loaded_at=EXCLUDED.loaded_at;

  DELETE FROM overview.administrative_map_context_region_render;

  INSERT INTO overview.administrative_map_context_region_render(
    region_code,geometry,geo_json,source_geometry_sha256,refreshed_at
  )
  WITH governed_coverage AS (
    SELECT region.parent_code,
           ST_UnaryUnion(ST_Collect(render.geometry)) geometry
      FROM platform.region region
      JOIN overview.administrative_boundary_render render
        ON render.region_code=region.code
     WHERE region.administrative_level='TOWNSHIP'
     GROUP BY region.parent_code
  ), available AS (
    SELECT context.code region_code,
           ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Difference(
             ST_Intersection(context.geometry,parent.geometry),
             ST_UnaryUnion(ST_Collect(
               COALESCE(governed.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326)),
               COALESCE(priority.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
             ))
           )),3)) geometry
      FROM overview.administrative_map_context_region context
      JOIN overview.administrative_boundary_render parent
        ON parent.region_code=context.parent_code
      LEFT JOIN governed_coverage governed ON governed.parent_code=context.parent_code
      LEFT JOIN LATERAL (
        SELECT ST_UnaryUnion(ST_Collect(other.geometry)) geometry
          FROM overview.administrative_map_context_region other
         WHERE other.parent_code=context.parent_code
           AND (other.sort_order,other.code)<(context.sort_order,context.code)
      ) priority ON true
     WHERE context.code NOT LIKE 'CTX:RESIDUAL:%'
  ), components AS (
    SELECT available.region_code,(component).geom geometry,
           row_number() OVER(
             PARTITION BY available.region_code
             ORDER BY ST_Area((component).geom::geography) DESC
           ) component_rank
      FROM available
      CROSS JOIN LATERAL ST_Dump(available.geometry) component
     WHERE NOT ST_IsEmpty(available.geometry)
  ), cleaned AS (
    SELECT region_code,ST_Multi(geometry)::geometry(MultiPolygon,4326) geometry
      FROM components
     WHERE component_rank=1
       AND ST_Area(geometry::geography)>=1000000
  )
  SELECT region_code,geometry,ST_AsGeoJSON(geometry,7),
         encode(sha256(ST_AsEWKB(geometry)),'hex'),now()
    FROM cleaned;

  -- Fill every remaining parent pixel with one passive cap. Unlike the former
  -- residual-to-township ownership rule this never changes a real township,
  -- never becomes selectable, and therefore cannot make one town swallow its
  -- neighbours. Multipart residuals are safe because they have no outline or
  -- side wall in the client.
  INSERT INTO overview.administrative_map_context_region_render(
    region_code,geometry,geo_json,source_geometry_sha256,refreshed_at
  )
  WITH occupied AS (
    SELECT parent.code parent_code,
           ST_UnaryUnion(ST_Collect(surface.geometry)) geometry
      FROM platform.region parent
      JOIN LATERAL (
        SELECT render.geometry
          FROM platform.region child
          JOIN overview.administrative_boundary_render render
            ON render.region_code=child.code
         WHERE child.parent_code=parent.code
           AND child.administrative_level='TOWNSHIP'
        UNION ALL
        SELECT context_render.geometry
          FROM overview.administrative_map_context_region context
          JOIN overview.administrative_map_context_region_render context_render
            ON context_render.region_code=context.code
         WHERE context.parent_code=parent.code
           AND context.code NOT LIKE 'CTX:RESIDUAL:%'
      ) surface ON true
     WHERE parent.administrative_level='COUNTY'
     GROUP BY parent.code
  ), residual AS (
    SELECT 'CTX:RESIDUAL:'||parent.code region_code,
           ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Difference(
             parent_render.geometry,
             COALESCE(occupied.geometry,ST_GeomFromText('MULTIPOLYGON EMPTY',4326))
           )),3)) geometry
      FROM platform.region parent
      JOIN overview.administrative_boundary_render parent_render
        ON parent_render.region_code=parent.code
      LEFT JOIN occupied ON occupied.parent_code=parent.code
     WHERE parent.administrative_level='COUNTY'
  )
  SELECT region_code,geometry,ST_AsGeoJSON(geometry,7),
         encode(sha256(ST_AsEWKB(geometry)),'hex'),now()
    FROM residual
   WHERE NOT ST_IsEmpty(geometry);
END;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM overview.refresh_administrative_boundary_render_source();
  PERFORM overview.clean_real_township_boundary_render();
  PERFORM overview.refresh_map_context_region_render();
  PERFORM overview.repartition_display_children('VILLAGE');
END;
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');

DO $$
DECLARE
  governed_village_count integer;
  rendered_village_count integer;
  invalid_village_count integer;
  governed_township_count integer;
  rendered_township_count integer;
  invalid_township_count integer;
  township_overlap_pair_count integer;
  township_parent_gap_count integer;
  township_abnormal_expansion_count integer;
  township_encapsulation_pair_count integer;
  real_level_invalid_count integer;
  real_level_overlap_pair_count integer;
  real_level_expansion_count integer;
  real_level_encapsulation_pair_count integer;
  county_surface_gap_count integer;
  county_surface_overlap_pair_count integer;
BEGIN
  SELECT count(*) INTO governed_township_count
    FROM platform.region WHERE administrative_level='TOWNSHIP';
  SELECT count(*),count(*) FILTER(
           WHERE ST_NumGeometries(render.geometry)<>1
              OR NOT ST_IsValid(render.geometry)
              OR ST_IsEmpty(render.geometry)
         )
    INTO rendered_township_count,invalid_township_count
    FROM overview.administrative_boundary_render render
    JOIN platform.region region ON region.code=render.region_code
   WHERE region.administrative_level='TOWNSHIP';

  SELECT count(*) INTO governed_village_count
    FROM platform.region WHERE administrative_level='VILLAGE';
  SELECT count(*),count(*) FILTER(
           WHERE ST_NumGeometries(render.geometry)<>1
              OR ST_NumInteriorRings(ST_GeometryN(render.geometry,1))<>0
              OR NOT ST_IsValid(render.geometry)
              OR ST_IsEmpty(render.geometry)
         )
    INTO rendered_village_count,invalid_village_count
    FROM overview.administrative_boundary_render render
    JOIN platform.region region ON region.code=render.region_code
   WHERE region.administrative_level='VILLAGE';

  SELECT count(*) INTO township_overlap_pair_count
    FROM platform.region left_region
    JOIN overview.administrative_boundary_render left_render
      ON left_render.region_code=left_region.code
    JOIN platform.region right_region
      ON right_region.parent_code=left_region.parent_code
     AND right_region.administrative_level='TOWNSHIP'
     AND right_region.code>left_region.code
    JOIN overview.administrative_boundary_render right_render
      ON right_render.region_code=right_region.code
   WHERE left_region.administrative_level='TOWNSHIP'
     AND left_render.geometry && right_render.geometry
     AND ST_Area(ST_Intersection(
           left_render.geometry,right_render.geometry
         )::geography)>1;

  SELECT count(*) INTO township_abnormal_expansion_count
    FROM platform.region region
    JOIN overview.administrative_boundary source
      ON source.region_code=region.code
    LEFT JOIN overview.administrative_boundary_display_reference reference
      ON reference.region_code=region.code
    JOIN overview.administrative_boundary_render render
      ON render.region_code=region.code
   WHERE region.administrative_level='TOWNSHIP'
     AND ST_Area(render.geometry::geography)>
         ST_Area(COALESCE(reference.geometry,source.geometry)::geography)*1.01;

  SELECT count(*) INTO township_encapsulation_pair_count
    FROM platform.region left_region
    JOIN overview.administrative_boundary_render left_render
      ON left_render.region_code=left_region.code
    JOIN platform.region right_region
      ON right_region.parent_code=left_region.parent_code
     AND right_region.administrative_level='TOWNSHIP'
     AND right_region.code>left_region.code
    JOIN overview.administrative_boundary_render right_render
      ON right_render.region_code=right_region.code
   WHERE left_region.administrative_level='TOWNSHIP'
     AND (
       ST_Contains(
         left_render.geometry,
         ST_PointOnSurface(right_render.geometry)
       )
       OR ST_Contains(
         right_render.geometry,
         ST_PointOnSurface(left_render.geometry)
       )
     );

  WITH township_coverage AS (
    SELECT child.parent_code,ST_UnaryUnion(ST_Collect(render.geometry)) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render render
        ON render.region_code=child.code
     WHERE child.administrative_level='TOWNSHIP'
     GROUP BY child.parent_code
  )
  SELECT count(*) INTO township_parent_gap_count
    FROM township_coverage coverage
    JOIN overview.administrative_boundary_render parent
      ON parent.region_code=coverage.parent_code
   WHERE ST_Area(ST_Difference(
           parent.geometry,coverage.geometry
         )::geography)>100000;

  SELECT count(*) INTO real_level_invalid_count
    FROM platform.region region
    JOIN overview.administrative_boundary_render render
      ON render.region_code=region.code
   WHERE region.administrative_level IN ('PREFECTURE','COUNTY','TOWNSHIP')
     AND (NOT ST_IsValid(render.geometry)
       OR ST_IsEmpty(render.geometry)
       OR ST_NumGeometries(render.geometry)<>1);

  WITH surface AS (
    SELECT parent.code parent_code,parent_render.geometry parent_geometry,
           ST_UnaryUnion(ST_Collect(part.geometry)) geometry
      FROM platform.region parent
      JOIN overview.administrative_boundary_render parent_render
        ON parent_render.region_code=parent.code
      JOIN LATERAL (
        SELECT render.geometry
          FROM platform.region child
          JOIN overview.administrative_boundary_render render
            ON render.region_code=child.code
         WHERE child.parent_code=parent.code
           AND child.administrative_level='TOWNSHIP'
        UNION ALL
        SELECT context_render.geometry
          FROM overview.administrative_map_context_region context
          JOIN overview.administrative_map_context_region_render context_render
            ON context_render.region_code=context.code
         WHERE context.parent_code=parent.code
      ) part ON true
     WHERE parent.administrative_level='COUNTY'
     GROUP BY parent.code,parent_render.geometry
  )
  SELECT count(*) INTO county_surface_gap_count
    FROM surface
   WHERE ST_Area(ST_Difference(parent_geometry,geometry)::geography)>10000;

  WITH part AS (
    SELECT child.parent_code,child.code region_code,render.geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render render
        ON render.region_code=child.code
     WHERE child.administrative_level='TOWNSHIP'
    UNION ALL
    SELECT context.parent_code,context.code,context_render.geometry
      FROM overview.administrative_map_context_region context
      JOIN overview.administrative_map_context_region_render context_render
        ON context_render.region_code=context.code
  )
  SELECT count(*) INTO county_surface_overlap_pair_count
    FROM part left_part
    JOIN part right_part
      ON right_part.parent_code=left_part.parent_code
     AND right_part.region_code>left_part.region_code
   WHERE left_part.geometry && right_part.geometry
     AND ST_Area(ST_Intersection(
           left_part.geometry,right_part.geometry
         )::geography)>1;

  SELECT count(*) INTO real_level_overlap_pair_count
    FROM platform.region left_region
    JOIN overview.administrative_boundary_render left_render
      ON left_render.region_code=left_region.code
    JOIN platform.region right_region
      ON right_region.parent_code IS NOT DISTINCT FROM left_region.parent_code
     AND right_region.administrative_level=left_region.administrative_level
     AND right_region.code>left_region.code
    JOIN overview.administrative_boundary_render right_render
      ON right_render.region_code=right_region.code
   WHERE left_region.administrative_level IN ('PREFECTURE','COUNTY','TOWNSHIP')
     AND left_render.geometry && right_render.geometry
     AND ST_Area(ST_Intersection(
           left_render.geometry,right_render.geometry
         )::geography)>1;

  SELECT count(*) INTO real_level_expansion_count
    FROM platform.region region
    JOIN overview.administrative_boundary source
      ON source.region_code=region.code
    LEFT JOIN overview.administrative_boundary_display_reference reference
      ON reference.region_code=region.code
    JOIN overview.administrative_boundary_render render
      ON render.region_code=region.code
   WHERE region.administrative_level IN ('COUNTY','TOWNSHIP')
     AND ST_Area(render.geometry::geography)>
         ST_Area(COALESCE(reference.geometry,source.geometry)::geography)*1.01;

  SELECT count(*) INTO real_level_encapsulation_pair_count
    FROM platform.region left_region
    JOIN overview.administrative_boundary_render left_render
      ON left_render.region_code=left_region.code
    JOIN platform.region right_region
      ON right_region.parent_code IS NOT DISTINCT FROM left_region.parent_code
     AND right_region.administrative_level=left_region.administrative_level
     AND right_region.code>left_region.code
    JOIN overview.administrative_boundary_render right_render
      ON right_render.region_code=right_region.code
   WHERE left_region.administrative_level IN ('PREFECTURE','COUNTY','TOWNSHIP')
     AND (
       ST_Contains(
         left_render.geometry,
         ST_PointOnSurface(right_render.geometry)
       )
       OR ST_Contains(
         right_render.geometry,
         ST_PointOnSurface(left_render.geometry)
       )
     );

  IF rendered_township_count<>governed_township_count
     OR invalid_township_count<>0
     OR township_overlap_pair_count<>0
     OR township_abnormal_expansion_count<>0
     OR township_encapsulation_pair_count<>0
     OR real_level_invalid_count<>0
     OR real_level_overlap_pair_count<>0
     OR real_level_expansion_count<>0
     OR real_level_encapsulation_pair_count<>0
     OR county_surface_gap_count<>0
     OR county_surface_overlap_pair_count<>0
     OR governed_village_count NOT IN (0,2332)
     OR rendered_village_count<>governed_village_count
     OR invalid_village_count<>0 THEN
    RAISE EXCEPTION
      'Boundary display release gate failed: townships governed/rendered/invalid %/%/%, overlaps %, real-only parent gaps %, expanded %, encapsulations %, all-real-level invalid/overlap/expanded/encapsulation %/%/%/%, completed county surface gaps/overlaps %/%, villages governed/rendered/invalid %/%/%',
      governed_township_count,rendered_township_count,invalid_township_count,
      township_overlap_pair_count,township_parent_gap_count,
      township_abnormal_expansion_count,township_encapsulation_pair_count,
      real_level_invalid_count,real_level_overlap_pair_count,
      real_level_expansion_count,real_level_encapsulation_pair_count,
      county_surface_gap_count,county_surface_overlap_pair_count,
      governed_village_count,rendered_village_count,invalid_village_count;
  END IF;
END;
$$;

COMMENT ON FUNCTION overview.repartition_display_children(varchar) IS
  'Generates topology-closed, synthetic village display boundaries inside each real township outline. It rejects use for county or township boundaries.';

COMMENT ON FUNCTION overview.clean_real_township_boundary_render() IS
  'Keeps the dominant real prefecture, county and township reference component and removes display overlaps/fragments without assigning parent residuals or cutting artificial hole corridors. Canonical real geometry is never modified.';

COMMENT ON FUNCTION overview.refresh_map_context_region_render() IS
  'Builds disjoint named context surfaces and one passive residual cap per county so the full parent is covered without changing any real township boundary.';

COMMENT ON FUNCTION overview.refresh_administrative_boundary_render() IS
  'Refreshes real prefecture, county and township references, applies display-only cleanup without residual absorption, completes parent surfaces with passive context, then generates topology-closed village partitions inside real township outlines.';
