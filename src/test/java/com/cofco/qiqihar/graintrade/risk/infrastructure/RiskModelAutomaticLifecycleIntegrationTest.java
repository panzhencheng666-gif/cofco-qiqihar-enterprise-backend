package com.cofco.qiqihar.graintrade.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.risk.application.LocalRiskClassifierScorer;
import com.cofco.qiqihar.graintrade.risk.application.LocalRiskClassifierTrainer;
import com.cofco.qiqihar.graintrade.risk.application.RiskModelLifecycleOrchestrator;
import com.cofco.qiqihar.graintrade.risk.application.RiskPromotionGate;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingExample;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingJob;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class RiskModelAutomaticLifecycleIntegrationTest {
    private static final ProtectedTestDatabase DATABASE=ProtectedTestDatabase.shared();
    private static final UUID MODEL_ID=UUID.fromString("21600000-0000-0000-0000-000000000001");
    private static final UUID RULE_ID=UUID.fromString("21600000-0000-0000-0000-000000000002");
    private static final Instant SHADOW_START=Instant.parse("2026-09-21T04:00:00Z");

    @BeforeAll
    static void migrate() {
        DATABASE.flyway().migrate();
    }

    @Test
    void scoresUnseenCasesBeforeTheirLabelsAndAutomaticallyActivatesPassingCandidate(
            @TempDir Path artifactRoot) throws Exception {
        JdbcClient jdbc=JdbcClient.create(DATABASE.dataSource());
        ObjectMapper json=new ObjectMapper();
        seedModelAndCandidate(jdbc,json,artifactRoot);
        var repository=new JdbcRiskModelLifecycleRepository(jdbc,json);
        var firstPass=new RiskModelLifecycleOrchestrator(repository,
                new LocalRiskClassifierScorer(json),new RiskPromotionGate(),
                Clock.fixed(SHADOW_START,ZoneOffset.UTC));

        assertThat(firstPass.process()).isGreaterThanOrEqualTo(1);
        assertThat(status(jdbc,1)).isEqualTo("SHADOW");

        insertUnresolvedShadowCases(jdbc);
        var scoringPass=new RiskModelLifecycleOrchestrator(repository,
                new LocalRiskClassifierScorer(json),new RiskPromotionGate(),
                Clock.fixed(SHADOW_START.plusSeconds(120),ZoneOffset.UTC));
        assertThat(scoringPass.process()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT count(*) FROM risk.model_live_prediction WHERE model_id=:model")
                .param("model",MODEL_ID).query(Integer.class).single()).isEqualTo(5);
        assertThat(status(jdbc,1)).isEqualTo("SHADOW");

        resolveShadowCases(jdbc);
        var promotionPass=new RiskModelLifecycleOrchestrator(repository,
                new LocalRiskClassifierScorer(json),new RiskPromotionGate(),
                Clock.fixed(SHADOW_START.plusSeconds(300),ZoneOffset.UTC));
        assertThat(promotionPass.process()).isGreaterThanOrEqualTo(1);

        assertThat(status(jdbc,1)).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM risk.model_evaluation WHERE model_id=:model AND passed")
                .param("model",MODEL_ID).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT event_code FROM risk.model_activation_event WHERE model_id=:model")
                .param("model",MODEL_ID).query(String.class).single())
                .isEqualTo("AUTO_ACTIVATED");
        assertThat(jdbc.sql("""
                SELECT (metric_definition->>'resolvedLabelCount')::integer
                FROM risk.model_activation_event WHERE model_id=:model
                """).param("model",MODEL_ID).query(Integer.class).single()).isEqualTo(4);

        seedDegradedSecondActiveVersion(jdbc);
        var rollbackPass=new RiskModelLifecycleOrchestrator(repository,
                new LocalRiskClassifierScorer(json),new RiskPromotionGate(),
                Clock.fixed(SHADOW_START.plusSeconds(900),ZoneOffset.UTC));
        assertThat(rollbackPass.process()).isGreaterThanOrEqualTo(1);
        assertThat(status(jdbc,1)).isEqualTo("ACTIVE");
        assertThat(status(jdbc,2)).isEqualTo("RETIRED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM risk.model_activation_event
                WHERE model_id=:model AND event_code='AUTO_ROLLED_BACK'
                """).param("model",MODEL_ID).query(Integer.class).single()).isEqualTo(1);
    }

    private static void seedModelAndCandidate(JdbcClient jdbc,ObjectMapper json,Path artifactRoot)
            throws Exception {
        jdbc.sql("UPDATE risk.ai_training_policy SET auto_activation_enabled=false").update();
        jdbc.sql("""
                INSERT INTO risk.risk_rule_set_version(
                  rule_set_id,version,domain_code,rule_set_name,status_code,scope_definition,
                  rule_definition,definition_sha256,missing_data_policy,late_event_policy,
                  created_by_subject)
                VALUES (:rule,1,'INVENTORY','自动晋级测试规则','DRAFT','{}','{}',repeat('b',64),
                  'MANUAL_REVIEW','MANUAL_REVIEW','test')
                """).param("rule",RULE_ID).update();
        jdbc.sql("""
                UPDATE risk.risk_rule_set_version SET status_code='REVIEW_PENDING'
                WHERE rule_set_id=:rule AND version=1
                """).param("rule",RULE_ID).update();
        jdbc.sql("""
                UPDATE risk.risk_rule_set_version SET status_code='APPROVED',
                  approved_by_subject='test',approved_at=:at
                WHERE rule_set_id=:rule AND version=1
                """).param("rule",RULE_ID).param("at",SHADOW_START.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                UPDATE risk.risk_rule_set_version SET status_code='ACTIVE',effective_from=:at
                WHERE rule_set_id=:rule AND version=1
                """).param("rule",RULE_ID).param("at",SHADOW_START.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO risk.ai_model(
                  model_id,model_code,model_name,model_kind,domain_code,isolation_scope,
                  base_model_reference,purpose_definition,status_code,created_by_subject,
                  updated_by_subject,created_at,updated_at)
                VALUES (:model,'risk-auto-lifecycle-test','自动晋级测试模型','RISK_CLASSIFIER',
                  'CROSS_DOMAIN','RISK_SYSTEM','builtin://bernoulli-naive-bayes/v1','{}','DRAFT',
                  'test','test',:at,:at)
                """).param("model",MODEL_ID).param("at",SHADOW_START.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                UPDATE risk.ai_model SET status_code='ACTIVE',updated_at=:at
                WHERE model_id=:model AND status_code='DRAFT'
                """).param("model",MODEL_ID).param("at",SHADOW_START.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO risk.ai_training_policy(
                  training_policy_id,model_id,frequency_code,scheduled_local_time,schedule_timezone,
                  training_window_days,minimum_new_labels,automatic_candidate_enabled,
                  auto_activation_enabled,enabled,approved_by_subject,approved_at,updated_at,
                  minimum_shadow_labels,minimum_shadow_hours,minimum_shadow_f1,
                  maximum_f1_regression,rollback_f1_drop)
                VALUES ('21600000-0000-0000-0000-000000000003',:model,'DAILY','02:15:00',
                  'Asia/Shanghai',90,4,true,true,true,'test',:at,:at,4,0,0.60,0.02,0.05)
                """).param("model",MODEL_ID).param("at",SHADOW_START.atOffset(ZoneOffset.UTC)).update();

        UUID snapshot=UUID.fromString("21600000-0000-0000-0000-000000000004");
        UUID run=UUID.fromString("21600000-0000-0000-0000-000000000005");
        jdbc.sql("""
                INSERT INTO risk.training_snapshot(
                  training_snapshot_id,domain_code,snapshot_date,cutoff_at,data_sha256,
                  feature_schema_version,row_count,positive_label_count,negative_label_count,
                  source_watermarks,status_code)
                VALUES (:snapshot,'CROSS_DOMAIN',DATE '2026-09-21',:at,repeat('c',64),
                  'risk-feedback-v1',6,3,3,'{}','USED')
                """).param("snapshot",snapshot).param("at",SHADOW_START.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO risk.training_run(
                  training_run_id,model_id,training_snapshot_id,domain_code,training_kind,
                  algorithm_code,algorithm_version,code_sha256,parameter_definition,random_seed,
                  status_code,created_at)
                VALUES (:run,:model,:snapshot,'CROSS_DOMAIN','RETRAIN',
                  'builtin-bernoulli-naive-bayes','1',repeat('d',64),'{}',7,'QUEUED',:at)
                """).param("run",run).param("model",MODEL_ID).param("snapshot",snapshot)
                .param("at",SHADOW_START.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                UPDATE risk.training_run SET status_code='RUNNING',started_at=:at
                WHERE training_run_id=:run AND status_code='QUEUED'
                """).param("run",run).param("at",SHADOW_START.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                UPDATE risk.training_run SET status_code='SUCCEEDED',completed_at=:at
                WHERE training_run_id=:run AND status_code='RUNNING'
                """).param("run",run).param("at",SHADOW_START.atOffset(ZoneOffset.UTC)).update();
        List<RiskTrainingExample> examples=List.of(
                example("库存 短缺 高风险",true,1),example("库存 异常 高风险",true,2),
                example("运输 中断 高风险",true,3),example("库存 正常 稳定",false,4),
                example("供应 正常 稳定",false,5),example("运输 正常 稳定",false,6));
        var artifact=new LocalRiskClassifierTrainer(json,artifactRoot).train(new RiskTrainingJob(
                MODEL_ID,"risk-auto-lifecycle-test","CROSS_DOMAIN",1,snapshot,7L,examples));
        jdbc.sql("""
                INSERT INTO risk.model_version(
                  model_id,version,domain_code,training_run_id,status_code,artifact_reference,
                  artifact_sha256,metric_definition,threshold_definition,created_at)
                VALUES (:model,1,'CROSS_DOMAIN',:run,'CANDIDATE',:artifact,:hash,
                  CAST(:metrics AS jsonb),CAST(:thresholds AS jsonb),:at)
                """).param("model",MODEL_ID).param("run",run)
                .param("artifact",artifact.artifactReference()).param("hash",artifact.artifactSha256())
                .param("metrics",json.writeValueAsString(artifact.metrics()))
                .param("thresholds",json.writeValueAsString(artifact.thresholds()))
                .param("at",SHADOW_START.atOffset(ZoneOffset.UTC)).update();
    }

    private static void insertUnresolvedShadowCases(JdbcClient jdbc) {
        for (int index=1;index<=5;index++) {
            boolean positive=index<=2;
            UUID assessment=UUID.fromString("21600000-0000-0000-0000-%012d".formatted(100+index));
            jdbc.sql("""
                    INSERT INTO risk.risk_assessment(
                      assessment_id,domain_code,subject_type,subject_id,rule_set_id,rule_set_version,
                      evaluation_mode,risk_level,reason_codes,evidence_snapshot,evaluated_at,
                      evaluation_duration_ms)
                    VALUES (:id,'INVENTORY','WAREHOUSE',:subject,:rule,1,'RULE',:level,:reasons,
                      CAST(:evidence AS jsonb),:evaluated,10)
                    """).param("id",assessment).param("subject","shadow-"+index).param("rule",RULE_ID)
                    .param("level",positive?"HIGH":"NONE")
                    .param("reasons",positive?new String[]{"库存","短缺","高风险"}
                            :new String[]{"库存","正常","稳定"})
                    .param("evidence",positive?"{\"signal\":\"库存 短缺 高风险\"}"
                            :"{\"signal\":\"库存 正常 稳定\"}")
                    .param("evaluated",SHADOW_START.plusSeconds(60+index).atOffset(ZoneOffset.UTC)).update();
        }
    }

    private static void resolveShadowCases(JdbcClient jdbc) {
        for (int index=1;index<=5;index++) {
            boolean positive=index<=2;
            UUID assessment=UUID.fromString("21600000-0000-0000-0000-%012d".formatted(100+index));
            UUID feedback=UUID.fromString("21600000-0000-0000-0000-%012d".formatted(200+index));
            jdbc.sql("""
                    INSERT INTO risk.risk_case_feedback(
                      feedback_id,assessment_id,conclusion_code,reason_code,disposition_note,
                      resolved_by_subject,resolved_at)
                    VALUES (:id,:assessment,:conclusion,'SOURCE_VERIFIED','影子结果核验',
                      'test',:resolved)
                    """).param("id",feedback).param("assessment",assessment)
                    .param("conclusion",index==5?"INSUFFICIENT_EVIDENCE":
                            positive?"CONFIRMED":"FALSE_POSITIVE")
                    .param("resolved",SHADOW_START.plusSeconds(180+index).atOffset(ZoneOffset.UTC)).update();
        }
    }

    private static void seedDegradedSecondActiveVersion(JdbcClient jdbc) {
        UUID run=UUID.fromString("21600000-0000-0000-0000-000000000006");
        UUID snapshot=UUID.fromString("21600000-0000-0000-0000-000000000004");
        Instant activated=SHADOW_START.plusSeconds(420);
        jdbc.sql("""
                INSERT INTO risk.training_run(
                  training_run_id,model_id,training_snapshot_id,domain_code,training_kind,
                  algorithm_code,algorithm_version,code_sha256,parameter_definition,random_seed,
                  status_code,created_at)
                VALUES (:run,:model,:snapshot,'CROSS_DOMAIN','RETRAIN',
                  'builtin-bernoulli-naive-bayes','1',repeat('e',64),'{}',8,'QUEUED',:at)
                """).param("run",run).param("model",MODEL_ID).param("snapshot",snapshot)
                .param("at",activated.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                UPDATE risk.training_run SET status_code='RUNNING',started_at=:at
                WHERE training_run_id=:run
                """).param("run",run).param("at",activated.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                UPDATE risk.training_run SET status_code='SUCCEEDED',completed_at=:at
                WHERE training_run_id=:run
                """).param("run",run).param("at",activated.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO risk.model_version(
                  model_id,version,domain_code,training_run_id,status_code,artifact_reference,
                  artifact_sha256,metric_definition,threshold_definition,created_at)
                SELECT model_id,2,domain_code,:run,'CANDIDATE',artifact_reference,
                       artifact_sha256,metric_definition,threshold_definition,:at
                FROM risk.model_version WHERE model_id=:model AND version=1
                """).param("run",run).param("model",MODEL_ID)
                .param("at",activated.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                UPDATE risk.model_version SET status_code='SHADOW',shadow_started_at=:at
                WHERE model_id=:model AND version=2
                """).param("model",MODEL_ID).param("at",activated.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO risk.model_evaluation(
                  model_id,model_version,evaluation_window_start,evaluation_window_end,
                  cohort_definition,metric_definition,passed,evaluated_at)
                VALUES (:model,2,:start,:end,'{}','{"candidateF1":1.0}',true,:evaluated)
                """).param("model",MODEL_ID).param("start",activated.atOffset(ZoneOffset.UTC))
                .param("end",activated.plusSeconds(1).atOffset(ZoneOffset.UTC))
                .param("evaluated",activated.plusSeconds(2).atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                UPDATE risk.model_version SET status_code='APPROVED',
                  shadow_completed_at=:at,approved_by_subject='system:auto-promotion',approved_at=:at
                WHERE model_id=:model AND version=2
                """).param("model",MODEL_ID)
                .param("at",activated.plusSeconds(2).atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("UPDATE risk.model_version SET status_code='STANDBY' WHERE model_id=:model AND version=1")
                .param("model",MODEL_ID).update();
        jdbc.sql("""
                UPDATE risk.model_version SET status_code='ACTIVE',activated_at=:at
                WHERE model_id=:model AND version=2
                """).param("model",MODEL_ID)
                .param("at",activated.plusSeconds(3).atOffset(ZoneOffset.UTC)).update();

        for (int index=1;index<=4;index++) {
            boolean actual=index<=2;
            UUID assessment=UUID.fromString("21600000-0000-0000-0000-%012d".formatted(300+index));
            UUID feedback=UUID.fromString("21600000-0000-0000-0000-%012d".formatted(400+index));
            Instant evaluated=activated.plusSeconds(20+index);
            Instant scored=activated.plusSeconds(40+index);
            Instant resolved=activated.plusSeconds(60+index);
            jdbc.sql("""
                    INSERT INTO risk.risk_assessment(
                      assessment_id,domain_code,subject_type,subject_id,rule_set_id,rule_set_version,
                      evaluation_mode,risk_level,reason_codes,evidence_snapshot,evaluated_at,
                      evaluation_duration_ms)
                    VALUES (:id,'INVENTORY','WAREHOUSE',:subject,:rule,1,'RULE',:level,
                      ARRAY['rollback'],'{}',:evaluated,10)
                    """).param("id",assessment).param("subject","rollback-"+index)
                    .param("rule",RULE_ID).param("level",actual?"HIGH":"NONE")
                    .param("evaluated",evaluated.atOffset(ZoneOffset.UTC)).update();
            jdbc.sql("""
                    INSERT INTO risk.model_live_prediction(
                      model_id,model_version,assessment_id,lifecycle_phase,predicted_positive,
                      positive_probability,scored_at)
                    VALUES (:model,2,:assessment,'ACTIVE',:bad,:bad_probability,:scored),
                           (:model,1,:assessment,'STANDBY',:good,:good_probability,:scored)
                    """).param("model",MODEL_ID).param("assessment",assessment)
                    .param("bad",!actual).param("bad_probability",actual?0.10:0.90)
                    .param("good",actual).param("good_probability",actual?0.90:0.10)
                    .param("scored",scored.atOffset(ZoneOffset.UTC)).update();
            jdbc.sql("""
                    INSERT INTO risk.risk_case_feedback(
                      feedback_id,assessment_id,conclusion_code,reason_code,disposition_note,
                      resolved_by_subject,resolved_at)
                    VALUES (:id,:assessment,:conclusion,'SOURCE_VERIFIED','回滚监测核验',
                      'test',:resolved)
                    """).param("id",feedback).param("assessment",assessment)
                    .param("conclusion",actual?"CONFIRMED":"FALSE_POSITIVE")
                    .param("resolved",resolved.atOffset(ZoneOffset.UTC)).update();
        }
    }

    private static String status(JdbcClient jdbc,int version) {
        return jdbc.sql("SELECT status_code FROM risk.model_version WHERE model_id=:model AND version=:version")
                .param("model",MODEL_ID).param("version",version).query(String.class).single();
    }

    private static RiskTrainingExample example(String text,boolean positive,int sequence) {
        return new RiskTrainingExample(UUID.randomUUID(),
                SHADOW_START.minusSeconds(700-sequence),text,positive);
    }
}
