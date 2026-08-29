package com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure;

import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.Candidate;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.ExportSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.JobSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.RequestSnapshot;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcSamplePointCoordinateCorrectionRepository {
    public static final String EXPORT_TYPE = "SAMPLE_POINT_COORDINATE_EXPORT";
    public static final String JOB_TYPE = "SAMPLE_POINT_COORDINATE_CORRECTION_JOB";
    public static final String REQUEST_TYPE = "SAMPLE_POINT_COORDINATE_CORRECTION_REQUEST";
    public static final String EXPORT_CREATED = "SAMPLE_POINT_COORDINATE_EXPORT_CREATED";
    public static final String JOB_COMPLETED = "SAMPLE_POINT_COORDINATE_CORRECTION_JOB_COMPLETED";
    public static final String REQUEST_SUBMITTED = "SAMPLE_POINT_COORDINATE_CORRECTION_SUBMITTED";
    public static final String REQUEST_APPLIED = "SAMPLE_POINT_COORDINATE_CORRECTION_APPLIED";
    public static final String REQUEST_REJECTED = "SAMPLE_POINT_COORDINATE_CORRECTION_REJECTED";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSamplePointCoordinateCorrectionRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Candidate> findGlobalDuplicates() {
        return jdbc.sql("""
                WITH positioned AS (
                  SELECT point.sample_point_id,point.version,point.canonical_name,point.region_code,
                         region.name region_name,point.kind_code,
                         ST_X(point.governed_point)::numeric longitude,
                         ST_Y(point.governed_point)::numeric latitude
                  FROM registry.sample_point point
                  JOIN platform.region region ON region.code=point.region_code
                  WHERE point.approval_state='APPROVED' AND point.location_state='VALID'
                    AND point.governed_point IS NOT NULL
                ), duplicate_key AS (
                  SELECT longitude,latitude FROM positioned
                  GROUP BY longitude,latitude HAVING count(DISTINCT sample_point_id)>1
                )
                SELECT positioned.* FROM positioned JOIN duplicate_key USING(longitude,latitude)
                ORDER BY longitude,latitude,sample_point_id
                """).query((row, index) -> new Candidate(
                        row.getObject("sample_point_id", UUID.class), row.getLong("version"),
                        row.getString("canonical_name"), row.getString("region_code"),
                        row.getString("region_name"), row.getString("kind_code"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude")))
                .list();
    }

    public void lockIdempotency(String subjectId, String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "SAMPLE_POINT_CORRECTION:" + subjectId + ":" + key)
                .query((row, index) -> Boolean.TRUE).single();
    }

    public void lockRequest(UUID requestId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "SAMPLE_POINT_CORRECTION_REQUEST:" + requestId)
                .query((row, index) -> Boolean.TRUE).single();
    }

    public Optional<Candidate> lockApprovedCandidate(UUID samplePointId) {
        List<Candidate> values = jdbc.sql("""
                SELECT point.sample_point_id,point.version,point.canonical_name,point.region_code,
                       region.name region_name,point.kind_code,
                       ST_X(point.governed_point)::numeric longitude,
                       ST_Y(point.governed_point)::numeric latitude
                FROM registry.sample_point point
                JOIN platform.region region ON region.code=point.region_code
                WHERE point.sample_point_id=:id AND point.approval_state='APPROVED'
                  AND point.location_state='VALID' AND point.governed_point IS NOT NULL
                FOR UPDATE OF point
                """).param("id", samplePointId).query((row, index) -> new Candidate(
                        row.getObject("sample_point_id", UUID.class), row.getLong("version"),
                        row.getString("canonical_name"), row.getString("region_code"),
                        row.getString("region_name"), row.getString("kind_code"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude")))
                .list();
        return values.stream().findFirst();
    }

    public Optional<ExportSnapshot> findExport(
            UUID batchId, String subjectId, String workUnitCode) {
        return jsonOne("""
                SELECT detail::text FROM platform.business_audit_event
                WHERE aggregate_type=:type AND aggregate_id=:id AND action_code=:action
                  AND actor_subject_id=:actor AND work_unit_code=:workUnit
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """, ExportSnapshot.class, EXPORT_TYPE, batchId.toString(), EXPORT_CREATED,
                subjectId, workUnitCode);
    }

    public Optional<JobSnapshot> findJob(
            UUID jobId, String subjectId, String workUnitCode) {
        return jsonOne("""
                SELECT detail::text FROM platform.business_audit_event
                WHERE aggregate_type=:type AND aggregate_id=:id AND action_code=:action
                  AND actor_subject_id=:actor AND work_unit_code=:workUnit
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """, JobSnapshot.class, JOB_TYPE, jobId.toString(), JOB_COMPLETED,
                subjectId, workUnitCode);
    }

    public Optional<JobSnapshot> findJobByIdempotency(
            String subjectId, String workUnitCode, String idempotencyKey) {
        List<String> values = jdbc.sql("""
                SELECT detail::text FROM platform.business_audit_event
                WHERE aggregate_type=:type AND action_code=:action
                  AND actor_subject_id=:actor AND work_unit_code=:workUnit
                  AND detail->>'idempotencyKey'=:key
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """).param("type", JOB_TYPE).param("action", JOB_COMPLETED)
                .param("actor", subjectId).param("workUnit", workUnitCode)
                .param("key", idempotencyKey).query(String.class).list();
        return values.stream().findFirst().map(value -> read(value, JobSnapshot.class));
    }

    public Optional<RequestSnapshot> findRequestByIdempotency(
            String subjectId, String workUnitCode, String idempotencyKey) {
        List<String> values = jdbc.sql("""
                SELECT detail::text FROM platform.business_audit_event
                WHERE aggregate_type=:type AND action_code=:action
                  AND actor_subject_id=:actor AND work_unit_code=:workUnit
                  AND detail->>'idempotencyKey'=:key
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """).param("type", REQUEST_TYPE).param("action", REQUEST_SUBMITTED)
                .param("actor", subjectId).param("workUnit", workUnitCode)
                .param("key", idempotencyKey).query(String.class).list();
        return values.stream().findFirst().map(value -> read(value, RequestSnapshot.class));
    }

    public List<JobSnapshot> history(String subjectId, String workUnitCode) {
        return jdbc.sql("""
                SELECT detail::text FROM platform.business_audit_event
                WHERE aggregate_type=:type AND action_code=:action
                  AND actor_subject_id=:actor AND work_unit_code=:workUnit
                ORDER BY occurred_at DESC,event_id DESC LIMIT 100
                """).param("type", JOB_TYPE).param("action", JOB_COMPLETED)
                .param("actor", subjectId).param("workUnit", workUnitCode)
                .query(String.class).list().stream()
                .map(value -> read(value, JobSnapshot.class)).toList();
    }

    public Optional<RequestSnapshot> findRequest(UUID requestId) {
        List<String> values = jdbc.sql("""
                SELECT detail::text FROM platform.business_audit_event
                WHERE aggregate_type=:type AND aggregate_id=:id AND action_code=:action
                ORDER BY occurred_at,event_id LIMIT 1
                """).param("type", REQUEST_TYPE).param("id", requestId.toString())
                .param("action", REQUEST_SUBMITTED).query(String.class).list();
        return values.stream().findFirst().map(value -> read(value, RequestSnapshot.class));
    }

    public List<RequestSnapshot> pendingRequests(String workUnitCode) {
        return jdbc.sql("""
                SELECT submitted.detail::text
                FROM platform.business_audit_event submitted
                WHERE submitted.aggregate_type=:type AND submitted.action_code=:submitted
                  AND submitted.work_unit_code=:workUnit
                  AND NOT EXISTS (
                    SELECT 1 FROM platform.business_audit_event decided
                    WHERE decided.aggregate_type=submitted.aggregate_type
                      AND decided.aggregate_id=submitted.aggregate_id
                      AND decided.action_code IN (:applied,:rejected))
                ORDER BY submitted.occurred_at,submitted.event_id
                """).param("type", REQUEST_TYPE).param("submitted", REQUEST_SUBMITTED)
                .param("workUnit", workUnitCode).param("applied", REQUEST_APPLIED)
                .param("rejected", REQUEST_REJECTED).query(String.class).list().stream()
                .map(value -> read(value, RequestSnapshot.class)).toList();
    }

    public boolean hasDecision(UUID requestId) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM platform.business_audit_event
                  WHERE aggregate_type=:type AND aggregate_id=:id
                    AND action_code IN (:applied,:rejected))
                """).param("type", REQUEST_TYPE).param("id", requestId.toString())
                .param("applied", REQUEST_APPLIED).param("rejected", REQUEST_REJECTED)
                .query(Boolean.class).single();
    }

    public boolean matchesCurrent(RequestSnapshot request) {
        return matchesCurrent(request.samplePointId(), request.expectedVersion(),
                request.originalLongitude(), request.originalLatitude());
    }

    public boolean matchesCurrent(
            UUID pointId, long version, BigDecimal longitude, BigDecimal latitude) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM registry.sample_point
                  WHERE sample_point_id=:id AND version=:version
                    AND approval_state='APPROVED' AND location_state='VALID'
                    AND ST_Equals(governed_point,
                      ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)))
                """).param("id", pointId).param("version", version)
                .param("longitude", longitude).param("latitude", latitude)
                .query(Boolean.class).single();
    }

    public boolean withinRegion(String regionCode, BigDecimal longitude, BigDecimal latitude) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM overview.administrative_boundary
                  WHERE region_code=:regionCode AND ST_Covers(
                    geometry,ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)))
                """).param("regionCode", regionCode).param("longitude", longitude)
                .param("latitude", latitude).query(Boolean.class).single();
    }

    public int apply(RequestSnapshot request, String reviewer, Instant reviewedAt) {
        return jdbc.sql("""
                UPDATE registry.sample_point
                SET governed_point=ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326),
                    coordinate_shared_verified=false,version=version+1,
                    updated_by=:reviewer,updated_at=:reviewedAt
                WHERE sample_point_id=:id AND version=:version
                  AND approval_state='APPROVED' AND location_state='VALID'
                  AND ST_Equals(governed_point,
                    ST_SetSRID(ST_MakePoint(:originalLongitude,:originalLatitude),4326))
                """).param("longitude", request.correctedLongitude())
                .param("latitude", request.correctedLatitude()).param("reviewer", reviewer)
                .param("reviewedAt", Timestamp.from(reviewedAt)).param("id", request.samplePointId())
                .param("version", request.expectedVersion())
                .param("originalLongitude", request.originalLongitude())
                .param("originalLatitude", request.originalLatitude()).update();
    }

    private <T> Optional<T> jsonOne(
            String sql, Class<T> type, String aggregateType, String aggregateId,
            String action, String actor, String workUnit) {
        List<String> values = jdbc.sql(sql).param("type", aggregateType)
                .param("id", aggregateId).param("action", action).param("actor", actor)
                .param("workUnit", workUnit).query(String.class).list();
        return values.stream().findFirst().map(value -> read(value, type));
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("SAMPLE_POINT_CORRECTION_AUDIT_INVALID", exception);
        }
    }
}
