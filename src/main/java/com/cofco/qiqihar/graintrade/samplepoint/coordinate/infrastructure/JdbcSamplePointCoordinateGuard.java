package com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure;

import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateGuard;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateKey;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSamplePointCoordinateGuard implements SamplePointCoordinateGuard {
    private final JdbcClient jdbc;

    public JdbcSamplePointCoordinateGuard(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public void lockAndRequireAvailable(
            UUID allowedSamplePointId, BigDecimal longitude, BigDecimal latitude) {
        SamplePointCoordinateKey key = SamplePointCoordinateKey.of(longitude, latitude);
        lock(key);
        if (!occupants(allowedSamplePointId, key).isEmpty()) {
            throw new ConflictException(
                    "SAMPLE_POINT_COORDINATE_OCCUPIED",
                    "该经纬度已被其他样本点使用，请核对真实坐标");
        }
    }

    @Override
    public void lockAndRequireReviewedSharing(
            UUID allowedSamplePointId, BigDecimal longitude, BigDecimal latitude,
            Set<UUID> reviewedOccupantIds) {
        SamplePointCoordinateKey key = SamplePointCoordinateKey.of(longitude, latitude);
        lock(key);
        Set<UUID> reviewed = Set.copyOf(
                reviewedOccupantIds == null ? Set.of() : reviewedOccupantIds);
        Set<UUID> current = Set.copyOf(occupants(allowedSamplePointId, key));
        if (reviewed.isEmpty() || !current.equals(reviewed)) {
            throw new ConflictException(
                    "SAMPLE_POINT_COORDINATE_REVIEW_STALE",
                    "该坐标的占用情况已变化，请重新核验后再审核");
        }
    }

    private void lock(SamplePointCoordinateKey key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "SAMPLE_POINT_COORDINATE:" + key.lockKey())
                .query((row, index) -> Boolean.TRUE).single();
    }

    private List<UUID> occupants(UUID allowedSamplePointId, SamplePointCoordinateKey key) {
        String allowedClause = allowedSamplePointId == null
                ? ""
                : " AND sample_point_id<>CAST(:allowedSamplePointId AS uuid)";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                SELECT sample_point_id FROM registry.sample_point
                WHERE approval_state='APPROVED' AND location_state='VALID'
                  AND governed_point IS NOT NULL
                  AND ST_Equals(governed_point,
                    ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326))
                """ + allowedClause + " ORDER BY sample_point_id")
                .param("longitude", key.longitude())
                .param("latitude", key.latitude());
        if (allowedSamplePointId != null) {
            statement = statement.param("allowedSamplePointId", allowedSamplePointId.toString());
        }
        return statement.query(UUID.class).list();
    }
}
