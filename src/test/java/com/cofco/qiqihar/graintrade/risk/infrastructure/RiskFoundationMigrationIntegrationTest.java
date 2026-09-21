package com.cofco.qiqihar.graintrade.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskFoundationMigrationIntegrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private Connection connection;

    @BeforeAll
    static void migrate() {
        DATABASE.flyway().migrate();
    }

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = DATABASE.openConnection();
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollBackTransaction() throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.close();
        }
    }

    @Test
    void enforcesIdempotentInventoryEvidenceAndGovernedAiPromotion() throws SQLException {
        execute("""
                INSERT INTO overview.storage_facility(
                  facility_code,facility_name,relation_type,region_code,coordinate_precision,
                  operational_status,source_origin,created_by_subject,updated_by_subject)
                VALUES('RISK_TEST_DEPOT','风险底座测试库','OWNED','230200','UNKNOWN','ACTIVE',
                  'USER_SUBMITTED','risk-test','risk-test')
                """);
        execute("SET LOCAL ROLE qiqihar_enterprise_runtime");
        execute("""
                INSERT INTO risk.inventory_source(
                  source_code,source_name,facility_code,source_type,connection_mode,trust_level,
                  status_code,maximum_receive_delay_seconds,created_by_subject,updated_by_subject)
                VALUES('RISK_TEST_WMS','测试WMS','RISK_TEST_DEPOT','WMS','WEBHOOK','AUTHORITATIVE',
                  'ACTIVE',30,'risk-test','risk-test')
                """);
        execute(sourceEventInsert("21400000-0000-0000-0000-000000000001"));

        assertThatThrownBy(() -> execute(
                sourceEventInsert("21400000-0000-0000-0000-000000000009")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("inventory_source_event_source_code_external_event_id_key");

        execute("""
                INSERT INTO risk.inventory_movement(
                  movement_id,source_event_id,movement_leg,movement_type,facility_code,
                  warehouse_code,cargo_owner_code,batch_code,product_code,grade_code,crop_year,
                  quantity_delta_tonnes,occurred_at,posted_at,posted_by_subject)
                VALUES('21400000-0000-0000-0000-000000000002',
                  '21400000-0000-0000-0000-000000000001','SINGLE','STOCK_IN','RISK_TEST_DEPOT',
                  'WH-1','COFCO','BATCH-1','CORN','GRADE-2',2026,10.000000,
                  TIMESTAMPTZ '2026-09-21 01:00:00+00',TIMESTAMPTZ '2026-09-21 01:00:01+00','risk-test')
                """);
        execute("RESET ROLE");
        assertThatThrownBy(() -> execute("""
                UPDATE risk.inventory_movement SET quantity_delta_tonnes=9
                WHERE movement_id='21400000-0000-0000-0000-000000000002'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Inventory movements are immutable");
        assertThatThrownBy(() -> execute("""
                UPDATE risk.inventory_source_event SET original_quantity=9000
                WHERE source_event_id='21400000-0000-0000-0000-000000000001'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Inventory source evidence is immutable");
        execute(reversalSourceEventInsert(
                "21400000-0000-0000-0000-000000000003", "EVENT-REV-BAD", 2, "5000", "5"));
        assertThatThrownBy(() -> execute(reversalMovementInsert(
                "21400000-0000-0000-0000-000000000004",
                "21400000-0000-0000-0000-000000000003", "-5")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("REVERSAL must exactly negate the referenced movement");
        execute(reversalSourceEventInsert(
                "21400000-0000-0000-0000-000000000005", "EVENT-REV-OK", 3, "10000", "10"));
        execute(reversalMovementInsert(
                "21400000-0000-0000-0000-000000000006",
                "21400000-0000-0000-0000-000000000005", "-10"));

        execute("""
                INSERT INTO risk.ai_model(
                  model_id,model_code,model_name,model_kind,domain_code,base_model_reference,
                  purpose_definition,created_by_subject,updated_by_subject)
                VALUES('21400000-0000-0000-0000-000000000010','RISK_AI_CORE',
                  '粮食风险独立研判模型','DOMAIN_LLM','CROSS_DOMAIN','Qwen-compatible-base',
                  '{"purpose":"evidence-bound risk judgement"}'::jsonb,'risk-test','risk-test')
                """);
        execute("""
                UPDATE risk.ai_model SET status_code='ACTIVE',updated_at=now()
                WHERE model_id='21400000-0000-0000-0000-000000000010'
                """);
        assertThatThrownBy(() -> execute("""
                UPDATE risk.ai_model SET base_model_reference='changed-base'
                WHERE model_id='21400000-0000-0000-0000-000000000010'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Active AI model identity and purpose are immutable");
        execute("""
                INSERT INTO risk.ai_training_policy(
                  training_policy_id,model_id,scheduled_local_time,training_window_days,
                  minimum_new_labels,auto_activation_enabled,enabled,approved_by_subject,approved_at)
                VALUES('21400000-0000-0000-0000-000000000011',
                  '21400000-0000-0000-0000-000000000010',TIME '02:30',365,10,true,true,
                  'risk-approver',TIMESTAMPTZ '2026-09-21 01:10:00+00')
                """);
        insertTrainingLineage();
        assertThatThrownBy(() -> execute("""
                UPDATE risk.training_run SET parameter_definition='{"changed":true}'::jsonb
                WHERE training_run_id='21400000-0000-0000-0000-000000000014'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Training identity, inputs, code and parameters are immutable");
        assertThatThrownBy(() -> execute("""
                UPDATE risk.training_run SET started_at=started_at + interval '1 minute'
                WHERE training_run_id='21400000-0000-0000-0000-000000000014'
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Recorded training lifecycle evidence is immutable");

        assertThatThrownBy(() -> execute("""
                INSERT INTO risk.model_version(
                  model_id,version,domain_code,training_run_id,status_code,artifact_reference,
                  artifact_sha256,metric_definition,threshold_definition,
                  shadow_started_at,shadow_completed_at,approved_by_subject,approved_at,activated_at)
                VALUES('21400000-0000-0000-0000-000000000010',2,'INVENTORY',
                  '21400000-0000-0000-0000-000000000014','ACTIVE','mlflow://risk-ai/2',
                  repeat('f',64),'{}'::jsonb,'{}'::jsonb,now(),now(),'risk-approver',now(),now())
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Model versions must be created as CANDIDATE");

        assertThatThrownBy(() -> execute("""
                UPDATE risk.model_version
                SET status_code='ACTIVE',activated_at=now(),approved_by_subject='risk-approver',approved_at=now()
                WHERE model_id='21400000-0000-0000-0000-000000000010' AND version=1
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CANDIDATE models must enter SHADOW before activation");

        execute("""
                UPDATE risk.model_version SET status_code='SHADOW',
                  shadow_started_at=TIMESTAMPTZ '2026-09-21 02:00:00+00'
                WHERE model_id='21400000-0000-0000-0000-000000000010' AND version=1
                """);
        assertThatThrownBy(() -> execute("""
                UPDATE risk.model_version
                SET status_code='APPROVED',
                    shadow_completed_at=TIMESTAMPTZ '2026-09-21 03:00:00+00',
                    approved_by_subject='risk-approver',
                    approved_at=TIMESTAMPTZ '2026-09-21 03:05:00+00'
                WHERE model_id='21400000-0000-0000-0000-000000000010' AND version=1
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("passed recorded evaluation");
        execute("""
                INSERT INTO risk.model_evaluation(
                  model_id,model_version,evaluation_window_start,evaluation_window_end,
                  cohort_definition,metric_definition,passed,evaluated_at)
                VALUES('21400000-0000-0000-0000-000000000010',1,
                  TIMESTAMPTZ '2026-09-21 02:00:00+00',TIMESTAMPTZ '2026-09-21 03:00:00+00',
                  '{"mode":"shadow"}'::jsonb,'{"false_positive_rate":0.02}'::jsonb,true,
                  TIMESTAMPTZ '2026-09-21 03:01:00+00')
                """);
        execute("""
                UPDATE risk.model_version
                SET status_code='APPROVED',
                    shadow_completed_at=TIMESTAMPTZ '2026-09-21 03:00:00+00',
                    approved_by_subject='risk-approver',
                    approved_at=TIMESTAMPTZ '2026-09-21 03:05:00+00'
                WHERE model_id='21400000-0000-0000-0000-000000000010' AND version=1
                """);
        execute("""
                UPDATE risk.model_version SET status_code='ACTIVE',
                  activated_at=TIMESTAMPTZ '2026-09-21 03:10:00+00'
                WHERE model_id='21400000-0000-0000-0000-000000000010' AND version=1
                """);
        execute("""
                INSERT INTO risk.risk_assessment(
                  assessment_id,domain_code,subject_type,subject_id,model_id,model_version,
                  evaluation_mode,risk_level,reason_codes,evidence_snapshot,score,
                  evaluated_at,evaluation_duration_ms)
                VALUES('21400000-0000-0000-0000-000000000021','INVENTORY','WAREHOUSE','WH-1',
                  '21400000-0000-0000-0000-000000000010',1,'AI_ASSISTED','LOW',
                  ARRAY['BALANCE_STABLE'],'{"movementCount":2}'::jsonb,0.1,
                  TIMESTAMPTZ '2026-09-21 03:20:00+00',25)
                """);
        execute("""
                UPDATE risk.ai_model SET status_code='SUSPENDED',updated_at=now()
                WHERE model_id='21400000-0000-0000-0000-000000000010'
                """);
        assertThatThrownBy(() -> execute("""
                INSERT INTO risk.risk_assessment(
                  assessment_id,domain_code,subject_type,subject_id,model_id,model_version,
                  evaluation_mode,risk_level,reason_codes,evidence_snapshot,
                  evaluated_at,evaluation_duration_ms)
                VALUES('21400000-0000-0000-0000-000000000023','INVENTORY','WAREHOUSE','WH-1',
                  '21400000-0000-0000-0000-000000000010',1,'AI_ASSISTED','LOW',
                  ARRAY['SUSPENDED_MODEL_TEST'],'{}'::jsonb,now(),1)
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Model-backed assessments require an ACTIVE AI model identity");
        execute("""
                UPDATE risk.model_version SET status_code='RETIRED',
                  retired_at=TIMESTAMPTZ '2026-09-21 04:00:00+00'
                WHERE model_id='21400000-0000-0000-0000-000000000010' AND version=1
                """);

        assertThat(queryString("""
                SELECT status_code || ':' || (activated_at IS NOT NULL) FROM risk.model_version
                WHERE model_id='21400000-0000-0000-0000-000000000010' AND version=1
                """)).isEqualTo("RETIRED:true");

        execute("""
                INSERT INTO risk.risk_rule_set_version(
                  rule_set_id,version,domain_code,rule_set_name,scope_definition,rule_definition,
                  definition_sha256,missing_data_policy,late_event_policy,created_by_subject)
                VALUES('21400000-0000-0000-0000-000000000020',1,'INVENTORY','库存异常规则',
                  '{}'::jsonb,'{"rule":"balance"}'::jsonb,repeat('1',64),
                  'MANUAL_REVIEW','REPLAY','risk-test')
                """);
        execute("""
                UPDATE risk.risk_rule_set_version SET status_code='REVIEW_PENDING'
                WHERE rule_set_id='21400000-0000-0000-0000-000000000020' AND version=1
                """);
        assertThatThrownBy(() -> execute("""
                UPDATE risk.risk_rule_set_version SET rule_definition='{"rule":"changed"}'::jsonb
                WHERE rule_set_id='21400000-0000-0000-0000-000000000020' AND version=1
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Reviewed rule definitions are immutable");
        execute("""
                UPDATE risk.risk_rule_set_version SET status_code='APPROVED',
                  approved_by_subject='risk-approver',
                  approved_at=TIMESTAMPTZ '2026-09-21 04:10:00+00'
                WHERE rule_set_id='21400000-0000-0000-0000-000000000020' AND version=1
                """);
        assertThatThrownBy(() -> execute("""
                UPDATE risk.risk_rule_set_version SET status_code='ACTIVE',
                  approved_at=TIMESTAMPTZ '2026-09-21 04:11:00+00',
                  effective_from=TIMESTAMPTZ '2026-09-21 04:11:00+00'
                WHERE rule_set_id='21400000-0000-0000-0000-000000000020' AND version=1
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Recorded rule lifecycle evidence is immutable");
        assertThatThrownBy(() -> execute("""
                INSERT INTO risk.risk_assessment(
                  assessment_id,domain_code,subject_type,subject_id,evaluation_mode,risk_level,
                  reason_codes,evidence_snapshot,evaluated_at,evaluation_duration_ms)
                VALUES('21400000-0000-0000-0000-000000000022','INVENTORY','WAREHOUSE','WH-1',
                  'RULE','LOW',ARRAY['RULE_TEST'],'{}'::jsonb,now(),1)
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("RULE assessments require a rule set version");

        insertMarketCandidate();
        assertThatThrownBy(() -> execute("""
                INSERT INTO risk.risk_assessment(
                  assessment_id,domain_code,subject_type,subject_id,model_id,model_version,
                  evaluation_mode,risk_level,reason_codes,evidence_snapshot,
                  evaluated_at,evaluation_duration_ms)
                VALUES('21400000-0000-0000-0000-000000000034','INVENTORY','MARKET_SUBJECT','M-1',
                  '21400000-0000-0000-0000-000000000030',1,'MODEL_SHADOW','LOW',
                  ARRAY['DOMAIN_TEST'],'{}'::jsonb,now(),1)
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Assessment domain must match the model domain or use CROSS_DOMAIN");
    }

    private static String sourceEventInsert(String sourceEventId) {
        return """
                INSERT INTO risk.inventory_source_event(
                  source_event_id,source_code,external_event_id,source_sequence,event_type,
                  facility_code,warehouse_code,cargo_owner_code,batch_code,product_code,grade_code,
                  crop_year,original_quantity,original_unit,standard_quantity_tonnes,
                  conversion_rule_version,occurred_at,received_at,source_timezone,payload_sha256,
                  signature_status,processing_status,posted_at)
                VALUES('%s','RISK_TEST_WMS','EVENT-1',1,
                  'STOCK_IN','RISK_TEST_DEPOT','WH-1','COFCO','BATCH-1','CORN','GRADE-2',2026,
                  10000.000000,'KILOGRAM',10.000000,'KG_TO_TON_V1',
                  TIMESTAMPTZ '2026-09-21 01:00:00+00',TIMESTAMPTZ '2026-09-21 01:00:01+00',
                  'Asia/Shanghai',repeat('a',64),'VERIFIED','POSTED',
                  TIMESTAMPTZ '2026-09-21 01:00:01+00')
                """.formatted(sourceEventId);
    }

    private static String reversalSourceEventInsert(
            String sourceEventId,
            String externalEventId,
            int sourceSequence,
            String originalKilograms,
            String standardTonnes) {
        return """
                INSERT INTO risk.inventory_source_event(
                  source_event_id,source_code,external_event_id,source_sequence,event_type,
                  facility_code,warehouse_code,cargo_owner_code,batch_code,product_code,grade_code,
                  crop_year,original_quantity,original_unit,standard_quantity_tonnes,
                  conversion_rule_version,occurred_at,received_at,source_timezone,payload_sha256,
                  signature_status,processing_status,posted_at)
                VALUES('%s','RISK_TEST_WMS','%s',%d,
                  'REVERSAL','RISK_TEST_DEPOT','WH-1','COFCO','BATCH-1','CORN','GRADE-2',2026,
                  %s,'KILOGRAM',%s,'KG_TO_TON_V1',
                  TIMESTAMPTZ '2026-09-21 01:10:00+00',TIMESTAMPTZ '2026-09-21 01:11:00+00',
                  'Asia/Shanghai',repeat('b',64),'VERIFIED','POSTED',
                  TIMESTAMPTZ '2026-09-21 01:11:00+00')
                """.formatted(
                sourceEventId, externalEventId, sourceSequence, originalKilograms, standardTonnes);
    }

    private static String reversalMovementInsert(
            String movementId, String sourceEventId, String quantityDeltaTonnes) {
        return """
                INSERT INTO risk.inventory_movement(
                  movement_id,source_event_id,movement_leg,movement_type,facility_code,
                  warehouse_code,cargo_owner_code,batch_code,product_code,grade_code,crop_year,
                  quantity_delta_tonnes,reversal_of_movement_id,occurred_at,posted_at,posted_by_subject)
                VALUES('%s','%s','SINGLE','REVERSAL','RISK_TEST_DEPOT','WH-1','COFCO','BATCH-1',
                  'CORN','GRADE-2',2026,%s,'21400000-0000-0000-0000-000000000002',
                  TIMESTAMPTZ '2026-09-21 01:10:00+00',TIMESTAMPTZ '2026-09-21 01:11:00+00','risk-test')
                """.formatted(movementId, sourceEventId, quantityDeltaTonnes);
    }

    private void insertTrainingLineage() throws SQLException {
        execute("""
                INSERT INTO risk.knowledge_snapshot(
                  knowledge_snapshot_id,snapshot_date,cutoff_at,content_sha256,
                  embedding_model_reference,source_manifest,document_count,chunk_count,status_code)
                VALUES('21400000-0000-0000-0000-000000000012',DATE '2026-09-21',
                  TIMESTAMPTZ '2026-09-21 00:00:00+00',repeat('b',64),'embedding-v1',
                  '{"sources":["approved-rules"]}'::jsonb,1,3,'ACTIVE')
                """);
        execute("""
                INSERT INTO risk.training_snapshot(
                  training_snapshot_id,domain_code,snapshot_date,cutoff_at,knowledge_snapshot_id,
                  data_sha256,feature_schema_version,row_count,positive_label_count,
                  negative_label_count,source_watermarks,status_code)
                VALUES('21400000-0000-0000-0000-000000000013','CROSS_DOMAIN',DATE '2026-09-21',
                  TIMESTAMPTZ '2026-09-21 00:00:00+00',
                  '21400000-0000-0000-0000-000000000012',repeat('c',64),'inventory-v1',100,10,90,
                  '{"RISK_TEST_WMS":1}'::jsonb,'FROZEN')
                """);
        execute("""
                INSERT INTO risk.training_run(
                  training_run_id,model_id,training_snapshot_id,domain_code,training_kind,algorithm_code,
                  algorithm_version,code_sha256,parameter_definition,random_seed,status_code,
                  started_at,completed_at)
                VALUES('21400000-0000-0000-0000-000000000014',
                  '21400000-0000-0000-0000-000000000010',
                  '21400000-0000-0000-0000-000000000013','CROSS_DOMAIN','LORA_ADAPTER',
                  'RISK_AI_TRAINER','1',repeat('d',64),'{}'::jsonb,214,'QUEUED',NULL,NULL)
                """);
        execute("""
                UPDATE risk.training_run SET status_code='RUNNING',started_at=now()
                WHERE training_run_id='21400000-0000-0000-0000-000000000014'
                """);
        execute("""
                UPDATE risk.training_run SET status_code='SUCCEEDED',completed_at=now()
                WHERE training_run_id='21400000-0000-0000-0000-000000000014'
                """);
        execute("""
                INSERT INTO risk.model_version(
                  model_id,version,domain_code,training_run_id,artifact_reference,artifact_sha256,
                  metric_definition,threshold_definition)
                VALUES('21400000-0000-0000-0000-000000000010',1,'CROSS_DOMAIN',
                  '21400000-0000-0000-0000-000000000014','mlflow://risk-ai/1',repeat('e',64),
                  '{"false_positive_rate":0.02}'::jsonb,'{"minimum_confidence":0.8}'::jsonb)
                """);
    }

    private void insertMarketCandidate() throws SQLException {
        execute("""
                INSERT INTO risk.ai_model(
                  model_id,model_code,model_name,model_kind,domain_code,base_model_reference,
                  purpose_definition,created_by_subject,updated_by_subject)
                VALUES('21400000-0000-0000-0000-000000000030','RISK_MARKET_MODEL','市场风险模型',
                  'FORECAST','MARKET','market-base','{}'::jsonb,'risk-test','risk-test')
                """);
        execute("""
                UPDATE risk.ai_model SET status_code='ACTIVE',updated_at=now()
                WHERE model_id='21400000-0000-0000-0000-000000000030'
                """);
        execute("""
                INSERT INTO risk.training_snapshot(
                  training_snapshot_id,domain_code,snapshot_date,cutoff_at,knowledge_snapshot_id,
                  data_sha256,feature_schema_version,row_count,positive_label_count,
                  negative_label_count,source_watermarks,status_code)
                VALUES('21400000-0000-0000-0000-000000000031','MARKET',DATE '2026-09-21',
                  TIMESTAMPTZ '2026-09-21 00:00:00+00',
                  '21400000-0000-0000-0000-000000000012',repeat('2',64),'market-v1',20,2,18,
                  '{}'::jsonb,'FROZEN')
                """);
        execute("""
                INSERT INTO risk.training_run(
                  training_run_id,model_id,training_snapshot_id,domain_code,training_kind,
                  algorithm_code,algorithm_version,code_sha256,parameter_definition,random_seed,
                  status_code)
                VALUES('21400000-0000-0000-0000-000000000032',
                  '21400000-0000-0000-0000-000000000030',
                  '21400000-0000-0000-0000-000000000031','MARKET','RETRAIN',
                  'MARKET_TRAINER','1',repeat('3',64),'{}'::jsonb,32,'QUEUED')
                """);
        execute("""
                UPDATE risk.training_run SET status_code='RUNNING',started_at=now()
                WHERE training_run_id='21400000-0000-0000-0000-000000000032'
                """);
        execute("""
                UPDATE risk.training_run SET status_code='SUCCEEDED',completed_at=now()
                WHERE training_run_id='21400000-0000-0000-0000-000000000032'
                """);
        execute("""
                INSERT INTO risk.model_version(
                  model_id,version,domain_code,training_run_id,artifact_reference,artifact_sha256,
                  metric_definition,threshold_definition)
                VALUES('21400000-0000-0000-0000-000000000030',1,'MARKET',
                  '21400000-0000-0000-0000-000000000032','mlflow://risk-market/1',repeat('4',64),
                  '{}'::jsonb,'{}'::jsonb)
                """);
    }

    private void execute(String sql) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.releaseSavepoint(savepoint);
        } catch (SQLException exception) {
            connection.rollback(savepoint);
            throw exception;
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
