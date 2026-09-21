package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalAgricultureBoundaryRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegionalAgricultureBoundaryRepository implements RegionalAgricultureBoundaryRepository {
    private final JdbcClient jdbc;
    private record AreaRevision(String value, boolean cacheable) {}
    private record CachedArea(String revision, BigDecimal value) {}
    private final java.util.Map<String,CachedArea> areas = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256,0.75f,true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,CachedArea> eldest) {
                    return size()>256;
                }
            });

    public JdbcRegionalAgricultureBoundaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigDecimal> areaSquareMetres(String regionCode) {
        var revision=jdbc.sql(AREA_SCOPE + """
                SELECT anchor.code||':'||anchor.version||':'||requested.administrative_level||':'||
                       (SELECT count(*) FROM descendants WHERE administrative_level=requested.administrative_level) AS revision,
                       pg_current_xact_id_if_assigned() IS NULL AS cacheable
                FROM anchor CROSS JOIN requested
                """).param("regionCode",regionCode)
                .query((rs,row) -> new AreaRevision(rs.getString("revision"),rs.getBoolean("cacheable"))).optional();
        if (revision.isEmpty()) return Optional.empty();
        var current=revision.get();
        var cached=areas.get(regionCode);
        if(current.cacheable() && cached!=null && cached.revision().equals(current.value())) return Optional.of(cached.value());
        var area=jdbc.sql(AREA_SCOPE + """
                SELECT (ST_Area(anchor.geometry::geography)
                        / GREATEST(1,(SELECT count(*) FROM descendants
                                     WHERE administrative_level=(SELECT administrative_level FROM requested))))::numeric
                FROM anchor
                """).param("regionCode", regionCode).query(BigDecimal.class).optional();
        if(current.cacheable()) area.ifPresent(value -> areas.put(regionCode,new CachedArea(current.value(),value)));
        return area;
    }

    private static final String AREA_SCOPE = """
                WITH RECURSIVE lineage AS (
                  SELECT code,parent_code,administrative_level,0 AS depth
                  FROM platform.region WHERE code=:regionCode
                  UNION ALL
                  SELECT parent.code,parent.parent_code,parent.administrative_level,lineage.depth+1
                  FROM platform.region parent JOIN lineage ON parent.code=lineage.parent_code
                ), anchor AS MATERIALIZED (
                  SELECT lineage.code,lineage.depth,boundary.geometry,boundary.xmin::text AS version
                  FROM lineage JOIN overview.administrative_boundary boundary ON boundary.region_code=lineage.code
                  ORDER BY lineage.depth LIMIT 1
                ), descendants(code,administrative_level) AS (
                  SELECT region.code,region.administrative_level
                  FROM platform.region region JOIN anchor ON region.code=anchor.code
                  UNION ALL
                  SELECT child.code,child.administrative_level
                  FROM platform.region child JOIN descendants parent ON child.parent_code=parent.code
                ), requested AS (
                  SELECT administrative_level FROM platform.region WHERE code=:regionCode
                )
                """;

    @Override
    public Optional<Allocation> allocation(String regionCode, String parentCode) {
        return jdbc.sql("""
                WITH weights AS (
                  SELECT r.code, CASE WHEN b.geometry IS NOT NULL THEN ST_Area(b.geometry::geography) END AS area
                  FROM platform.region r LEFT JOIN overview.administrative_boundary b ON b.region_code=r.code
                  WHERE r.parent_code=:parent
                ), totals AS (
                  SELECT count(*) AS n, count(area) FILTER(WHERE area>0) AS available, sum(area) AS total FROM weights
                )
                SELECT CASE WHEN n=available AND total>0 THEN (w.area/total)::numeric ELSE 1.0/n END AS share,
                       CASE WHEN n=available AND total>0
                         THEN '本级边界面积占全部同级地区边界面积合计的比例'
                         ELSE '同级地区边界尚不齐全，采用最大熵等份假设：1÷'||n||'个同级地区；这是分配权重，不是实测面积'
                       END AS basis
                FROM weights w CROSS JOIN totals WHERE w.code=:region AND n>0
                """).param("region",regionCode).param("parent",parentCode)
                .query((rs,row) -> new Allocation(rs.getBigDecimal("share"),rs.getString("basis"))).optional();
    }

    @Override
    public RegionFacts facts(String regionCode) {
        BigDecimal area = areaSquareMetres(regionCode).orElse(BigDecimal.ZERO);
        return jdbc.sql("""
                WITH RECURSIVE descendants AS (
                  SELECT code,parent_code,administrative_level FROM platform.region WHERE code=:regionCode
                  UNION ALL
                  SELECT child.code,child.parent_code,child.administrative_level
                  FROM platform.region child JOIN descendants parent ON child.parent_code=parent.code
                )
                SELECT count(*) FILTER (WHERE parent_code=:regionCode) AS direct_children,
                       count(*) FILTER (WHERE administrative_level='COUNTY') AS counties,
                       count(*) FILTER (WHERE administrative_level='TOWNSHIP') AS townships,
                       count(*) FILTER (WHERE administrative_level='VILLAGE') AS villages
                FROM descendants WHERE code<>:regionCode
                """).param("regionCode", regionCode).query((rs, row) -> new RegionFacts(
                        area,
                        rs.getInt("direct_children"),
                        rs.getInt("counties"),
                        rs.getInt("townships"),
                        rs.getInt("villages"))).single();
    }
}
