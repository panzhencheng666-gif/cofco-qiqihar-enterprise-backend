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
        boolean boundaryAvailable = jdbc.sql("SELECT EXISTS(SELECT 1 FROM overview.administrative_boundary WHERE region_code=:region)")
                .param("region", regionCode).query(Boolean.class).single();
        if (!boundaryAvailable) return new RegionalRailways(regionCode, false, null, List.of(), List.of());
        var sourceAsOf = jdbc.sql("SELECT max(source_as_of) AS source_as_of FROM overview.regional_railway_feature")
                .query((rs,n) -> rs.getTimestamp("source_as_of") == null ? null : rs.getTimestamp("source_as_of").toInstant().toString())
                .optional().orElse(null);
        var facilities = jdbc.sql("""
                WITH boundary AS (SELECT geometry FROM overview.administrative_boundary WHERE region_code=:region),
                candidates AS (
                  SELECT f.*,ST_Covers(b.geometry,f.geometry) AS within_region,
                    ST_Distance(f.geometry::geography,b.geometry::geography)/1000 AS distance_km
                  FROM overview.regional_railway_feature f CROSS JOIN boundary b
                  WHERE f.kind<>'rail' AND ST_DWithin(f.geometry::geography,b.geometry::geography,25000)
                ), ranked AS (
                  SELECT *,row_number() OVER (PARTITION BY within_region ORDER BY distance_km,name,source_id) AS proximity_rank
                  FROM candidates
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
                WITH clipped AS (
                  SELECT f.name,f.tags,f.source_id,ST_CollectionExtract(ST_Intersection(f.geometry,b.geometry),2) AS geometry
                  FROM overview.regional_railway_feature f
                  JOIN overview.administrative_boundary b ON b.region_code=:region AND ST_Intersects(f.geometry,b.geometry)
                  WHERE f.kind='rail'
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
        return new RegionalRailways(regionCode,true,sourceAsOf,facilities,lines);
    }
}
