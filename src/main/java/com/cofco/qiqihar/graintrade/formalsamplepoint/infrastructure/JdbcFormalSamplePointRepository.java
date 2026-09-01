package com.cofco.qiqihar.graintrade.formalsamplepoint.infrastructure;

import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointRepository;
import com.cofco.qiqihar.graintrade.formalsamplepoint.application.FormalSamplePointView;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFormalSamplePointRepository implements FormalSamplePointRepository {
    private static final String SELECT_VIEW = """
            SELECT point.sample_point_id,point.kind_code,point.canonical_name,
                   point.region_code,point.approval_state,point.location_state,
                   ST_X(point.governed_point) longitude,
                   ST_Y(point.governed_point) latitude,
                   point.effective_from,point.effective_to,point.version,
                   ((SELECT count(*) FROM production.production_record record
                       WHERE record.sample_point_id=point.sample_point_id)
                    +(SELECT count(*) FROM market.market_record record
                       WHERE record.sample_point_id=point.sample_point_id)
                    +(SELECT count(*) FROM logistics.route_event event
                       WHERE event.sample_point_id=point.sample_point_id)) annual_observation_count,
                   (SELECT count(*) FROM registry.sample_network_membership membership
                       WHERE membership.sample_point_id=point.sample_point_id) network_membership_count
            FROM registry.sample_point point
            """;
    private final JdbcClient jdbc;

    public JdbcFormalSamplePointRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public PagedResult<FormalSamplePointView> findPage(
            String regionCode, String keyword, int pageNumber, int pageSize,
            Set<String> authorizedRegionCodes) {
        Filter filter = filter(regionCode, keyword, authorizedRegionCodes);
        long total = jdbc.sql("SELECT count(*) FROM registry.sample_point point " + filter.sql())
                .params(filter.parameters()).query(Long.class).single();
        long offset = Math.multiplyExact((long) pageNumber, pageSize);
        var items = jdbc.sql(SELECT_VIEW + filter.sql() + """
                         ORDER BY point.canonical_name,point.sample_point_id
                        LIMIT :limit OFFSET :offset
                        """).params(filter.parameters())
                .param("limit", pageSize).param("offset", offset)
                .query((row, ignored) -> view(row)).list();
        return new PagedResult<>(items, pageNumber, pageSize, total);
    }

    @Override
    public Optional<FormalSamplePointView> find(UUID id) {
        return jdbc.sql(SELECT_VIEW + """
                        WHERE point.kind_code='SURVEY_SITE'
                          AND point.sample_point_id=:id
                        """).param("id", id).query((row, ignored) -> view(row)).optional();
    }

    @Override
    public DeleteResult delete(
            UUID id, long expectedVersion, String expectedRegionCode, String actorSubjectId) {
        String result = jdbc.sql("""
                SELECT registry.delete_formal_sample_point(:id,:version,:region,:actor)
                """).param("id", id).param("version", expectedVersion)
                .param("region", expectedRegionCode)
                .param("actor", actorSubjectId)
                .query(String.class).single();
        return DeleteResult.valueOf(result);
    }

    private static Filter filter(
            String regionCode, String keyword, Set<String> authorizedRegionCodes) {
        StringBuilder sql = new StringBuilder(" WHERE point.kind_code='SURVEY_SITE'");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (!authorizedRegionCodes.contains("*")) {
            if (authorizedRegionCodes.isEmpty()) {
                sql.append(" AND 1=0");
            } else {
                sql.append(" AND point.region_code IN (:authorizedRegions)");
                parameters.put("authorizedRegions", authorizedRegionCodes);
            }
        }
        if (regionCode != null) {
            sql.append(" AND point.region_code=:regionCode");
            parameters.put("regionCode", regionCode);
        }
        if (keyword != null) {
            sql.append(" AND point.canonical_name ILIKE :keyword ESCAPE E'\\\\'");
            parameters.put("keyword", "%" + escape(keyword) + "%");
        }
        return new Filter(sql.toString(), parameters);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static FormalSamplePointView view(java.sql.ResultSet row)
            throws java.sql.SQLException {
        return new FormalSamplePointView(
                row.getObject("sample_point_id", UUID.class),
                row.getString("kind_code"), row.getString("canonical_name"),
                row.getString("region_code"), row.getString("approval_state"),
                row.getString("location_state"), row.getBigDecimal("longitude"),
                row.getBigDecimal("latitude"),
                row.getObject("effective_from", LocalDate.class),
                row.getObject("effective_to", LocalDate.class),
                row.getLong("version"), row.getLong("annual_observation_count"),
                row.getLong("network_membership_count"));
    }

    private record Filter(String sql, Map<String, Object> parameters) {}
}
