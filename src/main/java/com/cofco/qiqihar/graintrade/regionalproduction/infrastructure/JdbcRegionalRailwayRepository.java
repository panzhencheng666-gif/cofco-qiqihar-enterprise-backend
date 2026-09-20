package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalRailwayRepository;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalRailwayRoute;
import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalRailways;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRegionalRailwayRepository implements RegionalRailwayRepository {
    private final JdbcClient jdbc;
    private record CachedRailways(String revision, RegionalRailways value) {}
    private record Revision(String value, boolean cacheable) {}
    private final java.util.Map<String,CachedRailways> cache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(64,0.75f,true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,CachedRailways> eldest) {
                    return size()>64;
                }
            });
    public JdbcRegionalRailwayRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(readOnly = true)
    public RegionalRailways find(String regionCode) {
        return find(regionCode, true);
    }

    @Override
    @Transactional(readOnly = true)
    public RegionalRailways findFacilities(String regionCode) {
        return find(regionCode, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RegionalRailwayRoute> findRoutes(String regionCode) {
        return jdbc.sql("""
                WITH boundary AS (
                  SELECT geometry FROM overview.administrative_boundary WHERE region_code=:region
                ), segments AS (
                  SELECT DISTINCT ON (f.source_id)
                    f.source_id,
                    coalesce(nullif(f.name,''),nullif(f.tags->>'ref',''),'未命名铁路') AS route_name,
                    f.tags,
                    f.geometry
                  FROM overview.regional_railway_feature f CROSS JOIN boundary b
                  WHERE f.kind='rail' AND f.geometry && b.geometry AND ST_Intersects(f.geometry,b.geometry)
                  ORDER BY f.source_id
                ), grouped AS (
                  SELECT route_name,min(source_id) AS source_id,
                    coalesce(string_agg(DISTINCT tags->>'usage','、'),'') AS usage,
                    coalesce(string_agg(DISTINCT tags->>'operator','、'),'') AS operator,
                    ST_LineMerge(ST_Collect(geometry)) AS geometry
                  FROM segments GROUP BY route_name
                )
                SELECT route_name,source_id,usage,operator,
                  ST_AsGeoJSON(ST_SimplifyPreserveTopology(geometry,0.0005),6) AS geometry_geo_json
                FROM grouped
                WHERE NOT ST_IsEmpty(geometry)
                ORDER BY route_name
                """).param("region", regionCode).query((rs, n) -> new RegionalRailwayRoute(
                    regionCode + ":" + rs.getString("route_name"),
                    rs.getString("route_name"), rs.getString("geometry_geo_json"),
                    rs.getString("usage"), rs.getString("operator"),
                    "https://www.openstreetmap.org/" + rs.getString("source_id"))).list();
    }

    private RegionalRailways find(String regionCode, boolean includeLines) {
        // Read committed tuple versions, not source dates: edits/deletes with an unchanged
        // source date must invalidate results. Never reuse across writes in this transaction.
        var revision = jdbc.sql("""
                SELECT b.xmin::text || ':' || coalesce((
                  SELECT md5(string_agg(f.source_id || ':' || f.xmin::text,',' ORDER BY f.source_id))
                  FROM overview.regional_railway_feature f),'empty') AS revision,
                  pg_current_xact_id_if_assigned() IS NULL AS cacheable
                FROM overview.administrative_boundary b WHERE b.region_code=:region
                """).param("region",regionCode).query((rs,n) ->
                    new Revision(rs.getString("revision"),rs.getBoolean("cacheable"))).optional().orElse(null);
        String key=regionCode+":"+includeLines;
        if (revision != null && revision.cacheable()) {
            var found=cache.get(key);
            if (found != null && found.revision().equals(revision.value())) return found.value();
        }
        var result=load(regionCode,includeLines);
        if (revision != null && revision.cacheable()) cache.put(key,new CachedRailways(revision.value(),result));
        return result;
    }

    private RegionalRailways load(String regionCode, boolean includeLines) {
        boolean boundaryAvailable = jdbc.sql("SELECT EXISTS(SELECT 1 FROM overview.administrative_boundary WHERE region_code=:region)")
                .param("region", regionCode).query(Boolean.class).single();
        if (!boundaryAvailable) return new RegionalRailways(regionCode, false, null, List.of(), List.of());
        var sourceAsOf = jdbc.sql("SELECT max(source_as_of) AS source_as_of FROM overview.regional_railway_feature")
                .query((rs,n) -> rs.getTimestamp("source_as_of") == null ? null : rs.getTimestamp("source_as_of").toInstant().toString())
                .optional().orElse(null);
        var facilities = jdbc.sql("""
                WITH boundary AS MATERIALIZED (
                  SELECT geometry,ST_Simplify(geometry,0.0001,true)::geography AS search_geography
                  FROM overview.administrative_boundary WHERE region_code=:region
                ),
                inside AS MATERIALIZED (
                  SELECT f.source_id FROM boundary b
                  JOIN overview.regional_railway_feature f ON f.geometry && b.geometry
                    AND f.kind<>'rail' AND ST_Covers(b.geometry,f.geometry)
                ), nearby AS MATERIALIZED (
                  SELECT f.source_id,ST_Distance(f.geometry::geography,b.search_geography)/1000 AS distance_km
                  FROM boundary b JOIN overview.regional_railway_feature f
                    ON f.kind<>'rail' AND ST_DWithin(f.geometry::geography,b.search_geography,25000)
                  WHERE NOT EXISTS(SELECT 1 FROM inside i WHERE i.source_id=f.source_id)
                ), located AS (
                  SELECT source_id,true AS within_region,0::double precision AS distance_km FROM inside
                  UNION ALL SELECT source_id,false,distance_km FROM nearby
                ), ranked AS (
                  SELECT f.*,l.within_region,l.distance_km,
                    row_number() OVER (PARTITION BY within_region ORDER BY distance_km,name,f.source_id) AS proximity_rank
                  FROM located l JOIN overview.regional_railway_feature f ON f.source_id=l.source_id
                )
                SELECT f.*,ST_X(f.geometry) AS longitude,ST_Y(f.geometry) AS latitude,
                  coalesce((SELECT string_agg(DISTINCT line.name,'、' ORDER BY line.name)
                    FROM overview.regional_railway_feature line WHERE line.kind='rail'
                    AND ST_DWithin(line.geometry::geography,f.geometry::geography,150)),'') AS nearby_lines
                FROM ranked f WHERE within_region OR proximity_rank<=5
                ORDER BY within_region DESC,distance_km,name,source_id
                """).param("region",regionCode).query((rs,n) -> {
                    var tags = tools.jackson.databind.json.JsonMapper.builder().build().readTree(rs.getString("tags"));
                    boolean disused = tags.path("disused").asString().equals("yes") || tags.path("abandoned").asString().equals("yes");
                    String service = tags.path("freight").asString().equals("yes") ? "地图标注货运，实际业务待核验"
                            : tags.path("passenger").asString().equals("yes") ? "地图标注客运，实际业务待核验" : "业务范围待核验";
                    return new RegionalRailways.Facility(rs.getString("source_id"),rs.getString("name"),rs.getString("kind"),
                            rs.getBigDecimal("longitude"),rs.getBigDecimal("latitude"),tags.path("operator").asString(""),
                            tags.path("railway:ref").asString(""),disused ? "地图标注停用" : "运营情况待核验",service,
                            rs.getBoolean("within_region") ? "WITHIN" : "NEARBY",rs.getBigDecimal("distance_km"),
                            rs.getString("nearby_lines"),"https://www.openstreetmap.org/"+rs.getString("source_id"));
                }).list();
        var lines = includeLines ? jdbc.sql("""
                WITH pieces AS MATERIALIZED (
                  SELECT ST_Subdivide(ST_Simplify(geometry,0.0001,true),256) AS geometry
                  FROM overview.administrative_boundary WHERE region_code=:region
                ), fragments AS (
                  SELECT f.name,f.tags,f.source_id,ST_CollectionExtract(ST_Intersection(f.geometry,p.geometry),2) AS geometry
                  FROM pieces p JOIN overview.regional_railway_feature f
                    ON f.geometry && p.geometry AND ST_Intersects(f.geometry,p.geometry)
                  WHERE f.kind='rail'
                ), clipped AS (
                  SELECT source_id,name,tags,ST_UnaryUnion(ST_Collect(geometry)) AS geometry
                  FROM fragments GROUP BY source_id,name,tags
                )
                SELECT name,round(sum(ST_Length(geometry::geography)/1000)::numeric,3) AS track_km,
                  coalesce(string_agg(DISTINCT tags->>'usage','、'),'') AS usage,
                  coalesce(string_agg(DISTINCT tags->>'electrified','、'),'') AS electrification,
                  coalesce(string_agg(DISTINCT tags->>'gauge','、'),'') AS gauge,
                  coalesce(string_agg(DISTINCT tags->>'operator','、'),'') AS operator,min(source_id) AS source_id
                FROM clipped WHERE NOT ST_IsEmpty(geometry) GROUP BY name ORDER BY name
                """).param("region",regionCode).query((rs,n) -> new RegionalRailways.Line(
                    rs.getString("name"),rs.getBigDecimal("track_km"),rs.getString("usage"),rs.getString("electrification"),
                    rs.getString("gauge"),rs.getString("operator"),"https://www.openstreetmap.org/"+rs.getString("source_id"))).list()
                : List.<RegionalRailways.Line>of();
        return new RegionalRailways(regionCode,true,sourceAsOf,List.copyOf(facilities),List.copyOf(lines));
    }
}
