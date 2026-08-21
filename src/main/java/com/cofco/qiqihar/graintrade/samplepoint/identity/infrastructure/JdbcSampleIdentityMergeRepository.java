package com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure;

import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityGovernanceWorkbook.Row;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.ExportSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.JobSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.RequestSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcSampleIdentityMergeRepository {
    public static final String EXPORT_TYPE = "SAMPLE_IDENTITY_MERGE_EXPORT";
    public static final String JOB_TYPE = "SAMPLE_IDENTITY_MERGE_JOB";
    public static final String REQUEST_TYPE = "SAMPLE_IDENTITY_MERGE_REQUEST";
    public static final String EXPORT_CREATED = "SAMPLE_IDENTITY_MERGE_EXPORT_CREATED";
    public static final String JOB_COMPLETED = "SAMPLE_IDENTITY_MERGE_JOB_COMPLETED";
    public static final String REQUEST_SUBMITTED = "SAMPLE_IDENTITY_MERGE_SUBMITTED";
    public static final String REQUEST_APPROVAL_AUTHORIZED =
            "SAMPLE_IDENTITY_MERGE_APPROVAL_AUTHORIZED";
    public static final String REQUEST_APPLIED = "SAMPLE_IDENTITY_MERGE_APPLIED";
    public static final String REQUEST_REJECTED = "SAMPLE_IDENTITY_MERGE_REJECTED";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSampleIdentityMergeRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<CandidateRecord> findHistoricalDuplicates() {
        return jdbc.sql("""
                WITH observed AS (
                  SELECT 'PRODUCTION' source_domain,record.record_id source_record_id,
                         record.version source_version,record.product_code,
                         concat(record.survey_year,'-',lpad(coalesce(record.survey_month,1)::text,2,'0')) survey_period,
                         record.sample_point_id,sample_name.value sample_name,
                         sample_contact.value sample_contact,record.region_code
                  FROM production.production_record record
                  JOIN production.production_record_submission_metadata sample_name
                    ON sample_name.record_id=record.record_id AND sample_name.field_code='PROD_SAMPLE_NAME'
                  JOIN production.production_record_submission_metadata sample_contact
                    ON sample_contact.record_id=record.record_id AND sample_contact.field_code='PROD_SAMPLE_CONTACT'
                  WHERE record.status_code='APPROVED' AND record.sample_point_id IS NOT NULL
                  UNION ALL
                  SELECT 'MARKET',record.record_id,record.version,record.product_code,
                         to_char(record.trade_date,'YYYY-MM'),record.sample_point_id,
                         sample_name.value,sample_contact.value,record.region_code
                  FROM market.market_record record
                  JOIN market.market_record_core_value sample_name
                    ON sample_name.record_id=record.record_id AND sample_name.field_code='MKT_SAMPLE_NAME'
                  JOIN market.market_record_core_value sample_contact
                    ON sample_contact.record_id=record.record_id AND sample_contact.field_code='MKT_SAMPLE_CONTACT'
                  WHERE record.status_code='APPROVED' AND record.sample_point_id IS NOT NULL
                ), eligible AS (
                  SELECT observed.*,region.name region_name,point.effective_from,
                         ST_X(point.governed_point)::numeric longitude,
                         ST_Y(point.governed_point)::numeric latitude,
                         lower(regexp_replace(normalize(btrim(observed.sample_name),NFKC),
                           '[[:space:]]+','','g')) name_key,
                         lower(regexp_replace(normalize(btrim(observed.sample_contact),NFKC),
                           '[[:space:]()（）-]+','','g')) contact_key
                  FROM observed
                  JOIN registry.sample_point point ON point.sample_point_id=observed.sample_point_id
                  JOIN platform.region region ON region.code=observed.region_code
                  WHERE point.kind_code='SURVEY_SITE' AND point.approval_state='APPROVED'
                    AND point.location_state='VALID' AND point.governed_point IS NOT NULL
                    AND NOT EXISTS(
                      SELECT 1 FROM registry.current_sample_subject_resolution resolution
                      WHERE resolution.source_domain=observed.source_domain
                        AND resolution.source_record_id=observed.source_record_id)
                ), duplicate_group AS (
                  SELECT source_domain,name_key,contact_key,region_code,longitude,latitude
                  FROM eligible GROUP BY source_domain,name_key,contact_key,region_code,longitude,latitude
                  HAVING count(DISTINCT sample_point_id)>1
                ), point_count AS (
                  SELECT source_domain,name_key,contact_key,region_code,longitude,latitude,
                         sample_point_id,count(*) approved_record_count,min(effective_from) effective_from
                  FROM eligible GROUP BY source_domain,name_key,contact_key,region_code,longitude,latitude,
                    sample_point_id
                ), canonical AS (
                  SELECT DISTINCT ON (source_domain,name_key,contact_key,region_code,longitude,latitude)
                         source_domain,name_key,contact_key,region_code,longitude,latitude,
                         sample_point_id canonical_sample_point_id
                  FROM point_count
                  ORDER BY source_domain,name_key,contact_key,region_code,longitude,latitude,
                           approved_record_count DESC,effective_from,sample_point_id
                )
                SELECT eligible.*,point_count.approved_record_count,
                       canonical.canonical_sample_point_id
                FROM eligible
                JOIN duplicate_group USING(
                  source_domain,name_key,contact_key,region_code,longitude,latitude)
                JOIN point_count USING(
                  source_domain,name_key,contact_key,region_code,longitude,latitude,sample_point_id)
                JOIN canonical USING(
                  source_domain,name_key,contact_key,region_code,longitude,latitude)
                ORDER BY source_domain,name_key,contact_key,region_code,longitude,latitude,
                         sample_point_id,source_record_id
                """).query((row, ignored) -> new CandidateRecord(
                        row.getString("source_record_id"), row.getLong("source_version"),
                        row.getString("source_domain"), row.getString("product_code"),
                        row.getString("survey_period"), row.getObject("sample_point_id", UUID.class),
                        row.getString("sample_name"), row.getString("sample_contact"),
                        row.getString("region_code"), row.getString("region_name"),
                        row.getBigDecimal("longitude"), row.getBigDecimal("latitude"),
                        row.getInt("approved_record_count"),
                        row.getObject("canonical_sample_point_id", UUID.class)))
                .list();
    }

    public void lockIdempotency(String subjectId, String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "SAMPLE_IDENTITY_MERGE:" + subjectId + ":" + key)
                .query((row, ignored) -> Boolean.TRUE).single();
    }

    public void lockRequest(UUID requestId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "SAMPLE_IDENTITY_MERGE_REQUEST:" + requestId)
                .query((row, ignored) -> Boolean.TRUE).single();
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

    public Optional<JobSnapshot> findJob(UUID jobId, String subjectId, String workUnitCode) {
        return jsonOne("""
                SELECT detail::text FROM platform.business_audit_event
                WHERE aggregate_type=:type AND aggregate_id=:id AND action_code=:action
                  AND actor_subject_id=:actor AND work_unit_code=:workUnit
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """, JobSnapshot.class, JOB_TYPE, jobId.toString(), JOB_COMPLETED,
                subjectId, workUnitCode);
    }

    public Optional<JobSnapshot> findJobByIdempotency(
            String subjectId, String workUnitCode, String key) {
        return jdbc.sql("""
                SELECT detail::text FROM platform.business_audit_event
                WHERE aggregate_type=:type AND action_code=:action
                  AND actor_subject_id=:actor AND work_unit_code=:workUnit
                  AND detail->'view'->>'idempotencyKey'=:key
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """).param("type", JOB_TYPE).param("action", JOB_COMPLETED)
                .param("actor", subjectId).param("workUnit", workUnitCode).param("key", key)
                .query(String.class).list().stream().findFirst()
                .map(value -> read(value, JobSnapshot.class));
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
        return jdbc.sql("""
                SELECT detail::text FROM platform.business_audit_event
                WHERE aggregate_type=:type AND aggregate_id=:id AND action_code=:action
                ORDER BY occurred_at,event_id LIMIT 1
                """).param("type", REQUEST_TYPE).param("id", requestId.toString())
                .param("action", REQUEST_SUBMITTED).query(String.class).list().stream()
                .findFirst().map(value -> read(value, RequestSnapshot.class));
    }

    public List<RequestSnapshot> pendingRequests(String workUnitCode) {
        return jdbc.sql("""
                SELECT submitted.detail::text FROM platform.business_audit_event submitted
                WHERE submitted.aggregate_type=:type AND submitted.action_code=:submitted
                  AND submitted.work_unit_code=:workUnit
                  AND NOT EXISTS(
                    SELECT 1 FROM platform.business_audit_event decision
                    WHERE decision.aggregate_type=submitted.aggregate_type
                      AND decision.aggregate_id=submitted.aggregate_id
                      AND decision.action_code IN (:applied,:rejected))
                ORDER BY submitted.occurred_at,submitted.event_id
                """).param("type", REQUEST_TYPE).param("submitted", REQUEST_SUBMITTED)
                .param("workUnit", workUnitCode).param("applied", REQUEST_APPLIED)
                .param("rejected", REQUEST_REJECTED).query(String.class).list().stream()
                .map(value -> read(value, RequestSnapshot.class)).toList();
    }

    public Optional<DecisionRecord> decision(UUID requestId) {
        return jdbc.sql("""
                SELECT action_code,actor_subject_id,occurred_at,detail->>'reason' reason,
                       nullif(detail->>'resolutionBatchId','')::uuid resolution_batch_id,
                       coalesce((detail->>'privilegedSelfReview')::boolean,false) privileged_self_review
                FROM platform.business_audit_event
                WHERE aggregate_type=:type AND aggregate_id=:id
                  AND action_code IN (:applied,:rejected)
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """).param("type", REQUEST_TYPE).param("id", requestId.toString())
                .param("applied", REQUEST_APPLIED).param("rejected", REQUEST_REJECTED)
                .query((row, ignored) -> new DecisionRecord(
                        row.getString("action_code"), row.getString("actor_subject_id"),
                        row.getTimestamp("occurred_at").toInstant(), row.getString("reason"),
                        row.getObject("resolution_batch_id", UUID.class),
                        row.getBoolean("privileged_self_review")))
                .optional();
    }

    public boolean matchesCurrent(RequestSnapshot request) {
        String table = "PRODUCTION".equals(request.sourceDomain())
                ? "production.production_record" : "market.market_record";
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM %s record
                  JOIN registry.sample_point current_point
                    ON current_point.sample_point_id=record.sample_point_id
                  JOIN registry.sample_point target_point
                    ON target_point.sample_point_id=:target
                  WHERE record.record_id=:record AND record.version=:version
                    AND record.status_code='APPROVED'
                    AND record.sample_point_id=:current
                    AND target_point.kind_code='SURVEY_SITE'
                    AND target_point.approval_state='APPROVED'
                    AND target_point.location_state='VALID'
                    AND target_point.region_code=:region
                    AND ST_Equals(target_point.governed_point,
                      ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326))
                    AND NOT EXISTS(
                      SELECT 1 FROM registry.current_sample_subject_resolution resolution
                      WHERE resolution.source_domain=:domain
                        AND resolution.source_record_id=:record))
                """.formatted(table)).param("target", request.targetSamplePointId())
                .param("record", request.sourceRecordId())
                .param("version", request.expectedSourceVersion())
                .param("current", request.currentSamplePointId())
                .param("region", request.regionCode()).param("longitude", request.longitude())
                .param("latitude", request.latitude()).param("domain", request.sourceDomain())
                .query(Boolean.class).single();
    }

    public UUID stageAndApply(RequestSnapshot request, String inputDigest) {
        UUID batchId = UUID.nameUUIDFromBytes(
                ("SAMPLE_IDENTITY_MERGE:" + request.requestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String result = jdbc.sql("""
                SELECT registry.apply_reviewed_sample_identity_merge(
                  :request,:batch,CAST(:digest AS char(64)))
                """).param("request", request.requestId()).param("batch", batchId)
                .param("digest", inputDigest).query(String.class).single();
        if (!Set.of("APPLIED", "ALREADY_APPLIED").contains(result)) {
            throw new IllegalStateException("Invalid identity merge apply result: " + result);
        }
        return batchId;
    }

    private <T> Optional<T> jsonOne(
            String sql, Class<T> type, String aggregateType, String aggregateId,
            String action, String actor, String workUnit) {
        return jdbc.sql(sql).param("type", aggregateType).param("id", aggregateId)
                .param("action", action).param("actor", actor).param("workUnit", workUnit)
                .query(String.class).list().stream().findFirst()
                .map(value -> read(value, type));
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid sample identity merge snapshot", exception);
        }
    }

    public record CandidateRecord(
            String sourceRecordId, long sourceVersion, String sourceDomain, String productCode,
            String surveyPeriod, UUID currentSamplePointId, String sampleName, String sampleContact,
            String regionCode, String regionName, BigDecimal longitude, BigDecimal latitude,
            int approvedRecordCount, UUID canonicalSamplePointId) {}

    public record DecisionRecord(
            String actionCode, String actor, Instant occurredAt, String reason,
            UUID resolutionBatchId, boolean privilegedSelfReview) {}
}
