package com.cofco.qiqihar.graintrade.regionalproduction.infrastructure;

import com.cofco.qiqihar.graintrade.regionalproduction.application.*;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcRegionalEstimateBatchRepository implements RegionalEstimateBatchRepository {
    private final JdbcClient jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public JdbcRegionalEstimateBatchRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public Optional<RegionalEstimateBatch> latest(String root, int year) {
        return jdbc.sql("SELECT payload::text FROM production.regional_estimate_batch WHERE root_region_code=:root AND data_year=:year ORDER BY attempted_at DESC LIMIT 1")
                .param("root",root).param("year",year).query(String.class).optional()
                .map(json -> mapper.readValue(json, RegionalEstimateBatch.class));
    }
    @Override public Optional<String> latestHash(String root, int year) {
        return jdbc.sql("SELECT input_hash FROM production.regional_estimate_batch WHERE root_region_code=:root AND data_year=:year ORDER BY attempted_at DESC LIMIT 1")
                .param("root",root).param("year",year).query(String.class).optional();
    }
    @Override public void save(RegionalEstimateBatch batch, String hash) {
        jdbc.sql("""
            INSERT INTO production.regional_estimate_batch(root_region_code,data_year,batch_day,calculated_at,attempted_at,input_hash,payload)
            VALUES(:root,:year,CAST(:attempt AS timestamptz) AT TIME ZONE 'Asia/Shanghai',CAST(:at AS timestamptz),CAST(:attempt AS timestamptz),:hash,CAST(:payload AS jsonb))
            ON CONFLICT(root_region_code,data_year,batch_day) DO UPDATE SET calculated_at=EXCLUDED.calculated_at,attempted_at=EXCLUDED.attempted_at,
                input_hash=EXCLUDED.input_hash,payload=EXCLUDED.payload
            """).param("root",batch.rootRegionCode()).param("year",batch.year()).param("at",batch.calculatedAt())
                .param("attempt",batch.attemptedAt()).param("hash",hash).param("payload",mapper.writeValueAsString(batch)).update();
    }
}
