package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalAgricultureBoundaryRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegionalAgricultureBoundaryRepository implements RegionalAgricultureBoundaryRepository {
    private final JdbcClient jdbc;

    public JdbcRegionalAgricultureBoundaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigDecimal> areaSquareMetres(String regionCode) {
        return jdbc.sql("""
                WITH RECURSIVE lineage AS (
                  SELECT code,parent_code,administrative_level,0 AS depth
                  FROM platform.region WHERE code=:regionCode
                  UNION ALL
                  SELECT parent.code,parent.parent_code,parent.administrative_level,lineage.depth+1
                  FROM platform.region parent JOIN lineage ON parent.code=lineage.parent_code
                ), anchor AS MATERIALIZED (
                  SELECT lineage.code,lineage.depth,boundary.geometry
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
                SELECT (ST_Area(anchor.geometry::geography)
                        / GREATEST(1,(SELECT count(*) FROM descendants
                                     WHERE administrative_level=(SELECT administrative_level FROM requested))))::numeric
                FROM anchor
                """).param("regionCode", regionCode).query(BigDecimal.class).optional();
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
