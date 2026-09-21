package com.cofco.qiqihar.graintrade.risk.infrastructure;

import com.cofco.qiqihar.graintrade.risk.application.LocalRiskClassifierTrainer;
import com.cofco.qiqihar.graintrade.risk.application.ExternalLoraRiskModelTrainer;
import com.cofco.qiqihar.graintrade.risk.application.RiskModelSummary;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingArtifact;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingClaim;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingExample;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingExecutionSummary;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingRepository;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingSnapshot;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcRiskTrainingRepository implements RiskTrainingRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcRiskTrainingRepository(JdbcClient jdbc,ObjectMapper json) {
        this.jdbc=jdbc;
        this.json=json;
    }

    @Override
    @Transactional(readOnly=true)
    public List<RiskModelSummary> findModels() {
        return jdbc.sql("""
                SELECT model.model_id,model.model_name,model.model_kind,
                       model.domain_code,model.status_code,
                       COALESCE(policy.enabled,false) policy_enabled,policy.scheduled_local_time,
                       policy.schedule_timezone,COALESCE(policy.training_window_days,0) training_window_days,
                       COALESCE(policy.minimum_new_labels,0) minimum_new_labels,
                       COALESCE(policy.automatic_candidate_enabled,false) automatic_candidate_enabled,
                       COALESCE(policy.auto_activation_enabled,false) auto_activation_enabled,
                       execution.scheduled_local_date,execution.status_code last_execution_status,
                       execution.outcome_code,execution.outcome_message,execution.completed_at,
                       version.version latest_version,version.status_code latest_version_status
                FROM risk.ai_model model
                LEFT JOIN risk.ai_training_policy policy ON policy.model_id=model.model_id
                LEFT JOIN LATERAL (
                  SELECT scheduled_local_date,status_code,outcome_code,outcome_message,completed_at
                  FROM risk.training_schedule_execution item
                  WHERE item.model_id=model.model_id
                  ORDER BY item.created_at DESC,item.execution_id DESC LIMIT 1
                ) execution ON true
                LEFT JOIN LATERAL (
                  SELECT version,status_code,artifact_reference
                  FROM risk.model_version item WHERE item.model_id=model.model_id
                  ORDER BY item.version DESC LIMIT 1
                ) version ON true
                ORDER BY model.model_kind,model.model_name
                """).query((row,index) -> new RiskModelSummary(
                        uuid(row,"model_id"),row.getString("model_name"),row.getString("model_kind"),
                        row.getString("domain_code"),row.getString("status_code"),
                        row.getBoolean("policy_enabled"),row.getObject("scheduled_local_time",java.time.LocalTime.class),
                        row.getString("schedule_timezone"),row.getInt("training_window_days"),
                        row.getInt("minimum_new_labels"),row.getBoolean("automatic_candidate_enabled"),
                        row.getBoolean("auto_activation_enabled"),
                        row.getObject("scheduled_local_date",java.time.LocalDate.class),
                        row.getString("last_execution_status"),row.getString("outcome_code"),
                        row.getString("outcome_message"),instant(row,"completed_at"),
                        integer(row,"latest_version"),row.getString("latest_version_status"))).list();
    }

    @Override
    @Transactional(readOnly=true)
    public List<RiskTrainingExecutionSummary> findRecentExecutions(int limit) {
        return jdbc.sql("""
                SELECT execution.execution_id,execution.model_id,model.model_name,
                       execution.scheduled_local_date,execution.trigger_code,execution.status_code,
                       execution.outcome_code,execution.outcome_message,
                       execution.training_snapshot_id,execution.training_run_id,
                       execution.created_at,execution.started_at,execution.completed_at
                FROM risk.training_schedule_execution execution
                JOIN risk.ai_model model ON model.model_id=execution.model_id
                ORDER BY execution.created_at DESC,execution.execution_id DESC LIMIT :limit
                """).param("limit",limit).query((row,index) -> new RiskTrainingExecutionSummary(
                        uuid(row,"execution_id"),uuid(row,"model_id"),row.getString("model_name"),
                        row.getObject("scheduled_local_date",java.time.LocalDate.class),
                        row.getString("trigger_code"),row.getString("status_code"),
                        row.getString("outcome_code"),row.getString("outcome_message"),
                        uuid(row,"training_snapshot_id"),uuid(row,"training_run_id"),
                        instant(row,"created_at"),instant(row,"started_at"),instant(row,"completed_at"))).list();
    }

    @Override
    @Transactional
    public boolean configureExternalLlm(String baseModelReference,Instant configuredAt) {
        int updated=jdbc.sql("""
                UPDATE risk.ai_model
                SET base_model_reference=:base,status_code='ACTIVE',
                    updated_by_subject='system:risk-llm-provisioner',updated_at=:now
                WHERE model_code='risk-reasoning-llm-v1' AND status_code='DRAFT'
                """).param("base",baseModelReference).param("now",dbTime(configuredAt)).update();
        int enabled=jdbc.sql("""
                UPDATE risk.ai_training_policy SET enabled=true,updated_at=:now
                WHERE model_id=(SELECT model_id FROM risk.ai_model
                  WHERE model_code='risk-reasoning-llm-v1' AND status_code='ACTIVE')
                """).param("now",dbTime(configuredAt)).update();
        return updated>0 || enabled>0;
    }

    @Override
    @Transactional
    public int enqueueDueDailyExecutions(Instant now) {
        return jdbc.sql("""
                INSERT INTO risk.training_schedule_execution(
                  execution_id,training_policy_id,model_id,scheduled_local_date,trigger_code,
                  requested_by_subject,due_at,status_code,created_at)
                SELECT gen_random_uuid(),policy.training_policy_id,policy.model_id,
                       (:now AT TIME ZONE policy.schedule_timezone)::date,'DAILY',
                       'system:risk-training-scheduler',
                       (((:now AT TIME ZONE policy.schedule_timezone)::date
                         + policy.scheduled_local_time) AT TIME ZONE policy.schedule_timezone),
                       'QUEUED',:now
                FROM risk.ai_training_policy policy
                JOIN risk.ai_model model ON model.model_id=policy.model_id
                WHERE policy.enabled AND model.status_code='ACTIVE'
                  AND (((:now AT TIME ZONE policy.schedule_timezone)::date
                        + policy.scheduled_local_time) AT TIME ZONE policy.schedule_timezone) <= :now
                ON CONFLICT DO NOTHING
                """).param("now",dbTime(now)).update();
    }

    @Override
    @Transactional
    public int failExpiredExecutions(Instant now) {
        jdbc.sql("""
                UPDATE risk.training_run run
                SET status_code='FAILED',failure_code='WORKER_LEASE_EXPIRED',
                    failure_message='训练执行器租约已过期，已终止本次运行',completed_at=:now
                FROM risk.training_schedule_execution execution
                WHERE execution.training_run_id=run.training_run_id
                  AND execution.status_code='RUNNING' AND execution.lease_until<:now
                  AND run.status_code='RUNNING'
                """).param("now",dbTime(now)).update();
        return jdbc.sql("""
                UPDATE risk.training_schedule_execution
                SET status_code='FAILED',outcome_code='WORKER_LEASE_EXPIRED',
                    outcome_message='训练执行器租约已过期，请检查执行节点后重新发起',
                    completed_at=:now,lease_owner=NULL,lease_until=NULL
                WHERE status_code='RUNNING' AND lease_until<:now
                """).param("now",dbTime(now)).update();
    }

    @Override
    @Transactional
    public UUID enqueueManualExecution(UUID modelId,String requestedBySubject,Instant now) {
        UUID executionId=UUID.randomUUID();
        Optional<UUID> created=jdbc.sql("""
                INSERT INTO risk.training_schedule_execution(
                  execution_id,training_policy_id,model_id,scheduled_local_date,trigger_code,
                  requested_by_subject,due_at,status_code,created_at)
                SELECT :execution_id,policy.training_policy_id,model.model_id,
                       (:now AT TIME ZONE policy.schedule_timezone)::date,'MANUAL',
                       :subject,:now,'QUEUED',:now
                FROM risk.ai_model model
                JOIN risk.ai_training_policy policy ON policy.model_id=model.model_id
                WHERE model.model_id=:model_id AND model.status_code='ACTIVE' AND policy.enabled
                RETURNING execution_id
                """).param("execution_id",executionId).param("now",dbTime(now))
                .param("subject",requestedBySubject).param("model_id",modelId)
                .query(UUID.class).optional();
        return created.orElseThrow(() -> new ClientRequestException(
                "RISK_TRAINING_NOT_AVAILABLE","模型未启用、已暂停或尚未配置获批训练策略"));
    }

    @Override
    @Transactional
    public Optional<RiskTrainingClaim> claimNext(
            Instant now,String workerId,Duration leaseDuration) {
        return jdbc.sql("""
                WITH candidate AS (
                  SELECT execution.execution_id
                  FROM risk.training_schedule_execution execution
                  WHERE execution.status_code='QUEUED' AND execution.due_at<=:now
                    AND (execution.lease_until IS NULL OR execution.lease_until<:now)
                    AND NOT EXISTS (
                      SELECT 1 FROM risk.training_schedule_execution running
                      WHERE running.model_id=execution.model_id AND running.status_code='RUNNING')
                  ORDER BY execution.due_at,execution.created_at
                  FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE risk.training_schedule_execution execution
                SET status_code='RUNNING',started_at=:now,lease_owner=:worker,
                    lease_until=:lease_until
                FROM candidate, risk.ai_training_policy policy, risk.ai_model model
                WHERE execution.execution_id=candidate.execution_id
                  AND policy.training_policy_id=execution.training_policy_id
                  AND model.model_id=execution.model_id
                RETURNING execution.execution_id,execution.training_policy_id,execution.model_id,
                          model.model_code,model.model_name,model.model_kind,model.domain_code,
                          model.base_model_reference,policy.training_window_days,
                          policy.minimum_new_labels
                """).param("now",dbTime(now)).param("worker",workerId)
                .param("lease_until",dbTime(now.plus(leaseDuration)))
                .query((row,index) -> new RiskTrainingClaim(
                        uuid(row,"execution_id"),uuid(row,"training_policy_id"),uuid(row,"model_id"),
                        row.getString("model_code"),row.getString("model_name"),
                        row.getString("model_kind"),row.getString("domain_code"),
                        row.getString("base_model_reference"),row.getInt("training_window_days"),
                        row.getInt("minimum_new_labels"),seed(uuid(row,"execution_id")))).optional();
    }

    @Override
    @Transactional
    public RiskTrainingSnapshot freezeTrainingSnapshot(RiskTrainingClaim claim,Instant cutoffAt) {
        Instant windowStart=cutoffAt.minus(Duration.ofDays(claim.trainingWindowDays()));
        List<LabelRow> labels=jdbc.sql("""
                SELECT assessment.assessment_id,feedback.resolved_at,feedback.conclusion_code,
                       assessment.domain_code,assessment.subject_type,assessment.evaluation_mode,
                       assessment.risk_level,array_to_json(assessment.reason_codes)::text reason_codes,
                       assessment.evidence_snapshot::text evidence_snapshot,feedback.reason_code
                FROM risk.risk_case_feedback feedback
                JOIN risk.risk_assessment assessment ON assessment.assessment_id=feedback.assessment_id
                WHERE feedback.resolved_at>=:window_start AND feedback.resolved_at<=:cutoff
                  AND feedback.conclusion_code IN ('CONFIRMED','MISSED_RISK','FALSE_POSITIVE')
                  AND (:domain='CROSS_DOMAIN' OR assessment.domain_code=:domain)
                ORDER BY feedback.resolved_at,assessment.assessment_id
                """).param("window_start",dbTime(windowStart)).param("cutoff",dbTime(cutoffAt))
                .param("domain",claim.domainCode()).query((row,index) -> label(row)).list();
        List<RiskTrainingExample> examples=new ArrayList<>();
        List<String> payloads=new ArrayList<>();
        for (LabelRow label:labels) {
            Map<String,Object> payload=new LinkedHashMap<>();
            payload.put("domainCode",label.domainCode());
            payload.put("subjectType",label.subjectType());
            payload.put("evaluationMode",label.evaluationMode());
            payload.put("riskLevel",label.riskLevel());
            payload.put("reasonCodes",readJson(label.reasonCodes()));
            payload.put("evidence",readJson(label.evidenceSnapshot()));
            payload.put("feedbackReasonCode",label.feedbackReasonCode());
            String canonical=writeJson(payload);
            boolean positive=!"FALSE_POSITIVE".equals(label.conclusionCode());
            examples.add(new RiskTrainingExample(label.assessmentId(),label.resolvedAt(),canonical,positive));
            payloads.add(label.assessmentId()+"|"+label.resolvedAt()+"|"+label.conclusionCode()+"|"+canonical);
        }
        String dataHash=sha256(String.join("\n",payloads).getBytes(StandardCharsets.UTF_8));
        UUID proposed=UUID.randomUUID();
        long positives=examples.stream().filter(RiskTrainingExample::positive).count();
        UUID snapshotId=jdbc.sql("""
                INSERT INTO risk.training_snapshot(
                  training_snapshot_id,domain_code,snapshot_date,cutoff_at,data_sha256,
                  feature_schema_version,row_count,positive_label_count,negative_label_count,
                  source_watermarks,status_code)
                VALUES (:id,:domain,(:cutoff AT TIME ZONE 'Asia/Shanghai')::date,:cutoff,:hash,
                        'risk-feedback-v1',:rows,:positive,:negative,CAST(:watermarks AS jsonb),'FROZEN')
                ON CONFLICT (domain_code,snapshot_date,data_sha256)
                DO UPDATE SET data_sha256=EXCLUDED.data_sha256
                RETURNING training_snapshot_id
                """).param("id",proposed).param("domain",claim.domainCode())
                .param("cutoff",dbTime(cutoffAt)).param("hash",dataHash).param("rows",examples.size())
                .param("positive",positives).param("negative",examples.size()-positives)
                .param("watermarks",writeJson(Map.of(
                        "windowStart",windowStart.toString(),"cutoffAt",cutoffAt.toString(),
                        "labelSource","risk.risk_case_feedback")))
                .query(UUID.class).single();
        int ordinal=0;
        for (int index=0;index<labels.size();index++) {
            LabelRow label=labels.get(index);
            RiskTrainingExample example=examples.get(index);
            ordinal++;
            jdbc.sql("""
                    INSERT INTO risk.training_example(
                      training_snapshot_id,ordinal,assessment_id,resolved_at,label_code,
                      positive_label,feature_payload,payload_sha256)
                    VALUES (:snapshot,:ordinal,:assessment,:resolved,:label,:positive,
                            CAST(:payload AS jsonb),:hash)
                    ON CONFLICT (training_snapshot_id,assessment_id) DO NOTHING
                    """).param("snapshot",snapshotId).param("ordinal",ordinal)
                    .param("assessment",label.assessmentId()).param("resolved",dbTime(label.resolvedAt()))
                    .param("label",label.conclusionCode()).param("positive",example.positive())
                    .param("payload",example.canonicalText())
                    .param("hash",sha256(example.canonicalText().getBytes(StandardCharsets.UTF_8))).update();
        }
        jdbc.sql("""
                UPDATE risk.training_schedule_execution SET training_snapshot_id=:snapshot
                WHERE execution_id=:execution AND status_code='RUNNING'
                """).param("snapshot",snapshotId).param("execution",claim.executionId()).update();
        return new RiskTrainingSnapshot(snapshotId,dataHash,examples,positives,examples.size()-positives);
    }

    @Override
    @Transactional
    public UUID createTrainingRun(RiskTrainingClaim claim,RiskTrainingSnapshot snapshot,
            Instant now,String algorithmCode) {
        UUID runId=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO risk.training_run(
                  training_run_id,model_id,training_snapshot_id,domain_code,training_kind,
                  algorithm_code,algorithm_version,code_sha256,parameter_definition,random_seed,
                  status_code,created_at)
                VALUES (:run,:model,:snapshot,:domain,:training_kind,:algorithm,'1',:code_hash,
                        CAST(:parameters AS jsonb),:seed,'QUEUED',:now)
                """).param("run",runId).param("model",claim.modelId())
                .param("snapshot",snapshot.trainingSnapshotId()).param("domain",claim.domainCode())
                .param("training_kind","DOMAIN_LLM".equals(claim.modelKind())?"LORA_ADAPTER":"RETRAIN")
                .param("algorithm",algorithmCode).param("code_hash",trainerCodeSha256(algorithmCode))
                .param("parameters",writeJson(Map.of("trainingWindowDays",claim.trainingWindowDays(),
                        "minimumNewLabels",claim.minimumNewLabels(),"featureSchema","risk-feedback-v1")))
                .param("seed",claim.randomSeed()).param("now",dbTime(now)).update();
        jdbc.sql("""
                UPDATE risk.training_schedule_execution SET training_run_id=:run
                WHERE execution_id=:execution AND status_code='RUNNING'
                """).param("run",runId).param("execution",claim.executionId()).update();
        return runId;
    }

    @Override
    @Transactional
    public void markRunRunning(UUID trainingRunId,Instant startedAt) {
        jdbc.sql("""
                UPDATE risk.training_run SET status_code='RUNNING',started_at=:started
                WHERE training_run_id=:run AND status_code='QUEUED'
                """).param("started",dbTime(startedAt)).param("run",trainingRunId).update();
    }

    @Override
    @Transactional(readOnly=true)
    public int nextModelVersion(UUID modelId) {
        return jdbc.sql("SELECT COALESCE(max(version),0)+1 FROM risk.model_version WHERE model_id=:model")
                .param("model",modelId).query(Integer.class).single();
    }

    @Override
    @Transactional
    public void completeRunAndCreateCandidate(UUID executionId,UUID trainingRunId,UUID modelId,
            String domainCode,int modelVersion,RiskTrainingArtifact artifact,Instant completedAt) {
        int completed=jdbc.sql("""
                UPDATE risk.training_run SET status_code='SUCCEEDED',completed_at=:completed
                WHERE training_run_id=:run AND status_code='RUNNING'
                """).param("completed",dbTime(completedAt)).param("run",trainingRunId).update();
        if (completed!=1) throw new IllegalStateException("训练运行状态已经变化，拒绝创建候选模型");
        jdbc.sql("""
                INSERT INTO risk.model_version(
                  model_id,version,domain_code,training_run_id,status_code,artifact_reference,
                  artifact_sha256,metric_definition,threshold_definition,created_at)
                VALUES (:model,:version,:domain,:run,'CANDIDATE',:artifact,:hash,
                        CAST(:metrics AS jsonb),CAST(:thresholds AS jsonb),:created)
                """).param("model",modelId).param("version",modelVersion).param("domain",domainCode)
                .param("run",trainingRunId).param("artifact",artifact.artifactReference())
                .param("hash",artifact.artifactSha256()).param("metrics",writeJson(artifact.metrics()))
                .param("thresholds",writeJson(artifact.thresholds())).param("created",dbTime(completedAt)).update();
        jdbc.sql("""
                UPDATE risk.training_schedule_execution
                SET status_code='SUCCEEDED',training_run_id=:run,outcome_code='CANDIDATE_CREATED',
                    outcome_message=:message,completed_at=:completed,lease_owner=NULL,lease_until=NULL
                WHERE execution_id=:execution AND status_code='RUNNING'
                """).param("run",trainingRunId)
                .param("message","已生成候选模型 v"+modelVersion+"，等待影子评估和人工审批")
                .param("completed",dbTime(completedAt)).param("execution",executionId).update();
    }

    @Override
    @Transactional
    public void failRunAndExecution(UUID executionId,UUID trainingRunId,String failureCode,
            String failureMessage,Instant completedAt) {
        jdbc.sql("""
                UPDATE risk.training_run SET status_code='FAILED',failure_code=:code,
                    failure_message=:message,completed_at=:completed
                WHERE training_run_id=:run AND status_code='RUNNING'
                """).param("code",failureCode).param("message",failureMessage)
                .param("completed",dbTime(completedAt)).param("run",trainingRunId).update();
        finishExecution(executionId,"FAILED",failureCode,failureMessage,completedAt,trainingRunId);
    }

    @Override
    @Transactional
    public void skipExecution(UUID executionId,String outcomeCode,String outcomeMessage,
            Instant completedAt) {
        finishExecution(executionId,"SKIPPED",outcomeCode,outcomeMessage,completedAt,null);
    }

    private void finishExecution(UUID executionId,String status,String code,String message,
            Instant completedAt,UUID runId) {
        jdbc.sql("""
                UPDATE risk.training_schedule_execution
                SET status_code=:status,outcome_code=:code,outcome_message=:message,
                    completed_at=:completed,training_run_id=COALESCE(:run,training_run_id),
                    lease_owner=NULL,lease_until=NULL
                WHERE execution_id=:execution AND status_code='RUNNING'
                """).param("status",status).param("code",code).param("message",message)
                .param("completed",dbTime(completedAt)).param("run",runId)
                .param("execution",executionId).update();
    }

    private LabelRow label(ResultSet row) throws SQLException {
        return new LabelRow(uuid(row,"assessment_id"),instant(row,"resolved_at"),
                row.getString("conclusion_code"),row.getString("domain_code"),
                row.getString("subject_type"),row.getString("evaluation_mode"),
                row.getString("risk_level"),row.getString("reason_codes"),
                row.getString("evidence_snapshot"),row.getString("reason_code"));
    }

    private Object readJson(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("训练证据 JSON 无法读取",exception); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("训练谱系 JSON 无法序列化",exception); }
    }

    private static String trainerCodeSha256(String algorithmCode) {
        Class<?> type="external-lora-adapter-v1".equals(algorithmCode)
                ? ExternalLoraRiskModelTrainer.class : LocalRiskClassifierTrainer.class;
        String resource="/"+type.getName().replace('.','/')+".class";
        try (InputStream input=type.getResourceAsStream(resource)) {
            if (input==null) throw new IllegalStateException("无法读取训练器代码字节");
            return sha256(input.readAllBytes());
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算训练器代码哈希",exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 不可用",exception); }
    }

    private static long seed(UUID value) {
        return value.getMostSignificantBits() ^ value.getLeastSignificantBits();
    }

    private static OffsetDateTime dbTime(Instant value) {
        return OffsetDateTime.ofInstant(value,ZoneOffset.UTC);
    }

    private static UUID uuid(ResultSet row,String column) throws SQLException {
        return row.getObject(column)==null?null:row.getObject(column,UUID.class);
    }

    private static Instant instant(ResultSet row,String column) throws SQLException {
        OffsetDateTime value=row.getObject(column,OffsetDateTime.class);
        return value==null?null:value.toInstant();
    }

    private static Integer integer(ResultSet row,String column) throws SQLException {
        int value=row.getInt(column);
        return row.wasNull()?null:value;
    }

    private record LabelRow(UUID assessmentId,Instant resolvedAt,String conclusionCode,
            String domainCode,String subjectType,String evaluationMode,String riskLevel,
            String reasonCodes,String evidenceSnapshot,String feedbackReasonCode) { }
}
