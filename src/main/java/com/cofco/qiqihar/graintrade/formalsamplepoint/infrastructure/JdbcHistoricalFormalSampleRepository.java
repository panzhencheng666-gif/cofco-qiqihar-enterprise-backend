package com.cofco.qiqihar.graintrade.formalsamplepoint.infrastructure;

import com.cofco.qiqihar.graintrade.formalsamplepoint.application.*;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.infrastructure.HistoricalSampleSql;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcHistoricalFormalSampleRepository implements HistoricalFormalSampleRepository {
    private final JdbcClient jdbc;
    public JdbcHistoricalFormalSampleRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    @Override public PagedResult<HistoricalFormalSample> findPage(String domain, String product, Integer year,
            String region, String keyword, int page, int size, Set<String> authorizedRegions) {
        var parameters = HistoricalSampleSql.parameters(year,product,region,domain,null,keyword,authorizedRegions);
        long total=jdbc.sql(HistoricalSampleSql.CTE+"SELECT count(*) FROM historical").params(parameters).query(Long.class).single();
        var rows=jdbc.sql(HistoricalSampleSql.CTE+"""
                SELECT * FROM historical ORDER BY retired_at DESC,sample_point_id
                LIMIT :limit OFFSET :offset
                """).params(parameters).param("limit",size).param("offset",Math.multiplyExact((long)page,size))
                .query((row,index)->new HistoricalFormalSample(row.getObject("sample_point_id",UUID.class),
                    row.getString("canonical_name"),row.getString("region_code"),row.getString("region_name"),
                    row.getString("address"),row.getString("type_code"),row.getString("type_name"),
                    row.getString("product_code"),row.getString("product_name"),row.getString("category_code"),
                    row.getObject("retired_at",OffsetDateTime.class),row.getInt("retirement_year"),
                    row.getString("retired_reason"),row.getString("source_record_id"),row.getObject("observed_at",OffsetDateTime.class))).list();
        return new PagedResult<>(rows,page,size,total);
    }
}
