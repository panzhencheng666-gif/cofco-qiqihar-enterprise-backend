package com.cofco.qiqihar.graintrade.risk.infrastructure;

import com.cofco.qiqihar.graintrade.risk.application.RiskModelLifecycleRepository;
import com.cofco.qiqihar.graintrade.risk.application.RiskModelScore;
import com.cofco.qiqihar.graintrade.risk.application.RiskPredictionOutcome;
import com.cofco.qiqihar.graintrade.risk.application.RiskPromotionCheck;
import com.cofco.qiqihar.graintrade.risk.application.RiskPromotionDecision;
import com.cofco.qiqihar.graintrade.risk.application.RiskRollbackCheck;
import com.cofco.qiqihar.graintrade.risk.application.RiskScoringTask;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcRiskModelLifecycleRepository implements RiskModelLifecycleRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcRiskModelLifecycleRepository(JdbcClient jdbc,ObjectMapper json) {
        this.jdbc=jdbc;
        this.json=json;
    }

    @Override
    @Transactional
    public int startEligibleShadowCandidates(Instant now) {
        return jdbc.sql("""
                UPDATE risk.model_version candidate
                SET status_code='SHADOW',shadow_started_at=:now
                FROM risk.ai_training_policy policy,risk.ai_model model
                WHERE candidate.model_id=policy.model_id
                  AND candidate.model_id=model.model_id
                  AND candidate.status_code='CANDIDATE'
                  AND model.status_code='ACTIVE' AND policy.enabled
                  AND policy.automatic_candidate_enabled AND policy.auto_activation_enabled
                  AND NOT EXISTS (
                    SELECT 1 FROM risk.model_version shadow
                    WHERE shadow.model_id=candidate.model_id AND shadow.status_code='SHADOW')
                """).param("now",dbTime(now)).update();
    }

    @Override
    @Transactional(readOnly=true)
    public List<RiskScoringTask> findPendingScoringTasks(Instant now,int limit) {
        return jdbc.sql("""
                SELECT version.model_id,version.version,model.model_kind,model.base_model_reference,
                       version.artifact_reference,
                       version.artifact_sha256,assessment.assessment_id,version.status_code,
                       jsonb_build_object(
                         'domainCode',assessment.domain_code,
                         'subjectType',assessment.subject_type,
                         'evaluationMode',assessment.evaluation_mode,
                         'riskLevel',assessment.risk_level,
                         'reasonCodes',to_jsonb(assessment.reason_codes),
                         'evidence',assessment.evidence_snapshot)::text canonical_evidence
                FROM risk.model_version version
                JOIN risk.ai_model model ON model.model_id=version.model_id
                JOIN risk.ai_training_policy policy ON policy.model_id=version.model_id
                JOIN risk.risk_assessment assessment
                  ON (model.domain_code='CROSS_DOMAIN' OR model.domain_code=assessment.domain_code)
                 AND assessment.evaluated_at>=CASE WHEN version.status_code='SHADOW'
                       THEN version.shadow_started_at ELSE version.activated_at END
                WHERE version.status_code IN ('SHADOW','ACTIVE','STANDBY')
                  AND model.model_kind IN ('RISK_CLASSIFIER','DOMAIN_LLM')
                  AND policy.auto_activation_enabled
                  AND NOT EXISTS (
                    SELECT 1 FROM risk.model_live_prediction prediction
                    WHERE prediction.model_id=version.model_id
                      AND prediction.model_version=version.version
                      AND prediction.assessment_id=assessment.assessment_id)
                  AND NOT EXISTS (
                    SELECT 1 FROM risk.risk_case_feedback feedback
                    WHERE feedback.assessment_id=assessment.assessment_id
                      AND feedback.resolved_at<=:now)
                ORDER BY assessment.evaluated_at,assessment.assessment_id,
                         version.model_id,version.version
                LIMIT :limit
                """).param("now",dbTime(now)).param("limit",limit)
                .query((row,index) -> new RiskScoringTask(
                        uuid(row,"model_id"),row.getInt("version"),row.getString("model_kind"),
                        row.getString("base_model_reference"),
                        row.getString("artifact_reference"),row.getString("artifact_sha256"),
                        uuid(row,"assessment_id"),row.getString("canonical_evidence"),
                        row.getString("status_code"))).list();
    }

    @Override
    @Transactional
    public void recordPrediction(RiskScoringTask task,RiskModelScore score,Instant scoredAt) {
        jdbc.sql("""
                INSERT INTO risk.model_live_prediction(
                  model_id,model_version,assessment_id,lifecycle_phase,predicted_positive,
                  positive_probability,scored_at)
                VALUES (:model,:version,:assessment,:phase,:predicted,:probability,:scored)
                ON CONFLICT DO NOTHING
                """).param("model",task.modelId()).param("version",task.modelVersion())
                .param("assessment",task.assessmentId()).param("phase",task.lifecyclePhase())
                .param("predicted",score.predictedPositive())
                .param("probability",BigDecimal.valueOf(score.positiveProbability()))
                .param("scored",dbTime(scoredAt)).update();
    }

    @Override
    @Transactional(readOnly=true)
    public List<RiskPromotionCheck> findPromotionChecks(Instant now) {
        List<PromotionRow> candidates=jdbc.sql("""
                SELECT version.model_id,version.version,version.shadow_started_at,
                       policy.minimum_shadow_labels,policy.minimum_shadow_f1,
                       policy.maximum_f1_regression
                FROM risk.model_version version
                JOIN risk.ai_training_policy policy ON policy.model_id=version.model_id
                WHERE version.status_code='SHADOW' AND policy.auto_activation_enabled
                  AND version.shadow_started_at
                      + make_interval(hours=>policy.minimum_shadow_hours)<=:now
                ORDER BY version.shadow_started_at,version.model_id,version.version
                """).param("now",dbTime(now)).query((row,index) -> new PromotionRow(
                        uuid(row,"model_id"),row.getInt("version"),instant(row,"shadow_started_at"),
                        row.getInt("minimum_shadow_labels"),row.getDouble("minimum_shadow_f1"),
                        row.getDouble("maximum_f1_regression"))).list();
        List<RiskPromotionCheck> result=new ArrayList<>();
        for (PromotionRow candidate:candidates) {
            List<OutcomeRow> rows=promotionOutcomes(candidate.modelId(),candidate.version());
            Instant end=rows.stream().map(OutcomeRow::scoredAt).max(Instant::compareTo)
                    .orElse(candidate.shadowStartedAt());
            result.add(new RiskPromotionCheck(candidate.modelId(),candidate.version(),
                    candidate.shadowStartedAt(),end,candidate.minimumLabels(),candidate.minimumF1(),
                    candidate.maximumRegression(),rows.stream().map(OutcomeRow::outcome).toList()));
        }
        return result;
    }

    private List<OutcomeRow> promotionOutcomes(UUID modelId,int version) {
        return jdbc.sql("""
                SELECT feedback.conclusion_code,candidate.predicted_positive,
                       incumbent.predicted_positive incumbent_predicted,candidate.scored_at
                FROM risk.model_live_prediction candidate
                JOIN risk.risk_case_feedback feedback
                  ON feedback.assessment_id=candidate.assessment_id
                 AND feedback.resolved_at>candidate.scored_at
                LEFT JOIN risk.model_version active
                  ON active.model_id=candidate.model_id AND active.status_code='ACTIVE'
                LEFT JOIN risk.model_live_prediction incumbent
                  ON incumbent.model_id=candidate.model_id
                 AND incumbent.model_version=active.version
                 AND incumbent.assessment_id=candidate.assessment_id
                 AND feedback.resolved_at>incumbent.scored_at
                WHERE candidate.model_id=:model AND candidate.model_version=:version
                  AND candidate.lifecycle_phase='SHADOW'
                  AND feedback.conclusion_code IN ('CONFIRMED','MISSED_RISK','FALSE_POSITIVE')
                  AND (active.version IS NULL OR incumbent.assessment_id IS NOT NULL)
                ORDER BY candidate.scored_at,candidate.assessment_id
                """).param("model",modelId).param("version",version)
                .query((row,index) -> outcome(row)).list();
    }

    @Override
    @Transactional
    public void recordPromotionDecision(
            RiskPromotionCheck check,RiskPromotionDecision decision,Instant now) {
        Integer incumbent=activeVersion(check.modelId());
        String metrics=metrics(decision);
        jdbc.sql("""
                INSERT INTO risk.model_evaluation(
                  model_id,model_version,evaluation_window_start,evaluation_window_end,
                  cohort_definition,metric_definition,passed,evaluated_at)
                VALUES (:model,:version,:start,:end,
                  '{"source":"point_in_time_live_predictions","labelTiming":"after_prediction"}',
                  CAST(:metrics AS jsonb),:passed,:now)
                """).param("model",check.modelId()).param("version",check.modelVersion())
                .param("start",dbTime(check.evaluationWindowStart()))
                .param("end",dbTime(check.evaluationWindowEnd()))
                .param("metrics",metrics)
                .param("passed",decision.status()==RiskPromotionDecision.Status.PASSED)
                .param("now",dbTime(now)).update();
        if (decision.status()==RiskPromotionDecision.Status.PASSED) {
            jdbc.sql("""
                    UPDATE risk.model_version SET status_code='APPROVED',shadow_completed_at=:now,
                      approved_by_subject='system:auto-promotion',approved_at=:now
                    WHERE model_id=:model AND version=:version AND status_code='SHADOW'
                    """).param("now",dbTime(now)).param("model",check.modelId())
                    .param("version",check.modelVersion()).update();
            jdbc.sql("""
                    UPDATE risk.model_version SET status_code='STANDBY'
                    WHERE model_id=:model AND status_code='ACTIVE'
                    """).param("model",check.modelId()).update();
            jdbc.sql("""
                    UPDATE risk.model_version SET status_code='ACTIVE',activated_at=:now
                    WHERE model_id=:model AND version=:version AND status_code='APPROVED'
                    """).param("now",dbTime(now)).param("model",check.modelId())
                    .param("version",check.modelVersion()).update();
            activationEvent(check.modelId(),incumbent,check.modelVersion(),"AUTO_ACTIVATED",
                    metrics,decision.reasonCode(),now);
        } else {
            jdbc.sql("""
                    UPDATE risk.model_version SET status_code='REJECTED',shadow_completed_at=:now
                    WHERE model_id=:model AND version=:version AND status_code='SHADOW'
                    """).param("now",dbTime(now)).param("model",check.modelId())
                    .param("version",check.modelVersion()).update();
            activationEvent(check.modelId(),incumbent,check.modelVersion(),"AUTO_REJECTED",
                    metrics,decision.reasonCode(),now);
        }
    }

    @Override
    @Transactional(readOnly=true)
    public List<RiskRollbackCheck> findRollbackChecks(Instant now) {
        List<RollbackRow> pairs=jdbc.sql("""
                SELECT active.model_id,active.version active_version,standby.version standby_version,
                       policy.minimum_shadow_labels,policy.minimum_shadow_f1,policy.rollback_f1_drop
                FROM risk.model_version active
                JOIN risk.model_version standby
                  ON standby.model_id=active.model_id AND standby.status_code='STANDBY'
                JOIN risk.ai_training_policy policy ON policy.model_id=active.model_id
                WHERE active.status_code='ACTIVE' AND policy.auto_activation_enabled
                  AND active.activated_at+make_interval(hours=>policy.minimum_shadow_hours)<=:now
                ORDER BY active.model_id,standby.version DESC
                """).param("now",dbTime(now)).query((row,index) -> new RollbackRow(
                        uuid(row,"model_id"),row.getInt("active_version"),
                        row.getInt("standby_version"),row.getInt("minimum_shadow_labels"),
                        row.getDouble("minimum_shadow_f1"),row.getDouble("rollback_f1_drop"))).list();
        List<RiskRollbackCheck> result=new ArrayList<>();
        for (RollbackRow pair:pairs) {
            List<RiskPredictionOutcome> outcomes=rollbackOutcomes(pair).stream()
                    .map(OutcomeRow::outcome).toList();
            result.add(new RiskRollbackCheck(pair.modelId(),pair.activeVersion(),pair.standbyVersion(),
                    pair.minimumLabels(),pair.minimumF1(),pair.rollbackDrop(),outcomes));
        }
        return result;
    }

    private List<OutcomeRow> rollbackOutcomes(RollbackRow pair) {
        return jdbc.sql("""
                SELECT feedback.conclusion_code,active.predicted_positive,
                       standby.predicted_positive incumbent_predicted,active.scored_at
                FROM risk.model_live_prediction active
                JOIN risk.model_live_prediction standby
                  ON standby.model_id=active.model_id
                 AND standby.model_version=:standby
                 AND standby.assessment_id=active.assessment_id
                JOIN risk.risk_case_feedback feedback
                  ON feedback.assessment_id=active.assessment_id
                 AND feedback.resolved_at>active.scored_at
                 AND feedback.resolved_at>standby.scored_at
                JOIN risk.model_version version
                  ON version.model_id=active.model_id AND version.version=active.model_version
                WHERE active.model_id=:model AND active.model_version=:active
                  AND active.scored_at>=version.activated_at
                  AND feedback.conclusion_code IN ('CONFIRMED','MISSED_RISK','FALSE_POSITIVE')
                ORDER BY active.scored_at,active.assessment_id
                """).param("model",pair.modelId()).param("active",pair.activeVersion())
                .param("standby",pair.standbyVersion()).query((row,index) -> outcome(row)).list();
    }

    @Override
    @Transactional
    public void rollback(RiskRollbackCheck check,RiskPromotionDecision decision,Instant now) {
        String metrics=metrics(decision);
        jdbc.sql("""
                UPDATE risk.model_version SET status_code='STANDBY'
                WHERE model_id=:model AND version=:version AND status_code='ACTIVE'
                """).param("model",check.modelId()).param("version",check.activeVersion()).update();
        jdbc.sql("""
                UPDATE risk.model_version SET status_code='ACTIVE'
                WHERE model_id=:model AND version=:version AND status_code='STANDBY'
                """).param("model",check.modelId()).param("version",check.standbyVersion()).update();
        jdbc.sql("""
                UPDATE risk.model_version SET status_code='RETIRED',retired_at=:now
                WHERE model_id=:model AND version=:version AND status_code='STANDBY'
                """).param("now",dbTime(now)).param("model",check.modelId())
                .param("version",check.activeVersion()).update();
        activationEvent(check.modelId(),check.activeVersion(),check.standbyVersion(),
                "AUTO_ROLLED_BACK",metrics,decision.reasonCode(),now);
    }

    private Integer activeVersion(UUID modelId) {
        return jdbc.sql("""
                SELECT version FROM risk.model_version
                WHERE model_id=:model AND status_code='ACTIVE'
                """).param("model",modelId).query(Integer.class).optional().orElse(null);
    }

    private void activationEvent(UUID modelId,Integer fromVersion,int toVersion,String eventCode,
            String metrics,String reasonCode,Instant now) {
        jdbc.sql("""
                INSERT INTO risk.model_activation_event(
                  event_id,model_id,from_version,to_version,event_code,metric_definition,
                  reason_code,occurred_at)
                VALUES (:id,:model,:from_version,:to_version,:event,CAST(:metrics AS jsonb),
                  :reason,:now)
                """).param("id",UUID.randomUUID()).param("model",modelId)
                .param("from_version",fromVersion).param("to_version",toVersion)
                .param("event",eventCode).param("metrics",metrics).param("reason",reasonCode)
                .param("now",dbTime(now)).update();
    }

    private String metrics(RiskPromotionDecision decision) {
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("resolvedLabelCount",decision.resolvedLabelCount());
        values.put("candidateF1",decision.candidateF1());
        values.put("incumbentF1",decision.incumbentF1());
        values.put("reasonCode",decision.reasonCode());
        try { return json.writeValueAsString(values); }
        catch (Exception exception) { throw new IllegalStateException("模型晋级指标无法序列化",exception); }
    }

    private static OutcomeRow outcome(ResultSet row) throws SQLException {
        Boolean incumbent=(Boolean)row.getObject("incumbent_predicted");
        boolean actual=!"FALSE_POSITIVE".equals(row.getString("conclusion_code"));
        return new OutcomeRow(new RiskPredictionOutcome(actual,
                row.getBoolean("predicted_positive"),incumbent),instant(row,"scored_at"));
    }

    private static UUID uuid(ResultSet row,String column) throws SQLException {
        return row.getObject(column,UUID.class);
    }

    private static Instant instant(ResultSet row,String column) throws SQLException {
        OffsetDateTime value=row.getObject(column,OffsetDateTime.class);
        return value.toInstant();
    }

    private static OffsetDateTime dbTime(Instant value) {
        return OffsetDateTime.ofInstant(value,ZoneOffset.UTC);
    }

    private record PromotionRow(UUID modelId,int version,Instant shadowStartedAt,int minimumLabels,
            double minimumF1,double maximumRegression) { }
    private record RollbackRow(UUID modelId,int activeVersion,int standbyVersion,int minimumLabels,
            double minimumF1,double rollbackDrop) { }
    private record OutcomeRow(RiskPredictionOutcome outcome,Instant scoredAt) { }
}
