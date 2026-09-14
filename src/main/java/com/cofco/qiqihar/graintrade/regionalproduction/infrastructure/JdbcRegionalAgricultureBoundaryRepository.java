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
                SELECT ST_Area(geometry::geography)::numeric AS area_square_metres
                FROM overview.administrative_boundary
                WHERE region_code=:regionCode
                """).param("regionCode", regionCode).query(BigDecimal.class).optional();
    }
}
