package com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure;

import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewEvents.AGGREGATE_TYPE;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewEvents.CONFIRM_DISTINCT;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewEvents.LINK_EXISTING;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewEvents.RETURN_FOR_CORRECTION;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityReviewEvents.SUBMITTED;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSampleIdentityReviewRepository {
    private final JdbcClient jdbc;

    public JdbcSampleIdentityReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SubmissionSnapshot> submission(UUID draftId) {
        return jdbc.sql("""
                SELECT actor_subject_id,work_unit_code,
                       detail->>'reasonCode' reason_code,
                       detail->>'reasonMessage' reason_message,
                       coalesce((SELECT string_agg(item.value,',')
                         FROM jsonb_array_elements_text(coalesce(
                           detail->'candidateSamplePointIds','[]'::jsonb)) item(value)),'')
                         candidate_sample_point_ids
                FROM platform.business_audit_event
                WHERE aggregate_type=:type AND aggregate_id=:id AND action_code=:action
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """).param("type", AGGREGATE_TYPE).param("id", draftId.toString())
                .param("action", SUBMITTED)
                .query((row, ignored) -> new SubmissionSnapshot(
                        row.getString("actor_subject_id"), row.getString("work_unit_code"),
                        row.getString("reason_code"), row.getString("reason_message"),
                        uuidList(row.getString("candidate_sample_point_ids"))))
                .optional();
    }

    public Optional<DecisionSnapshot> decision(UUID draftId) {
        return jdbc.sql("""
                SELECT action_code,actor_subject_id,occurred_at,
                       detail->>'targetSamplePointId' target_sample_point_id,
                       detail->>'reason' reason,
                       coalesce((detail->>'privilegedSelfReview')::boolean,false) privileged_self_review
                FROM platform.business_audit_event
                WHERE aggregate_type=:type AND aggregate_id=:id
                  AND action_code IN (:link,:distinct,:returned)
                ORDER BY occurred_at DESC,event_id DESC LIMIT 1
                """).param("type", AGGREGATE_TYPE).param("id", draftId.toString())
                .param("link", LINK_EXISTING).param("distinct", CONFIRM_DISTINCT)
                .param("returned", RETURN_FOR_CORRECTION)
                .query((row, ignored) -> new DecisionSnapshot(
                        decisionCode(row.getString("action_code")),
                        uuid(row.getString("target_sample_point_id")),
                        row.getString("reason"), row.getString("actor_subject_id"),
                        row.getTimestamp("occurred_at").toInstant(),
                        row.getBoolean("privileged_self_review")))
                .optional();
    }

    private static String decisionCode(String actionCode) {
        return switch (actionCode) {
            case LINK_EXISTING -> "LINK_EXISTING";
            case CONFIRM_DISTINCT -> "CONFIRM_DISTINCT";
            case RETURN_FOR_CORRECTION -> "RETURN_FOR_CORRECTION";
            default -> throw new IllegalStateException("Unknown sample identity decision");
        };
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static List<UUID> uuidList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(UUID::fromString).distinct().toList();
    }

    public record SubmissionSnapshot(
            String submittedBy, String workUnitCode, String reasonCode, String reasonMessage,
            List<UUID> candidateSamplePointIds) {}

    public record DecisionSnapshot(
            String decision, UUID targetSamplePointId, String reason, String decidedBy,
            Instant decidedAt, boolean privilegedSelfReview) {}
}
