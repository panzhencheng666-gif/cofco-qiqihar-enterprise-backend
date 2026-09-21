package com.cofco.qiqihar.graintrade.risk.infrastructure;

import com.cofco.qiqihar.graintrade.risk.application.RiskAssessmentDetail;
import com.cofco.qiqihar.graintrade.risk.application.RiskAssessmentQuery;
import com.cofco.qiqihar.graintrade.risk.application.RiskAssessmentSummary;
import com.cofco.qiqihar.graintrade.risk.application.RiskFeedback;
import com.cofco.qiqihar.graintrade.risk.application.RiskWorkbenchRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcRiskWorkbenchRepository implements RiskWorkbenchRepository {
    private static final String SUMMARY_COLUMNS="""
            assessment.assessment_id,assessment.domain_code,assessment.subject_type,
            assessment.subject_id,assessment.evaluation_mode,assessment.risk_level,
            assessment.reason_codes,assessment.score,assessment.evaluated_at,
            assessment.evaluation_duration_ms,model.model_name,assessment.model_version,
            feedback.feedback_id,feedback.conclusion_code
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcRiskWorkbenchRepository(DataSource dataSource,ObjectMapper json) {
        this.jdbc=JdbcClient.create(dataSource);
        this.json=json;
    }

    @Override
    public List<RiskAssessmentSummary> findAssessments(RiskAssessmentQuery query) {
        return jdbc.sql("""
                SELECT
                """ + SUMMARY_COLUMNS + """
                FROM risk.risk_assessment assessment
                LEFT JOIN risk.ai_model model ON model.model_id=assessment.model_id
                LEFT JOIN risk.risk_case_feedback feedback ON feedback.assessment_id=assessment.assessment_id
                WHERE (:domain='' OR assessment.domain_code=:domain)
                  AND (:level='' OR assessment.risk_level=:level)
                  AND (:status='ALL'
                    OR (:status='OPEN' AND feedback.feedback_id IS NULL)
                    OR (:status='REVIEWED' AND feedback.feedback_id IS NOT NULL))
                  AND (:search='' OR assessment.subject_id ILIKE '%'||:search||'%'
                    OR assessment.subject_type ILIKE '%'||:search||'%'
                    OR EXISTS(SELECT 1 FROM unnest(assessment.reason_codes) reason WHERE reason ILIKE '%'||:search||'%'))
                ORDER BY assessment.evaluated_at DESC,assessment.assessment_id
                LIMIT :limit
                """)
                .param("domain",query.domainCode())
                .param("level",query.riskLevel())
                .param("status",query.reviewStatus())
                .param("search",query.search())
                .param("limit",query.limit())
                .query(this::summary).list();
    }

    @Override
    public Optional<RiskAssessmentDetail> findAssessment(UUID assessmentId) {
        return jdbc.sql("""
                SELECT
                """ + SUMMARY_COLUMNS + """
                  ,assessment.evidence_snapshot::text,
                  judgement.judgement_id,judgement.independent_conclusion,
                  judgement.supporting_evidence::text,judgement.contradicting_evidence::text,
                  judgement.uncertainty_definition::text,judgement.recommended_actions::text,
                  judgement.confidence,judgement.generated_at,
                  feedback.reason_code,feedback.disposition_note,
                  feedback.resolved_by_subject,feedback.resolved_at
                FROM risk.risk_assessment assessment
                LEFT JOIN risk.ai_model model ON model.model_id=assessment.model_id
                LEFT JOIN risk.risk_case_feedback feedback ON feedback.assessment_id=assessment.assessment_id
                LEFT JOIN LATERAL (
                  SELECT value.* FROM risk.ai_judgement value
                  WHERE value.assessment_id=assessment.assessment_id
                  ORDER BY value.generated_at DESC,value.judgement_id DESC LIMIT 1
                ) judgement ON true
                WHERE assessment.assessment_id=:id
                """).param("id",assessmentId).query((row,index) -> new RiskAssessmentDetail(
                        summary(row,index),node(row.getString("evidence_snapshot")),judgement(row),feedback(row)))
                .optional();
    }

    @Override
    public boolean assessmentExists(UUID assessmentId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM risk.risk_assessment WHERE assessment_id=:id)
                """).param("id",assessmentId).query(Boolean.class).single());
    }

    @Override
    public Optional<RiskFeedback> createFeedback(
            UUID assessmentId,String conclusionCode,String reasonCode,String dispositionNote,
            String resolvedBySubject,Instant resolvedAt) {
        UUID feedbackId=UUID.randomUUID();
        return jdbc.sql("""
                INSERT INTO risk.risk_case_feedback(
                  feedback_id,assessment_id,conclusion_code,reason_code,disposition_note,
                  resolved_by_subject,resolved_at)
                VALUES(:feedbackId,:assessmentId,:conclusion,:reason,:note,:actor,:resolvedAt)
                ON CONFLICT (assessment_id) DO NOTHING
                RETURNING feedback_id,assessment_id,conclusion_code,reason_code,disposition_note,
                  resolved_by_subject,resolved_at
                """)
                .param("feedbackId",feedbackId).param("assessmentId",assessmentId)
                .param("conclusion",conclusionCode).param("reason",reasonCode)
                .param("note",dispositionNote).param("actor",resolvedBySubject)
                .param("resolvedAt",Timestamp.from(resolvedAt))
                .query((row,index) -> new RiskFeedback(
                        row.getObject("feedback_id",UUID.class),
                        row.getObject("assessment_id",UUID.class),row.getString("conclusion_code"),
                        row.getString("reason_code"),row.getString("disposition_note"),
                        row.getString("resolved_by_subject"),row.getTimestamp("resolved_at").toInstant()))
                .optional();
    }

    private RiskAssessmentSummary summary(ResultSet row,int index) throws SQLException {
        return new RiskAssessmentSummary(
                row.getObject("assessment_id",UUID.class),row.getString("domain_code"),
                row.getString("subject_type"),row.getString("subject_id"),
                row.getString("evaluation_mode"),row.getString("risk_level"),strings(row.getArray("reason_codes")),
                row.getBigDecimal("score"),row.getObject("evaluated_at",OffsetDateTime.class),
                row.getInt("evaluation_duration_ms"),row.getString("model_name"),
                integer(row,"model_version"),row.getObject("feedback_id")!=null,row.getString("conclusion_code"));
    }

    private RiskAssessmentDetail.AiJudgement judgement(ResultSet row) throws SQLException {
        UUID id=row.getObject("judgement_id",UUID.class);
        if(id==null)return null;
        return new RiskAssessmentDetail.AiJudgement(
                id,row.getString("independent_conclusion"),node(row.getString("supporting_evidence")),
                node(row.getString("contradicting_evidence")),node(row.getString("uncertainty_definition")),
                node(row.getString("recommended_actions")),row.getBigDecimal("confidence"),
                row.getObject("generated_at",OffsetDateTime.class));
    }

    private RiskFeedback feedback(ResultSet row) throws SQLException {
        UUID id=row.getObject("feedback_id",UUID.class);
        if(id==null)return null;
        return new RiskFeedback(id,row.getObject("assessment_id",UUID.class),
                row.getString("conclusion_code"),row.getString("reason_code"),
                row.getString("disposition_note"),row.getString("resolved_by_subject"),
                row.getTimestamp("resolved_at").toInstant());
    }

    private JsonNode node(String value){return value==null?null:json.readTree(value);}
    private static Integer integer(ResultSet row,String column) throws SQLException {
        int value=row.getInt(column);return row.wasNull()?null:value;
    }
    private static List<String> strings(Array value) throws SQLException {
        if(value==null)return List.of();
        return Arrays.stream((Object[])value.getArray()).map(String::valueOf).toList();
    }
}
