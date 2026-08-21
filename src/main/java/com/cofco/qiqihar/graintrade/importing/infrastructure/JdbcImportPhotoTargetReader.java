package com.cofco.qiqihar.graintrade.importing.infrastructure;

import com.cofco.qiqihar.graintrade.importing.application.ImportPhotoTargetReader;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcImportPhotoTargetReader implements ImportPhotoTargetReader {
    private final JdbcClient jdbc;

    public JdbcImportPhotoTargetReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Target> productionTargets(UUID importJobId) {
        return jdbc.sql("""
                SELECT row_result.row_number,row_result.business_record_id,record.region_code
                FROM platform.import_row_result row_result
                JOIN production.production_record record
                  ON record.record_id=row_result.business_record_id
                WHERE row_result.import_job_id=:importJobId
                  AND row_result.outcome_code='IMPORTED'
                  AND row_result.business_record_id IS NOT NULL
                ORDER BY row_result.row_number
                """).param("importJobId", importJobId)
                .query((row, index) -> new Target(row.getInt("row_number"),
                        row.getString("business_record_id"), row.getString("region_code")))
                .list();
    }
}
