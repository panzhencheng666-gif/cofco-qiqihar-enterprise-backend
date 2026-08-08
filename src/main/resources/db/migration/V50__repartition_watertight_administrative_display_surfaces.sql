-- Render geometry is a topology-owned surface, not a second cap placed above
-- an unrelated boundary.  Keep source/reference geometries untouched, but
-- derive one hole-free, non-overlapping display partition from the same parent
-- surface whenever source geometry cannot be rendered as such a partition.

-- Do not turn numerical line slivers into new display polygons. A gap or
-- overflow wider than two metres is material and rejected; thinner remnants
-- are below one pixel at every supported overview-map zoom and have no area
-- that could create a cap, sidewall, hole, or duplicate interactive region.
CREATE OR REPLACE FUNCTION overview.has_visible_surface_gap(candidate geometry)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT COALESCE(
    ST_Distance((circle).center::geography,(circle).nearest::geography)>2,
    false
  )
    FROM (
      SELECT ST_MaximumInscribedCircle(ST_CollectionExtract(candidate,3)) circle
    ) measured;
$$;

CREATE OR REPLACE FUNCTION overview.repartition_display_children_watertight(
  requested_child_level text,
  target_parent_codes text[] DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  requested_parent_level text;
  expected_child_count integer;
  generated_child_count integer;
  assigned_region_count integer;
  assigned_piece_count integer;
  unassigned_piece_count integer;
  invalid_child_count integer;
  parent_gap_count integer;
  parent_overflow_count integer;
  sibling_overlap_count integer;
  raw_cell_gap_count integer;
  topology_debug text;
BEGIN
  requested_parent_level := CASE requested_child_level
    WHEN 'COUNTY' THEN 'PREFECTURE'
    WHEN 'TOWNSHIP' THEN 'COUNTY'
    WHEN 'VILLAGE' THEN 'TOWNSHIP'
    ELSE NULL
  END;
  IF requested_parent_level IS NULL THEN
    RAISE EXCEPTION 'Unsupported watertight display child level: %', requested_child_level;
  END IF;

  DROP TABLE IF EXISTS pg_temp.watertight_parent;
  DROP TABLE IF EXISTS pg_temp.watertight_seed;
  DROP TABLE IF EXISTS pg_temp.watertight_cell;
  DROP TABLE IF EXISTS pg_temp.watertight_piece;
  DROP TABLE IF EXISTS pg_temp.watertight_edge;
  DROP TABLE IF EXISTS pg_temp.watertight_owner;
  DROP TABLE IF EXISTS pg_temp.watertight_bridge;
  DROP TABLE IF EXISTS pg_temp.watertight_final;
  DROP TABLE IF EXISTS pg_temp.watertight_gap;
  DROP TABLE IF EXISTS pg_temp.watertight_gap_owner;
  DROP TABLE IF EXISTS pg_temp.watertight_gap_addition;

  -- A parent is rebuilt only when the supplied source/reference children cannot
  -- be shown as a single, hole-free, watertight partition. Village boundaries
  -- are explicitly display partitions, so they are rebuilt on every refresh.
  CREATE TEMP TABLE watertight_parent ON COMMIT DROP AS
  WITH grouped AS (
    SELECT parent.code parent_code,
           parent_render.geometry source_parent_geometry,
           count(child.code)::integer child_count,
           ST_UnaryUnion(ST_Collect(child_render.geometry)) child_union,
           sum(ST_Area(child_render.geometry::geography)) child_area_m2,
           bool_or(
             ST_NumGeometries(child_render.geometry)<>1
             OR ST_NumInteriorRings(ST_GeometryN(child_render.geometry,1))<>0
           ) child_surface_defect
      FROM platform.region parent
      JOIN overview.administrative_boundary_render parent_render
        ON parent_render.region_code=parent.code
      JOIN platform.region child
        ON child.parent_code=parent.code
       AND child.administrative_level=requested_child_level
     LEFT JOIN overview.administrative_boundary_render child_render
        ON child_render.region_code=child.code
     WHERE parent.administrative_level=requested_parent_level
       AND (requested_child_level='VILLAGE' OR child_render.region_code IS NOT NULL)
     GROUP BY parent.code,parent_render.geometry
  ), scored AS (
    SELECT grouped.*,
           ST_Multi(ST_MakePolygon(ST_ExteriorRing(
             ST_GeometryN(grouped.source_parent_geometry,1)
           )))::geometry(MultiPolygon,4326) geometry,
           ST_Area(ST_Difference(
             ST_MakePolygon(ST_ExteriorRing(ST_GeometryN(grouped.source_parent_geometry,1))),
             grouped.child_union
           )::geography) gap_m2,
           ST_Area(ST_Difference(
             grouped.child_union,
             ST_MakePolygon(ST_ExteriorRing(ST_GeometryN(grouped.source_parent_geometry,1)))
           )::geography) overflow_m2,
           greatest(
             grouped.child_area_m2
             - ST_Area(grouped.child_union::geography),
             0
           ) overlap_m2
      FROM grouped
  )
  SELECT parent_code,geometry,child_count
    FROM scored
   WHERE (
     target_parent_codes IS NOT NULL AND parent_code=ANY(target_parent_codes)
   ) OR (
     target_parent_codes IS NULL AND (
       requested_child_level='VILLAGE'
       OR child_surface_defect
       OR overview.has_visible_surface_gap(ST_Difference(geometry,child_union))
       OR overview.has_visible_surface_gap(ST_Difference(child_union,geometry))
       OR overlap_m2>10000
     )
   );

  -- Use source/reference points as anchors where possible. The tiny,
  -- deterministic displacement makes co-located imported points independent
  -- Voronoi sites while keeping them inside the same parent surface.
  CREATE TEMP TABLE watertight_seed ON COMMIT DROP AS
  WITH anchors AS (
    SELECT child.code region_code,child.parent_code,parent.geometry parent_geometry,
           ST_PointOnSurface(COALESCE(
             reference.geometry,child_render.geometry,location.wgs84_coordinate
           )) raw_seed
      FROM platform.region child
      JOIN watertight_parent parent ON parent.parent_code=child.parent_code
      LEFT JOIN overview.administrative_boundary_render child_render
        ON child_render.region_code=child.code
      LEFT JOIN overview.administrative_boundary_display_reference reference
        ON reference.region_code=child.code
     LEFT JOIN platform.region_location location
        ON location.region_code=child.code
     WHERE child.administrative_level=requested_child_level
       AND (requested_child_level='VILLAGE' OR child_render.region_code IS NOT NULL)
  ), clamped AS (
    SELECT anchors.*,
           CASE
             WHEN ST_Covers(parent_geometry,raw_seed) THEN raw_seed
             ELSE ST_LineInterpolatePoint(
               ST_MakeLine(
                 ST_ClosestPoint(parent_geometry,raw_seed),
                 ST_PointOnSurface(parent_geometry)
               ),0.01
             )
           END interior_seed
      FROM anchors
  ), jittered AS (
    SELECT clamped.*,
           ST_Translate(
             interior_seed,
             ((('x'||substr(md5(region_code),1,8))::bit(32)::bigint % 2001)-1000)*1e-8,
             ((('x'||substr(md5(region_code),9,8))::bit(32)::bigint % 2001)-1000)*1e-8
           ) shifted_seed
      FROM clamped
  )
  SELECT region_code,parent_code,parent_geometry,
         CASE
           WHEN ST_Covers(parent_geometry,shifted_seed) THEN shifted_seed
           ELSE ST_LineInterpolatePoint(
             ST_MakeLine(
               ST_ClosestPoint(parent_geometry,shifted_seed),
               ST_PointOnSurface(parent_geometry)
             ),0.01
           )
         END::geometry(Point,4326) seed
    FROM jittered;

  SELECT count(*) INTO expected_child_count FROM watertight_seed;
  IF expected_child_count=0 THEN
    RETURN;
  END IF;

  IF EXISTS (
    SELECT 1
      FROM watertight_seed
     GROUP BY parent_code,ST_X(seed),ST_Y(seed)
    HAVING count(*)>1
  ) THEN
    RAISE EXCEPTION 'Watertight display seed gate failed for %: duplicated source anchors remain', requested_child_level;
  END IF;

  CREATE TEMP TABLE watertight_cell ON COMMIT DROP AS
  WITH diagrams AS (
    SELECT parent.parent_code,parent.geometry parent_geometry,parent.child_count,
           ST_VoronoiPolygons(
             ST_Collect(seed.seed),0,ST_Expand(ST_Envelope(parent.geometry),0.5)
           ) geometry
      FROM watertight_parent parent
      JOIN watertight_seed seed ON seed.parent_code=parent.parent_code
     WHERE parent.child_count>1
     GROUP BY parent.parent_code,parent.geometry,parent.child_count
  ), cells AS (
    SELECT diagram.parent_code,diagram.parent_geometry,(cell).geom geometry
      FROM diagrams diagram
      CROSS JOIN LATERAL ST_Dump(diagram.geometry) cell
  ), assigned AS (
    SELECT owner.region_code,cell.parent_code,
           ST_Multi(ST_CollectionExtract(ST_Intersection(
             cell.parent_geometry,cell.geometry
           ),3))::geometry(MultiPolygon,4326) geometry
      FROM cells cell
      CROSS JOIN LATERAL (
        SELECT seed.region_code
          FROM watertight_seed seed
         WHERE seed.parent_code=cell.parent_code
         ORDER BY ST_Distance(seed.seed,ST_PointOnSurface(cell.geometry)),
                  seed.region_code
         LIMIT 1
      ) owner
  ), singletons AS (
    SELECT seed.region_code,seed.parent_code,seed.parent_geometry geometry
      FROM watertight_seed seed
      JOIN watertight_parent parent ON parent.parent_code=seed.parent_code
     WHERE parent.child_count=1
  )
  SELECT * FROM assigned
  UNION ALL
  SELECT * FROM singletons;

  SELECT count(*) INTO generated_child_count FROM watertight_cell;
  IF generated_child_count<>expected_child_count THEN
    RAISE EXCEPTION
      'Watertight display cell gate failed for %: expected %, generated %',
      requested_child_level,expected_child_count,generated_child_count;
  END IF;

  SELECT count(DISTINCT region_code) INTO assigned_region_count
    FROM watertight_cell;
  IF assigned_region_count<>expected_child_count THEN
    RAISE EXCEPTION
      'Watertight display ownership gate failed for %: expected %, assigned %',
      requested_child_level,expected_child_count,assigned_region_count;
  END IF;

  WITH coverage AS (
    SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
      FROM watertight_cell
     GROUP BY parent_code
  )
  SELECT count(*) INTO raw_cell_gap_count
    FROM coverage
    JOIN watertight_parent parent USING(parent_code)
   WHERE overview.has_visible_surface_gap(ST_Difference(
           parent.geometry,coverage.geometry
         ));
  IF raw_cell_gap_count<>0 THEN
    SELECT string_agg(
             parent.parent_code || ':raw-gap=' || round(ST_Area(ST_Difference(
               parent.geometry,coverage.geometry
             )::geography)::numeric,2),
             ', ' ORDER BY parent.parent_code
           )
      INTO topology_debug
      FROM (
        SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
          FROM watertight_cell GROUP BY parent_code
      ) coverage
      JOIN watertight_parent parent USING(parent_code)
     WHERE overview.has_visible_surface_gap(ST_Difference(
             parent.geometry,coverage.geometry
           ));
    RAISE EXCEPTION
      'Watertight display raw-cell coverage gate failed for %: % parent(s) [%]',
      requested_child_level,raw_cell_gap_count,COALESCE(topology_debug,'none');
  END IF;

  -- A Voronoi cell clipped by a strongly concave real parent can have a
  -- detached lobe. Treat that lobe as a separate planar piece and give it to
  -- the nearest edge-connected owner, rather than publishing one region as a
  -- multi-part flying island.
  CREATE TEMP TABLE watertight_piece ON COMMIT DROP AS
  SELECT row_number() OVER()::bigint piece_id,
         cell.region_code candidate_region_code,cell.parent_code,
         (component).geom::geometry(Polygon,4326) geometry,
         ST_Area((component).geom::geography) area_m2
    FROM watertight_cell cell
    CROSS JOIN LATERAL ST_Dump(cell.geometry) component
   WHERE NOT ST_IsEmpty((component).geom)
     AND ST_Area((component).geom::geography)>0.01;
  CREATE UNIQUE INDEX watertight_piece_pkey ON watertight_piece(piece_id);
  CREATE INDEX watertight_piece_geometry_gix ON watertight_piece USING GIST(geometry);
  CREATE INDEX watertight_piece_parent_idx ON watertight_piece(parent_code);

  CREATE TEMP TABLE watertight_edge ON COMMIT DROP AS
  SELECT left_piece.piece_id left_piece_id,right_piece.piece_id right_piece_id,
         ST_Length(ST_Intersection(
           ST_Boundary(left_piece.geometry),ST_Boundary(right_piece.geometry)
         )::geography) shared_border_m
    FROM watertight_piece left_piece
    JOIN watertight_piece right_piece
      ON right_piece.parent_code=left_piece.parent_code
     AND right_piece.piece_id>left_piece.piece_id
     AND left_piece.geometry && right_piece.geometry
   WHERE ST_Length(ST_Intersection(
           ST_Boundary(left_piece.geometry),ST_Boundary(right_piece.geometry)
         )::geography)>0.001;
  CREATE INDEX watertight_edge_left_idx ON watertight_edge(left_piece_id);
  CREATE INDEX watertight_edge_right_idx ON watertight_edge(right_piece_id);

  CREATE TEMP TABLE watertight_owner(
    piece_id bigint PRIMARY KEY,
    owner_region_code varchar(12) NOT NULL,
    graph_distance integer NOT NULL
  ) ON COMMIT DROP;
  INSERT INTO watertight_owner(piece_id,owner_region_code,graph_distance)
  SELECT DISTINCT ON(seed.region_code)
         piece.piece_id,seed.region_code,0
    FROM watertight_seed seed
    JOIN watertight_piece piece
      ON piece.parent_code=seed.parent_code
     AND piece.candidate_region_code=seed.region_code
   ORDER BY seed.region_code,
            ST_Covers(piece.geometry,seed.seed) DESC,
            piece.area_m2 DESC,piece.piece_id;

  IF (SELECT count(*) FROM watertight_owner)<>expected_child_count THEN
    RAISE EXCEPTION
      'Watertight display primary-piece gate failed for %: expected %, seeded %',
      requested_child_level,expected_child_count,(SELECT count(*) FROM watertight_owner);
  END IF;

  LOOP
    WITH candidates AS (
      SELECT DISTINCT ON(target.piece_id)
             target.piece_id,owned.owner_region_code,
             owned.graph_distance+1 graph_distance
        FROM watertight_piece target
        JOIN watertight_edge edge
          ON edge.left_piece_id=target.piece_id
          OR edge.right_piece_id=target.piece_id
        JOIN watertight_owner owned
          ON owned.piece_id=CASE
            WHEN edge.left_piece_id=target.piece_id THEN edge.right_piece_id
            ELSE edge.left_piece_id END
       WHERE NOT EXISTS(
         SELECT 1 FROM watertight_owner current
          WHERE current.piece_id=target.piece_id
       )
       ORDER BY target.piece_id,
                (target.candidate_region_code=owned.owner_region_code) DESC,
                edge.shared_border_m DESC,owned.graph_distance,
                owned.owner_region_code
    )
    INSERT INTO watertight_owner(piece_id,owner_region_code,graph_distance)
    SELECT piece_id,owner_region_code,graph_distance FROM candidates
    ON CONFLICT(piece_id) DO NOTHING;
    GET DIAGNOSTICS assigned_piece_count=ROW_COUNT;
    EXIT WHEN assigned_piece_count=0;
  END LOOP;

  -- A few overlay remnants meet the partition only at a point. Give these
  -- precision pieces a sub-centimetre bridge to their nearest connected owner
  -- before publishing. The bridge is clipped to the same parent and is far
  -- below one display pixel, so it cannot become a visible artificial strip.
  CREATE TEMP TABLE watertight_bridge ON COMMIT DROP AS
  SELECT piece.piece_id,nearest.owner_region_code,
         ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_Intersection(
           parent.geometry,
           ST_Buffer(ST_ShortestLine(piece.geometry,nearest.geometry),0.0000001,
             'endcap=square join=mitre')
         )),3))::geometry(MultiPolygon,4326) geometry
    FROM watertight_piece piece
    JOIN watertight_parent parent ON parent.parent_code=piece.parent_code
    CROSS JOIN LATERAL (
      SELECT owner.owner_region_code,candidate.geometry
        FROM watertight_owner owner
        JOIN watertight_piece candidate ON candidate.piece_id=owner.piece_id
       WHERE candidate.parent_code=piece.parent_code
       ORDER BY ST_Distance(candidate.geometry,piece.geometry),candidate.piece_id
       LIMIT 1
    ) nearest
   WHERE NOT EXISTS(
     SELECT 1 FROM watertight_owner owner WHERE owner.piece_id=piece.piece_id
   );

  INSERT INTO watertight_owner(piece_id,owner_region_code,graph_distance)
  SELECT piece_id,owner_region_code,1 FROM watertight_bridge
  ON CONFLICT(piece_id) DO NOTHING;

  SELECT count(*) INTO unassigned_piece_count
    FROM watertight_piece piece
   WHERE NOT EXISTS(
     SELECT 1 FROM watertight_owner owner WHERE owner.piece_id=piece.piece_id
   );
  IF unassigned_piece_count<>0 THEN
    RAISE EXCEPTION
      'Watertight display connectivity gate failed for %: % unowned pieces',
      requested_child_level,unassigned_piece_count;
  END IF;

  CREATE TEMP TABLE watertight_final ON COMMIT DROP AS
  WITH all_parts AS (
    SELECT owner.owner_region_code,piece.parent_code,piece.geometry
      FROM watertight_owner owner
      JOIN watertight_piece piece ON piece.piece_id=owner.piece_id
    UNION ALL
    SELECT bridge.owner_region_code,piece.parent_code,bridge.geometry
      FROM watertight_bridge bridge
      JOIN watertight_piece piece ON piece.piece_id=bridge.piece_id
     WHERE NOT ST_IsEmpty(bridge.geometry)
  )
  SELECT owner_region_code region_code,parent_code,
         ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_UnaryUnion(
           ST_Collect(geometry)
         )),3))::geometry(MultiPolygon,4326) geometry
    FROM all_parts
   GROUP BY owner_region_code,parent_code;

  -- Never publish a sub-pixel detached fleck as an interactive region. The
  -- dropped total is bounded below one thousand square metres and is still
  -- inside the parent-partition tolerance; any material second component keeps
  -- the release gate red below.
  WITH ranked AS (
    SELECT final.region_code,final.parent_code,final.geometry,
           (part).geom component,
           row_number() OVER(
             PARTITION BY final.region_code
             ORDER BY ST_Area((part).geom::geography) DESC
           ) component_rank,
           ST_Area(final.geometry::geography)
             - max(ST_Area((part).geom::geography)) OVER(
               PARTITION BY final.region_code
             ) discarded_area_m2
      FROM watertight_final final
      CROSS JOIN LATERAL ST_Dump(final.geometry) part
  ), largest AS (
    SELECT region_code,parent_code,
           ST_Multi(component)::geometry(MultiPolygon,4326) geometry
      FROM ranked
     WHERE component_rank=1 AND discarded_area_m2<1000
  )
  UPDATE watertight_final final
     SET geometry=largest.geometry
    FROM largest
   WHERE final.region_code=largest.region_code
     AND final.parent_code=largest.parent_code
     AND ST_NumGeometries(final.geometry)>1;

  SELECT count(*) INTO invalid_child_count
    FROM watertight_final
   WHERE NOT ST_IsValid(geometry)
      OR ST_IsEmpty(geometry)
      OR ST_NumGeometries(geometry)<>1
      OR ST_NumInteriorRings(ST_GeometryN(geometry,1))<>0;

  WITH coverage AS (
    SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
      FROM watertight_final
     GROUP BY parent_code
  )
  SELECT count(*) INTO parent_gap_count
    FROM coverage
    JOIN watertight_parent parent USING(parent_code)
   WHERE overview.has_visible_surface_gap(ST_Difference(
           parent.geometry,coverage.geometry
         ));

  WITH coverage AS (
    SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
      FROM watertight_final
     GROUP BY parent_code
  )
  SELECT count(*) INTO parent_overflow_count
    FROM coverage
    JOIN watertight_parent parent USING(parent_code)
   WHERE overview.has_visible_surface_gap(ST_Difference(
           coverage.geometry,parent.geometry
         ));

  SELECT count(*) INTO sibling_overlap_count
    FROM watertight_final left_child
    JOIN watertight_final right_child
      ON right_child.parent_code=left_child.parent_code
     AND right_child.region_code>left_child.region_code
     AND left_child.geometry && right_child.geometry
   WHERE overview.has_visible_surface_gap(ST_Intersection(
           left_child.geometry,right_child.geometry
         ));

  IF invalid_child_count<>0 OR parent_gap_count<>0
     OR parent_overflow_count<>0 OR sibling_overlap_count<>0 THEN
    SELECT string_agg(detail,', ' ORDER BY detail)
      INTO topology_debug
      FROM (
        SELECT region_code || ':parts=' || ST_NumGeometries(geometry)
               || ':holes=' || ST_NumInteriorRings(ST_GeometryN(geometry,1))
               || ':smallest=' || round((
                    SELECT min(ST_Area((part).geom::geography))
                      FROM ST_Dump(geometry) part
                  )::numeric,2) detail
          FROM watertight_final
         WHERE NOT ST_IsValid(geometry)
            OR ST_IsEmpty(geometry)
            OR ST_NumGeometries(geometry)<>1
            OR ST_NumInteriorRings(ST_GeometryN(geometry,1))<>0
        UNION ALL
        SELECT parent.parent_code || ':gap=' || round(ST_Area(ST_Difference(
                 parent.geometry,coverage.geometry
               )::geography)::numeric,2)
          FROM (
            SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
              FROM watertight_final GROUP BY parent_code
          ) coverage
          JOIN watertight_parent parent USING(parent_code)
         WHERE overview.has_visible_surface_gap(ST_Difference(
                 parent.geometry,coverage.geometry
               ))
        UNION ALL
        SELECT parent.parent_code || ':overflow=' || round(ST_Area(ST_Difference(
                 coverage.geometry,parent.geometry
               )::geography)::numeric,2)
          FROM (
            SELECT parent_code,ST_UnaryUnion(ST_Collect(geometry)) geometry
              FROM watertight_final GROUP BY parent_code
          ) coverage
          JOIN watertight_parent parent USING(parent_code)
         WHERE overview.has_visible_surface_gap(ST_Difference(
                 coverage.geometry,parent.geometry
               ))
        UNION ALL
        SELECT left_child.parent_code || ':overlap=' || left_child.region_code
               || '/' || right_child.region_code || ':' || round(ST_Area(ST_Intersection(
                 left_child.geometry,right_child.geometry
               )::geography)::numeric,2)
          FROM watertight_final left_child
          JOIN watertight_final right_child
            ON right_child.parent_code=left_child.parent_code
           AND right_child.region_code>left_child.region_code
           AND left_child.geometry && right_child.geometry
         WHERE overview.has_visible_surface_gap(ST_Intersection(
                 left_child.geometry,right_child.geometry
               ))
      ) debug;
    RAISE EXCEPTION
      'Watertight display topology gate failed for %: invalid % [%], gaps %, overflow %, overlaps %',
      requested_child_level,invalid_child_count,COALESCE(topology_debug,'none'),
      parent_gap_count,parent_overflow_count,sibling_overlap_count;
  END IF;

  -- Villages are allowed to be display-generated.  A newly imported village
  -- may therefore have a governed point but no raw polygon yet; materialize
  -- its generated surface first so the render table's source-boundary foreign
  -- key remains valid and it is immediately clickable.
  INSERT INTO overview.administrative_boundary(
    region_code,geometry,source_name,source_url,source_revision,source_license,
    geometry_sha256
  )
  SELECT final.region_code,final.geometry,
         'System-generated topology-closed display partition',
         'urn:qiqihar:display-partition','v50-2026-08-05',
         'Internal display-only generated geometry',
         encode(sha256(ST_AsEWKB(final.geometry)),'hex')
    FROM watertight_final final
    JOIN platform.region region ON region.code=final.region_code
    LEFT JOIN overview.administrative_boundary boundary
      ON boundary.region_code=final.region_code
   WHERE requested_child_level='VILLAGE'
     AND boundary.region_code IS NULL
     AND region.administrative_level='VILLAGE'
  ON CONFLICT(region_code) DO NOTHING;

  INSERT INTO overview.administrative_boundary_render(
    region_code,geometry,geo_json,simplify_tolerance,full_point_count,
    render_point_count,source_geometry_sha256,source_name,source_revision,
    source_license
  )
  SELECT final.region_code,final.geometry,ST_AsGeoJSON(final.geometry,7),0,
         ST_NPoints(final.geometry),ST_NPoints(final.geometry),
         encode(sha256(ST_AsEWKB(final.geometry)),'hex'),
         'System-generated topology-closed display partition','v50-2026-08-05',
         'Internal display-only generated geometry'
    FROM watertight_final final
    LEFT JOIN overview.administrative_boundary_render render
      ON render.region_code=final.region_code
   WHERE render.region_code IS NULL
  ON CONFLICT(region_code) DO NOTHING;

  UPDATE overview.administrative_boundary_render render
     SET geometry=final.geometry,
         geo_json=ST_AsGeoJSON(final.geometry,7),
         simplify_tolerance=0,
         full_point_count=GREATEST(render.full_point_count,ST_NPoints(final.geometry)),
         render_point_count=ST_NPoints(final.geometry),
         source_geometry_sha256=CASE
           WHEN requested_child_level='VILLAGE'
             THEN encode(sha256(ST_AsEWKB(final.geometry)),'hex')
           ELSE COALESCE(reference.geometry_sha256,render.source_geometry_sha256)
         END,
         refreshed_at=now(),
         source_name=CASE
           WHEN requested_child_level='VILLAGE'
             THEN 'System-generated topology-closed display partition'
           ELSE left(COALESCE(reference.source_name,render.source_name)
             || '; watertight display partition',160)
         END,
         source_revision=CASE
           WHEN requested_child_level='VILLAGE' THEN 'v50-2026-08-05'
           ELSE COALESCE(reference.source_revision,render.source_revision)
         END,
         source_license=CASE
           WHEN requested_child_level='VILLAGE'
             THEN 'Internal display-only generated geometry'
           ELSE COALESCE(reference.source_license,render.source_license)
         END
    FROM watertight_final final
    LEFT JOIN overview.administrative_boundary_display_reference reference
      ON reference.region_code=final.region_code
   WHERE render.region_code=final.region_code;
END;
$$;

CREATE OR REPLACE FUNCTION overview.assert_watertight_administrative_render()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  invalid_surface_count integer;
  containment_error_count integer;
  parent_partition_error_count integer;
  sibling_overlap_count integer;
BEGIN
  SELECT count(*) INTO invalid_surface_count
    FROM overview.administrative_boundary_render render
   WHERE NOT ST_IsValid(render.geometry)
      OR ST_IsEmpty(render.geometry)
      OR ST_NumGeometries(render.geometry)<>1
      OR ST_NumInteriorRings(ST_GeometryN(render.geometry,1))<>0;

  SELECT count(*) INTO containment_error_count
    FROM platform.region child
    JOIN overview.administrative_boundary_render child_render
      ON child_render.region_code=child.code
    JOIN overview.administrative_boundary_render parent_render
      ON parent_render.region_code=child.parent_code
   WHERE child.administrative_level IN ('COUNTY','TOWNSHIP','VILLAGE')
     AND overview.has_visible_surface_gap(ST_Difference(
           child_render.geometry,parent_render.geometry
         ));

  WITH coverage AS (
    SELECT child.parent_code,count(*) rendered_child_count,
           ST_UnaryUnion(ST_Collect(render.geometry)) geometry
      FROM platform.region child
      JOIN overview.administrative_boundary_render render
        ON render.region_code=child.code
     WHERE child.administrative_level IN ('COUNTY','TOWNSHIP','VILLAGE')
     GROUP BY child.parent_code
  )
  SELECT count(*) INTO parent_partition_error_count
    FROM coverage
    JOIN overview.administrative_boundary_render parent
      ON parent.region_code=coverage.parent_code
    CROSS JOIN LATERAL (
      SELECT count(*) expected_child_count
        FROM platform.region child
       WHERE child.parent_code=coverage.parent_code
         AND child.administrative_level IN ('COUNTY','TOWNSHIP','VILLAGE')
    ) expected
   WHERE coverage.rendered_child_count=expected.expected_child_count
     AND (
       overview.has_visible_surface_gap(ST_Difference(parent.geometry,coverage.geometry))
       OR overview.has_visible_surface_gap(ST_Difference(coverage.geometry,parent.geometry))
     );

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
   WHERE left_region.administrative_level IN ('COUNTY','TOWNSHIP','VILLAGE')
     AND left_render.geometry && right_render.geometry
     AND overview.has_visible_surface_gap(ST_Intersection(
           left_render.geometry,right_render.geometry
         ));

  IF invalid_surface_count<>0 OR containment_error_count<>0
     OR parent_partition_error_count<>0 OR sibling_overlap_count<>0 THEN
    RAISE EXCEPTION
      'Administrative render integrity gate failed: invalid/holey %, containment %, parent partition %, sibling overlap %',
      invalid_surface_count,containment_error_count,parent_partition_error_count,
      sibling_overlap_count;
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION overview.refresh_administrative_boundary_render()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM overview.refresh_administrative_boundary_render_source();
  PERFORM overview.close_root_prefecture_gaps('FORMAL_BUSINESS');
  PERFORM overview.repartition_display_children_watertight('COUNTY');
  PERFORM overview.repartition_display_children_watertight('TOWNSHIP');
  PERFORM overview.repartition_display_children_watertight('VILLAGE');
  PERFORM overview.restore_administrative_boundary_render_provenance();
  TRUNCATE overview.administrative_map_context_region_render;
  PERFORM overview.assert_watertight_administrative_render();
END;
$$;

SELECT overview.refresh_administrative_boundary_render();
SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS');
